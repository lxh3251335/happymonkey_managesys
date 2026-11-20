package cn.tianshang.commerce.finance;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.ContractAttachmentService;
import cn.tianshang.commerce.contract.ContractExecutionService;
import cn.tianshang.commerce.contract.ContractLedger;
import cn.tianshang.commerce.contract.ContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 发票登记：读电子发票 PDF 认出各字段，一张发票可以分到同一客户的多份合同。
 *
 * <p>每份合同的那一部分仍按原来的合同开票登记（{@link ContractExecutionService#invoice}），同一张发票的各行共用一个分组；
 * 发票 PDF 存进每份相关合同的附件（类别“发票”）。
 */
@Service
public class InvoiceEntryService {

    static final long MAX_PDF_BYTES = 20L * 1024 * 1024;

    private final Database db;
    private final ContractService contracts;
    private final ContractExecutionService execution;
    private final ContractAttachmentService attachments;

    public InvoiceEntryService(
        Database db,
        ContractService contracts,
        ContractExecutionService execution,
        ContractAttachmentService attachments
    ) {
        this.db = db;
        this.contracts = contracts;
        this.execution = execution;
        this.attachments = attachments;
    }

    // ===================== 识别 =====================

    /** 读 PDF：返回认出的字段、对上的客户和分配建议。认不出时各字段为空，由人填写。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("请选择发票 PDF");
        if (file.getSize() > MAX_PDF_BYTES) throw new ApiException("发票文件不能超过 20 MB");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) throw new ApiException("目前只能识别 PDF 格式的电子发票；图片或 OFD 请手工填写");
        String text;
        try (var doc = Loader.loadPDF(file.getBytes())) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(doc);
        } catch (IOException e) {
            throw new ApiException("这个 PDF 打不开，请确认文件没有损坏");
        }
        var p = InvoiceParser.parse(text);
        var out = new LinkedHashMap<String, Object>();
        out.put("recognized", !p.empty());
        out.put("invoiceNo", p.invoiceNo());
        out.put("issuedOn", p.issuedOn());
        out.put("amount", p.amount());
        out.put("taxRate", p.taxRate());
        out.put("invoiceType", p.invoiceType());
        out.put("buyerName", p.buyerName());
        out.put("buyerTaxNo", p.buyerTaxNo());
        out.put("sellerName", p.sellerName());
        out.put("remark", p.remark());
        String customerId = matchCustomer(p.buyerName(), p.buyerTaxNo());
        out.put("customerId", customerId);
        if (
            p.invoiceNo() != null &&
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_invoice WHERE invoice_no=? AND deleted_at IS NULL",
                Integer.class,
                p.invoiceNo()
            ) >
            0
        ) out.put("duplicate", true);
        if (customerId != null && p.amount() != null) out.put("suggestion", suggest(customerId, p.amount(), text));
        return out;
    }

    /** 按税号、开票抬头、名称依次找客户；名称用“互相包含”来对（系统里常是简称）。 */
    private String matchCustomer(String buyerName, String buyerTaxNo) {
        var customers = db.jdbc.queryForList("SELECT id,name,invoice_title,tax_no FROM partner WHERE kind='CUSTOMER'");
        if (buyerTaxNo != null) for (var c : customers)
            if (buyerTaxNo.equalsIgnoreCase(db.text(c, "tax_no"))) return c.get("id").toString();
        if (buyerName == null) return null;
        String best = null;
        int bestLength = 0;
        for (var c : customers)
            for (String key : List.of(db.text(c, "invoice_title"), db.text(c, "name"))) {
                if (key.length() < 2) continue;
                if ((buyerName.contains(key) || key.contains(buyerName)) && key.length() > bestLength) {
                    best = c.get("id").toString();
                    bestLength = key.length();
                }
            }
        return best;
    }

    /**
     * 分配建议。hint 是发票上的文字（主要是备注）：里面写了合同号的，优先记到那些合同；
     * 否则金额正好等于某一份或某几份合同的未开票金额；再否则从最早的合同开始。
     */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> suggest(String customerId, BigDecimal amount, String hint) {
        if (amount == null || amount.signum() <= 0) throw new ApiException("请先填写价税合计");
        var open = new ArrayList<ReceiptAllocator.Open>();
        var named = new ArrayList<ReceiptAllocator.Open>();
        String h = hint == null ? "" : hint.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        for (var c : contracts.contracts()) {
            if (!customerId.equals(c.get("customer_id"))) continue;
            var uninvoiced = ContractLedger.decimal(c.get("uninvoiced"));
            if (uninvoiced.signum() <= 0) continue;
            var date = c.get("signed_on") != null ? c.get("signed_on") : c.get("order_date");
            var o = new ReceiptAllocator.Open(
                c.get("contract_key").toString(),
                uninvoiced,
                null,
                date == null ? null : ContractLedger.date(date)
            );
            open.add(o);
            String no = Objects.toString(c.get("contract_no"), "");
            if (no.length() >= 4 && h.contains(no.toUpperCase(Locale.ROOT))) named.add(o);
        }
        String rule;
        ReceiptAllocator.Suggestion s;
        if (!named.isEmpty()) {
            // 备注里点了名的合同：在这些合同里分
            s = ReceiptAllocator.suggest(amount, named, List.of());
            rule = "REMARK";
        } else {
            s = ReceiptAllocator.suggest(amount, open, List.of());
            rule = s.rule();
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("rule", rule);
        out.put("unallocated", s.unallocated());
        out.put(
            "allocations",
            s
                .shares()
                .stream()
                .map(x -> Map.of("contractKey", x.contractKey(), "amount", x.amount()))
                .toList()
        );
        return out;
    }

    // ===================== 登记 =====================

    public record Allocation(
        @NotBlank String contractKey,
        @NotNull @DecimalMin("0.01") @Digits(integer = 11, fraction = 2) BigDecimal amount
    ) {}

    public record EntryInput(
        @NotBlank String customerId,
        @NotBlank @Size(max = 60) @Pattern(
            regexp = "[0-9A-Za-z\\-]+",
            message = "发票号码只能包含数字、字母和短横线"
        ) String invoiceNo,
        @Pattern(regexp = "^$|SPECIAL|NORMAL") String invoiceType,
        @NotNull @PastOrPresent LocalDate issuedOn,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
        @Size(max = 1000) String note,
        @NotEmpty List<@Valid Allocation> allocations
    ) {}

    /** 登记一张发票并分到合同；各合同金额之和必须等于价税合计。file 为发票原件，可不传。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, Object> create(@Valid EntryInput in, MultipartFile file) {
        var total = in.allocations().stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(in.amount()) != 0) throw new ApiException(
            "分到各合同的金额合计 " +
                total.toPlainString() +
                " 元，应等于价税合计 " +
                in.amount().toPlainString() +
                " 元"
        );
        // 动笔之前逐份核对：合同存在、不重复、不超过该合同还没开票的金额
        var open = new HashMap<String, BigDecimal>();
        for (var c : contracts.contracts())
            if (in.customerId().equals(c.get("customer_id"))) open.put(
                c.get("contract_key").toString(),
                ContractLedger.decimal(c.get("uninvoiced"))
            );
        var seen = new HashSet<String>();
        for (var a : in.allocations()) {
            if (!seen.add(a.contractKey())) throw new ApiException("同一份合同在分配里出现了两次：" + a.contractKey());
            var left = open.get(a.contractKey());
            if (left == null) throw new ApiException("合同 " + a.contractKey() + " 不属于这位客户，或你无权查看");
            if (a.amount().compareTo(left) > 0) throw new ApiException(
                "合同 " +
                    a.contractKey() +
                    " 只剩 " +
                    left.toPlainString() +
                    " 元未开票，不能记 " +
                    a.amount().toPlainString() +
                    " 元"
            );
        }
        boolean single = in.allocations().size() == 1;
        String groupId = single ? null : db.id();
        String sha = file == null || file.isEmpty() ? null : sha256(file);
        int attached = 0;
        for (var a : in.allocations()) {
            execution.invoice(
                new ContractExecutionService.InvoiceInput(
                    in.customerId(),
                    a.contractKey(),
                    "INVOICE",
                    in.invoiceNo().trim(),
                    in.invoiceType(),
                    in.issuedOn(),
                    a.amount(),
                    in.taxRate(),
                    null,
                    in.note()
                ),
                groupId
            );
            // 同一个文件已经在这份合同的附件里就不重复存
            if (
                sha != null &&
                db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contract_attachment WHERE customer_id=? AND contract_no=? AND sha256=? AND deleted_at IS NULL",
                    Integer.class,
                    in.customerId(),
                    a.contractKey(),
                    sha
                ) ==
                0
            ) {
                attachments.upload(in.customerId(), a.contractKey(), "INVOICE", "发票 " + in.invoiceNo().trim(), file);
                attached++;
            }
        }
        return Map.of("contracts", in.allocations().size(), "attached", attached);
    }

    private static String sha256(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (Exception e) {
            throw new ApiException("读取发票文件失败");
        }
    }
}
