package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同执行：开票（蓝字 / 红字冲销）与实际成本登记。到款沿用合同回款（ContractService.receipt），发货在门店项目上登记。
 *
 * <p>开票合计不能超过合同金额；红字发票须关联原蓝字发票且不超过其未冲销金额。登记错误的记录可删除（软删除，保留审计）。
 */
@Service
public class ContractExecutionService {

    public static final Map<String, String> COST_CATEGORIES = new LinkedHashMap<>();

    static {
        COST_CATEGORIES.put("MATERIAL", "材料采购");
        COST_CATEGORIES.put("LABOR", "团队人工");
        COST_CATEGORIES.put("INSTALL", "安装调试");
        COST_CATEGORIES.put("FREIGHT", "运输物流");
        COST_CATEGORIES.put("FINANCE", "财务成本");
        COST_CATEGORIES.put("OTHER", "其他");
    }

    private final Database db;
    private final ContractService contracts;
    private final ContractRecordService records;
    private final Ownership ownership;

    public ContractExecutionService(
        Database db,
        ContractService contracts,
        ContractRecordService records,
        Ownership ownership
    ) {
        this.db = db;
        this.contracts = contracts;
        this.records = records;
        this.ownership = ownership;
    }

    // ===================== 开票 =====================

    public List<Map<String, Object>> invoices(String customerId, String contractKey) {
        String sql = """
            SELECT i.*,c.name AS customer_name,m.name AS company_name,o.invoice_no AS reverses_no,
              (SELECT COALESCE(SUM(r.amount),0) FROM contract_invoice r WHERE r.reverses_id=i.id AND r.deleted_at IS NULL) AS reversed
            FROM contract_invoice i JOIN partner c ON c.id=i.customer_id LEFT JOIN company m ON m.id=i.company_id
            LEFT JOIN contract_invoice o ON o.id=i.reverses_id
            WHERE i.deleted_at IS NULL
            """;
        if (customerId != null && contractKey != null) {
            ownership.checkContract(customerId, contractKey);
            return db.jdbc.queryForList(
                sql + " AND i.customer_id=? AND i.contract_no=? ORDER BY i.issued_on DESC,i.created_at DESC",
                customerId,
                contractKey
            );
        }
        return contracts.visibleOnly(db.jdbc.queryForList(sql + " ORDER BY i.issued_on DESC,i.created_at DESC"));
    }

