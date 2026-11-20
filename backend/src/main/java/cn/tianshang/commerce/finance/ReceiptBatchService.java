package cn.tianshang.commerce.finance;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.ContractLedger;
import cn.tianshang.commerce.contract.ContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回款单：银行到账的一笔钱，可以一次分到同一客户的多份合同上。
 *
 * <p>每份合同的那一部分仍按原来的合同回款登记（{@link ContractService#receipt}，再按门店剩余应收分摊），
 * 只是多了一个共同的回款单。没分完的余额留作“待分配”，不冲减任何合同的应收，以后可以继续分。
 */
@Service
public class ReceiptBatchService {

    private final Database db;
    private final Ownership ownership;
    private final ContractService contracts;

    public ReceiptBatchService(Database db, Ownership ownership, ContractService contracts) {
        this.db = db;
        this.ownership = ownership;
        this.contracts = contracts;
    }

    public record Allocation(
        @NotBlank String contractKey,
        @NotNull @DecimalMin("0.01") @Digits(integer = 11, fraction = 2) BigDecimal amount
    ) {}

    public record BatchInput(
        @NotBlank String customerId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent LocalDate paidOn,
        @Size(max = 100) String reference,
        @Size(max = 200) String payer,
        @Size(max = 1000) String note,
        @Size(max = 4000) String sourceText,
        @NotNull List<@Valid Allocation> allocations
    ) {}

    public record AllocateInput(@NotEmpty List<@Valid Allocation> allocations) {}

    /** 登记一笔到账并分到合同；allocations 可以为空（全部待分配）。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, Object> create(@Valid BatchInput in) {
        db.one("SELECT id FROM partner WHERE id=? AND kind='CUSTOMER'", in.customerId());
        var allocated = total(in.allocations());
        if (allocated.compareTo(in.amount()) > 0) throw new ApiException(
            "分到各合同的金额合计 " +
                allocated.toPlainString() +
                " 元，超过了到账金额 " +
                in.amount().toPlainString() +
                " 元"
        );
        check(in.customerId(), in.allocations());
        String reference =
            in.reference() == null || in.reference().isBlank() ? nextReference(in.paidOn()) : in.reference().trim();
        if (exists(reference)) throw new ApiException("凭证号 " + reference + " 已登记过");
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO receipt_batch(id,customer_id,amount,paid_on,reference,payer,note,source_text,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
            id,
            in.customerId(),
            in.amount(),
            in.paidOn(),
            reference,
            blankToNull(in.payer()),
            blankToNull(in.note()),
            blankToNull(in.sourceText()),
            Ownership.username()
        );
        post(id, in.customerId(), in.paidOn(), reference, in.note(), in.allocations(), 0);
        var left = in.amount().subtract(allocated);
        db.audit(
            "RECEIPT_BATCH_CREATED",
            id,
            "到账 " +
                in.amount() +
                "，分到 " +
                in.allocations().size() +
                " 份合同" +
                (left.signum() > 0 ? "，待分配 " + left : "")
        );
        return Map.of("id", id, "reference", reference, "contracts", in.allocations().size(), "unallocated", left);
    }

    /** 把回款单里还没分配的余额继续分到合同上。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, Object> allocate(String batchId, @Valid AllocateInput in) {
        var batch = db.one("SELECT * FROM receipt_batch WHERE id=? FOR UPDATE", batchId);
        var used = ContractLedger.decimal(
            db.jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM contract_receipt WHERE batch_id=? AND kind='RECEIPT'",
                BigDecimal.class,
                batchId
            )
        );
        var left = ContractLedger.decimal(batch.get("amount")).subtract(used);
        var adding = total(in.allocations());
        if (adding.compareTo(left) > 0) throw new ApiException(
            "这笔回款只剩 " + left.toPlainString() + " 元待分配，本次要分 " + adding.toPlainString() + " 元"
        );
        check(batch.get("customer_id").toString(), in.allocations());
        int existing = db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM contract_receipt WHERE batch_id=?",
            Integer.class,
            batchId
        );
        post(
            batchId,
            batch.get("customer_id").toString(),
            ContractLedger.date(batch.get("paid_on")),
            batch.get("reference").toString(),
            db.text(batch, "note"),
            in.allocations(),
            existing
        );
        db.audit(
            "RECEIPT_BATCH_ALLOCATED",
            batchId,
            "分配 " + adding + " 到 " + in.allocations().size() + " 份合同，剩余待分配 " + left.subtract(adding)
        );
        return Map.of("id", batchId, "unallocated", left.subtract(adding));
    }

    /** 动笔之前先把每一份都核一遍：合同存在、没有重复、金额不超过该合同的未收。任何一份不对，整笔都不登记。 */
    private void check(String customerId, List<Allocation> allocations) {
        if (allocations.isEmpty()) return;
        var open = new HashMap<String, BigDecimal>();
        for (var c : contracts.contracts())
            if (customerId.equals(c.get("customer_id"))) open.put(
                c.get("contract_key").toString(),
                ContractLedger.decimal(c.get("outstanding"))
            );
        var seen = new HashSet<String>();
        for (var a : allocations) {
            if (!seen.add(a.contractKey())) throw new ApiException("同一份合同在分配里出现了两次：" + a.contractKey());
            var left = open.get(a.contractKey());
            if (left == null) throw new ApiException("合同 " + a.contractKey() + " 不属于这位客户，或你无权查看");
            if (a.amount().compareTo(left) > 0) throw new ApiException(
                "合同 " +
                    a.contractKey() +
                    " 只剩 " +
                    left.toPlainString() +
                    " 元未收，不能分 " +
                    a.amount().toPlainString() +
                    " 元"
            );
        }
    }

    /** 逐份合同登记回款并挂到回款单上；合同回款的凭证号为“回款单凭证号-序号”。 */
    private void post(
        String batchId,
        String customerId,
        LocalDate paidOn,
        String reference,
        String note,
        List<Allocation> allocations,
        int offset
    ) {
        int n = offset;
        for (var a : allocations) {
            var r = contracts.receipt(
                new ContractService.ReceiptInput(
                    customerId,
                    a.contractKey(),
                    "RECEIPT",
                    a.amount(),
                    paidOn,
                    reference + "-" + (++n),
                    note,
                    null
                )
            );
            db.jdbc.update("UPDATE contract_receipt SET batch_id=? WHERE id=?", batchId, r.get("id"));
        }
    }

    /** 回款单列表（新的在前），带各合同的分配和待分配余额。商务只看到含自己负责合同的回款单。 */
    public List<Map<String, Object>> list() {
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var r : contracts.visibleOnly(
            db.jdbc.queryForList(
                "SELECT id,batch_id,customer_id,contract_no,kind,amount,reference FROM contract_receipt WHERE batch_id IS NOT NULL ORDER BY reference"
            )
        ))
            items.computeIfAbsent(r.get("batch_id").toString(), k -> new ArrayList<>()).add(r);
        var out = new ArrayList<Map<String, Object>>();
        for (var b : db.jdbc.queryForList(
            """
            SELECT b.*,c.name AS customer_name,
              (SELECT COALESCE(SUM(r.amount),0) FROM contract_receipt r WHERE r.batch_id=b.id AND r.kind='RECEIPT') AS allocated
            FROM receipt_batch b JOIN partner c ON c.id=b.customer_id
            ORDER BY b.paid_on DESC,b.created_at DESC
            """
        )) {
            var mine = items.getOrDefault(b.get("id").toString(), List.of());
            if (ownership.restricted() && mine.isEmpty()) continue;
            var row = new LinkedHashMap<>(b);
            row.remove("source_text");
            row.put(
                "unallocated",
                ContractLedger.decimal(b.get("amount")).subtract(ContractLedger.decimal(b.get("allocated")))
            );
            row.put("items", mine);
            out.add(row);
        }
        return out;
    }

    /** 分配建议：这位客户还有未收的合同里，这笔钱最可能对应哪几份。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public Map<String, Object> suggest(String customerId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new ApiException("请先填写到账金额");
        var open = new ArrayList<ReceiptAllocator.Open>();
        for (var c : contracts.contracts()) {
            if (!customerId.equals(c.get("customer_id"))) continue;
            var outstanding = ContractLedger.decimal(c.get("outstanding"));
            if (outstanding.signum() <= 0) continue;
            var signed = c.get("signed_on") != null ? c.get("signed_on") : c.get("order_date");
            open.add(
                new ReceiptAllocator.Open(
                    c.get("contract_key").toString(),
                    outstanding,
                    c.get("due_date") == null ? null : ContractLedger.date(c.get("due_date")),
                    signed == null ? null : ContractLedger.date(signed)
                )
            );
        }
        // 未作废的蓝字发票，扣掉已被红字冲销的部分
        var invoices = new ArrayList<ReceiptAllocator.Invoice>();
        for (var i : db.jdbc.queryForList(
            """
            SELECT i.contract_no,i.issued_on,
              i.amount-COALESCE((SELECT SUM(r.amount) FROM contract_invoice r WHERE r.reverses_id=i.id AND r.deleted_at IS NULL),0) AS net
            FROM contract_invoice i WHERE i.customer_id=? AND i.kind='INVOICE' AND i.deleted_at IS NULL
            """,
            customerId
        )) {
            var net = ContractLedger.decimal(i.get("net"));
            if (net.signum() > 0) invoices.add(
                new ReceiptAllocator.Invoice(
                    i.get("contract_no").toString(),
                    net,
                    ContractLedger.date(i.get("issued_on"))
                )
            );
        }
        var s = ReceiptAllocator.suggest(amount, open, invoices);
        var out = new LinkedHashMap<String, Object>();
        out.put("rule", s.rule());
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

    private boolean exists(String reference) {
        return (
            db.jdbc.queryForObject("SELECT COUNT(*) FROM receipt_batch WHERE reference=?", Integer.class, reference) >
                0 ||
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_receipt WHERE reference=?",
                Integer.class,
                reference
            ) >
            0
        );
    }

    /** 没有银行流水号时自动编号：HK + 到账日期 + 当日序号。 */
    private String nextReference(LocalDate paidOn) {
        String prefix = "HK" + paidOn.format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int n = 1; n < 1000; n++) {
            String ref = prefix + String.format("%02d", n);
            if (!exists(ref)) return ref;
        }
        throw new ApiException("当天的回款编号已用完，请手工填写凭证号");
    }

    private static BigDecimal total(List<Allocation> allocations) {
        return allocations.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
