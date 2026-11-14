package cn.tianshang.commerce.finance;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.quote.QuoteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashService {

    private final Database db;
    private final QuoteService quotes;

    public CashService(Database db, QuoteService quotes) {
        this.db = db;
        this.quotes = quotes;
    }

    public List<Map<String, Object>> entries() {
        return db.jdbc.queryForList(
            """
            SELECT e.*,q.code AS quote_code,p.name AS project_name,c.name AS customer_name
            FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id JOIN project p ON p.id=q.project_id
            JOIN partner c ON c.id=p.customer_id ORDER BY e.paid_on DESC,e.created_at DESC
            """
        );
    }

    public List<Map<String, Object>> balances() {
        var rows = quotes
            .list()
            .stream()
            .filter(q -> q.get("status").equals("ACCEPTED"))
            .toList();
        for (var q : rows) {
            var net = net(q.get("id").toString());
            var amount = (BigDecimal) q.get("amount");
            q.put("received", net);
            q.put("outstanding", amount.subtract(net));
            q.put(
                "overdue",
                amount.compareTo(net) > 0 && LocalDate.parse(q.get("due_date").toString()).isBefore(LocalDate.now())
            );
        }
        return rows;
    }

    public record CashInput(
        @NotBlank String quotationId,
        @NotNull @Pattern(regexp = "RECEIPT|REFUND") String kind,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent LocalDate paidOn,
        @NotBlank @Size(max = 150) String reference,
        @Size(max = 1000) String note,
        String reversesId
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, String> create(@Valid CashInput c) {
        var q = db.one("SELECT * FROM quotation WHERE id=? FOR UPDATE", c.quotationId());
        if (!q.get("status").equals("ACCEPTED")) throw new ApiException("请先确认客户订单及应收，再登记回款");
        var net = net(c.quotationId());
        var total = (BigDecimal) quotes.detail(c.quotationId()).get("amount");
        if (c.kind().equals("RECEIPT")) {
            if (c.reversesId() != null && !c.reversesId().isBlank()) throw new ApiException("收款不能关联退款原流水");
            if (net.add(c.amount()).compareTo(total) > 0) throw new ApiException("回款超过该订单剩余应收金额");
        } else {
            if (c.reversesId() == null) throw new ApiException("退款必须关联原收款流水");
            var original = db.one(
                "SELECT * FROM cash_entry WHERE id=? AND quotation_id=? AND kind='RECEIPT'",
                c.reversesId(),
                c.quotationId()
            );
            var refunded = db.jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM cash_entry WHERE reverses_id=?",
                BigDecimal.class,
                c.reversesId()
            );
            if (
                c.amount().add(refunded).compareTo((BigDecimal) original.get("amount")) > 0 ||
                c.amount().compareTo(net) > 0
            ) throw new ApiException("退款超过原流水可退金额");
            if (c.paidOn().isBefore(LocalDate.parse(original.get("paid_on").toString()))) throw new ApiException(
                "退款日期不能早于原收款日期"
            );
        }
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO cash_entry(id,quotation_id,kind,amount,paid_on,reference,note,reverses_id) VALUES(?,?,?,?,?,?,?,?)",
            id,
            c.quotationId(),
            c.kind(),
            c.amount(),
            c.paidOn(),
            c.reference().trim(),
            c.note(),
            c.kind().equals("REFUND") ? c.reversesId() : null
        );
        db.audit("CASH_" + c.kind(), id, "订单 " + q.get("code") + "，金额 " + c.amount());
        return Map.of("id", id);
    }

    private BigDecimal net(String id) {
        return db.jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN kind='RECEIPT' THEN amount ELSE -amount END),0) FROM cash_entry WHERE quotation_id=?",
            BigDecimal.class,
            id
        );
    }
}
