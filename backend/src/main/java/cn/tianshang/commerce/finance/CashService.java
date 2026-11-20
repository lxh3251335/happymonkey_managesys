package cn.tianshang.commerce.finance;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.ContractService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回款流水：统一查看全部收付款。
 *
 * <p>回款只按合同登记（{@link ContractService#receipt}），系统按门店剩余应收比例分摊到门店订单。
 * 早期按单个门店登记、未关联合同回款的流水作为“历史门店流水”保留展示，只允许对其做关联退款冲正。
 */
@Service
public class CashService {

    private final Database db;
    private final Ownership ownership;
    private final ContractService contracts;

    public CashService(Database db, Ownership ownership, ContractService contracts) {
        this.db = db;
        this.ownership = ownership;
        this.contracts = contracts;
    }

    /**
     * 统一回款流水：合同收付款（含分摊到的门店明细）+ 未关联合同的历史门店流水，按日期倒序。
     * 商务只看到自己负责合同 / 门店的流水。
     */
    public List<Map<String, Object>> ledger() {
        var out = new ArrayList<Map<String, Object>>();
        var allocations = new HashMap<String, List<Map<String, Object>>>();
        for (var e : db.jdbc.queryForList(
            """
            SELECT e.contract_receipt_id,e.amount,e.reference,p.name AS project_name,p.code AS project_code
            FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id JOIN project p ON p.id=q.project_id
            WHERE e.contract_receipt_id IS NOT NULL ORDER BY e.reference
            """
        ))
            allocations.computeIfAbsent(e.get("contract_receipt_id").toString(), k -> new ArrayList<>()).add(e);
        for (var r : contracts.receipts()) {
            var row = base(r, "CONTRACT");
            row.put("contract_key", r.get("contract_no"));
            row.put("allocations", allocations.getOrDefault(r.get("id").toString(), List.of()));
            out.add(row);
        }
        String sql = """
            SELECT e.*,p.name AS project_name,p.code AS project_code,p.contract_no AS project_contract_no,
              p.customer_id,c.name AS customer_name,
              (SELECT COALESCE(SUM(x.amount),0) FROM cash_entry x WHERE x.reverses_id=e.id) AS refunded
            FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id JOIN project p ON p.id=q.project_id
            JOIN partner c ON c.id=p.customer_id
            WHERE e.contract_receipt_id IS NULL
            """;
        var legacy = ownership.restricted()
            ? db.jdbc.queryForList(sql + " AND p.owner_id=?", Objects.toString(ownership.userId(), "-"))
            : db.jdbc.queryForList(sql);
        for (var e : legacy) {
            var row = base(e, "STORE");
            String no = e.get("project_contract_no") == null ? null : e.get("project_contract_no").toString();
            row.put("contract_key", no != null && !no.isBlank() ? no : "#" + e.get("project_code"));
            row.put("project_name", e.get("project_name"));
            row.put("allocations", List.of());
            out.add(row);
        }
        out.sort(
            Comparator.comparing((Map<String, Object> r) -> r.get("paid_on").toString())
                .thenComparing(r -> Objects.toString(r.get("created_at"), ""))
                .reversed()
        );
        return out;
    }

    private static LinkedHashMap<String, Object> base(Map<String, Object> r, String source) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", r.get("id"));
        row.put("source", source);
        row.put("kind", r.get("kind"));
        row.put("amount", r.get("amount"));
        row.put("paid_on", r.get("paid_on"));
        row.put("reference", r.get("reference"));
        row.put("note", r.get("note"));
        row.put("reverses_id", r.get("reverses_id"));
        row.put("refunded", r.get("refunded"));
        row.put("customer_id", r.get("customer_id"));
        row.put("customer_name", r.get("customer_name"));
        row.put("created_at", r.get("created_at"));
        return row;
    }

    public record RefundInput(
        @NotBlank String reversesId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent LocalDate paidOn,
        @NotBlank @Size(max = 150) String reference,
        @Size(max = 1000) String note
    ) {}

    /**
     * 历史门店流水的关联退款冲正。新的回款与退款一律按合同登记（合同台账 → 合同详情）。
     */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, String> refundLegacy(@Valid RefundInput c) {
        var original = db.one("SELECT * FROM cash_entry WHERE id=? AND kind='RECEIPT' FOR UPDATE", c.reversesId());
        if (original.get("contract_receipt_id") != null) throw new ApiException(
            "这笔是合同回款的分摊流水，请在合同详情中登记合同退款"
        );
        String reference = c.reference().trim();
        if (
            db.jdbc.queryForObject("SELECT COUNT(*) FROM cash_entry WHERE reference=?", Integer.class, reference) > 0
        ) throw new ApiException("该凭证号已登记");
        var refunded = db.jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM cash_entry WHERE reverses_id=?",
            BigDecimal.class,
            c.reversesId()
        );
        if (
            c.amount().add(refunded).compareTo(new BigDecimal(original.get("amount").toString())) > 0
        ) throw new ApiException("退款超过原流水可退金额");
        if (
            c.paidOn().isBefore(LocalDate.parse(original.get("paid_on").toString().substring(0, 10)))
        ) throw new ApiException("退款日期不能早于原回款日期");
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO cash_entry(id,quotation_id,kind,amount,paid_on,reference,note,reverses_id) VALUES(?,?,'REFUND',?,?,?,?,?)",
            id,
            original.get("quotation_id"),
            c.amount(),
            c.paidOn(),
            reference,
            c.note(),
            c.reversesId()
        );
        db.audit("CASH_REFUND", id, "冲正历史门店流水 " + original.get("reference") + "，金额 " + c.amount());
        return Map.of("id", id);
    }
}