    public record InvoiceInput(
        @NotBlank String customerId,
        @NotBlank String contractKey,
        @NotNull @Pattern(regexp = "INVOICE|RED") String kind,
        @NotBlank @Size(max = 60) @Pattern(
            regexp = "[0-9A-Za-z\\-]+",
            message = "发票号码只能包含数字、字母和短横线"
        ) String invoiceNo,
        @Pattern(regexp = "^$|SPECIAL|NORMAL") String invoiceType,
        @NotNull @PastOrPresent LocalDate issuedOn,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
        String reversesId,
        @Size(max = 1000) String note
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, Object> invoice(@Valid InvoiceInput in) {
        ownership.checkContract(in.customerId(), in.contractKey());
        // 先锁住合同记录再计算已开票金额，避免并发开票超过合同金额
        db.one("SELECT id FROM contract WHERE id=? FOR UPDATE", records.ensure(in.customerId(), in.contractKey()));
        var contract = contracts.contract(in.customerId(), in.contractKey());
        String no = in.invoiceNo().trim();
        if (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_invoice WHERE invoice_no=? AND deleted_at IS NULL",
                Integer.class,
                no
            ) >
            0
        ) throw new ApiException("发票号码 " + no + " 已登记");
        String reverses = null;
        if (in.kind().equals("INVOICE")) {
            if (in.reversesId() != null && !in.reversesId().isBlank()) throw new ApiException("蓝字发票不能关联原发票");
            BigDecimal amount = (BigDecimal) contract.get("amount"),
                invoiced = (BigDecimal) contract.get("invoiced");
            if (amount.signum() <= 0) throw new ApiException("合同金额为零，请先确认门店订单或填写签约金额");
            BigDecimal left = amount.subtract(invoiced);
            if (in.amount().compareTo(left) > 0) throw new ApiException(
                "开票金额超过合同未开票金额 " + left.toPlainString() + " 元"
            );
        } else {
            if (in.reversesId() == null || in.reversesId().isBlank()) throw new ApiException(
                "红字发票须关联原蓝字发票"
            );
            var original = db.one(
                "SELECT * FROM contract_invoice WHERE id=? AND customer_id=? AND contract_no=? AND kind='INVOICE' AND deleted_at IS NULL",
                in.reversesId(),
                in.customerId(),
                in.contractKey()
            );
            BigDecimal reversed = db.jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM contract_invoice WHERE reverses_id=? AND deleted_at IS NULL",
                BigDecimal.class,
                in.reversesId()
            );
            BigDecimal left = new BigDecimal(original.get("amount").toString()).subtract(reversed);
            if (in.amount().compareTo(left) > 0) throw new ApiException(
                "红字金额超过原发票未冲销金额 " + left.toPlainString() + " 元"
            );
            if (in.issuedOn().isBefore(ContractLedger.date(original.get("issued_on")))) throw new ApiException(
                "红字发票日期不能早于原发票日期"
            );
            reverses = in.reversesId();
        }
        String type = Contracts.blankToNull(in.invoiceType());
        if (type == null) {
            var t = db.jdbc.queryForList("SELECT invoice_type FROM partner WHERE id=?", String.class, in.customerId());
            type = t.isEmpty() ? null : t.get(0);
        }
        String id = db.id();
        db.jdbc.update(
            """
            INSERT INTO contract_invoice(id,customer_id,contract_no,company_id,kind,invoice_no,invoice_type,issued_on,amount,tax_rate,reverses_id,note,created_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            in.customerId(),
            in.contractKey(),
            contract.get("company_id"),
            in.kind(),
            no,
            type,
            in.issuedOn(),
            in.amount(),
            in.taxRate(),
            reverses,
            Contracts.blankToNull(in.note()),
            Ownership.username()
        );
        db.audit(
            in.kind().equals("INVOICE") ? "CONTRACT_INVOICE" : "CONTRACT_INVOICE_RED",
            id,
            "合同 " + in.contractKey() + "，发票 " + no + "，金额 " + in.amount().toPlainString()
        );
        return Map.of("id", id);
    }

    public record DeleteInput(@NotBlank @Size(max = 300) String reason) {}

    /** 删除登记错误的发票（不是红冲）：有红字冲销的蓝字发票不能删除。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public void deleteInvoice(String id, @Valid DeleteInput d) {
        var row = db.one("SELECT * FROM contract_invoice WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        if (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_invoice WHERE reverses_id=? AND deleted_at IS NULL",
                Integer.class,
                id
            ) >
            0
        ) throw new ApiException("该发票已有红字冲销记录，不能删除");
        db.jdbc.update(
            "UPDATE contract_invoice SET deleted_at=CURRENT_TIMESTAMP,deleted_by=?,delete_reason=? WHERE id=?",
            Ownership.username(),
            d.reason().trim(),
            id
        );
        db.audit("CONTRACT_INVOICE_DELETED", id, "发票 " + row.get("invoice_no") + " 删除：" + d.reason().trim());
    }

    // ===================== 实际成本 =====================

    /** 实际成本属于成本数据：仅系统管理员、管理层、财务可见与登记。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public List<Map<String, Object>> costs(String customerId, String contractKey) {
        ownership.checkContract(customerId, contractKey);
        return db.jdbc.queryForList(
            """
            SELECT k.*,s.name AS supplier_name FROM contract_cost k LEFT JOIN partner s ON s.id=k.supplier_id
            WHERE k.customer_id=? AND k.contract_no=? AND k.deleted_at IS NULL ORDER BY k.incurred_on DESC,k.created_at DESC
            """,
            customerId,
            contractKey
        );
    }

    public record CostInput(
        @NotBlank String customerId,
        @NotBlank String contractKey,
        @NotNull @Pattern(regexp = "MATERIAL|LABOR|INSTALL|FREIGHT|FINANCE|OTHER") String category,
        @Size(max = 36) String supplierId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent LocalDate incurredOn,
        @Size(max = 150) String reference,
        @Size(max = 1000) String note
    ) {}

    /** 登记实际成本：系统管理员、管理层、财务。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @Transactional
    public Map<String, Object> cost(@Valid CostInput in) {
        contracts.contract(in.customerId(), in.contractKey());
        String supplier = Contracts.blankToNull(in.supplierId());
        if (supplier != null) db.one("SELECT id FROM partner WHERE id=? AND kind='SUPPLIER'", supplier);
        String id = db.id();
        db.jdbc.update(
            """
            INSERT INTO contract_cost(id,customer_id,contract_no,category,supplier_id,amount,incurred_on,reference,note,created_by)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            in.customerId(),
            in.contractKey(),
            in.category(),
            supplier,
            in.amount(),
            in.incurredOn(),
            Contracts.blankToNull(in.reference()),
            Contracts.blankToNull(in.note()),
            Ownership.username()
        );
        db.audit(
            "CONTRACT_COST",
            id,
            "合同 " +
                in.contractKey() +
                "，" +
                COST_CATEGORIES.get(in.category()) +
                " " +
                in.amount().toPlainString() +
                " 元"
        );
        return Map.of("id", id);
    }

    /** 删除错误登记的成本：系统管理员、管理层、财务。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @Transactional
    public void deleteCost(String id, @Valid DeleteInput d) {
        var row = db.one("SELECT * FROM contract_cost WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        ownership.checkContract(row.get("customer_id").toString(), row.get("contract_no").toString());
        if (
            !Ownership.isAdmin() && !Ownership.hasRole("FINANCE") && !Ownership.username().equals(row.get("created_by"))
        ) throw new AccessDeniedException("只能删除自己登记的成本");
        db.jdbc.update(
            "UPDATE contract_cost SET deleted_at=CURRENT_TIMESTAMP,deleted_by=?,delete_reason=? WHERE id=?",
            Ownership.username(),
            d.reason().trim(),
            id
        );
        db.audit("CONTRACT_COST_DELETED", id, row.get("amount") + " 元删除：" + d.reason().trim());
    }
}
