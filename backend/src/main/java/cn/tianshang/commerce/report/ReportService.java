package cn.tianshang.commerce.report;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.finance.CashService;
import cn.tianshang.commerce.quote.QuoteService;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final Database db;
    private final QuoteService quotes;
    private final CashService cash;

    public ReportService(Database db, QuoteService quotes, CashService cash) {
        this.db = db;
        this.quotes = quotes;
        this.cash = cash;
    }

    public Map<String, Object> dashboard() {
        var latest = new LinkedHashMap<String, Map<String, Object>>();
        for (var q : quotes.list()) {
            String key = q.get("project_id").toString();
            if (
                !latest.containsKey(key) ||
                ((Number) q.get("version")).intValue() > ((Number) latest.get(key).get("version")).intValue()
            ) latest.put(key, q);
        }
        var rows = new ArrayList<>(latest.values());
        var balances = cash.balances();
        var out = new LinkedHashMap<String, Object>();
        out.put("projects", db.jdbc.queryForObject("SELECT COUNT(*) FROM project", Integer.class));
        out.put(
            "customers",
            db.jdbc.queryForObject("SELECT COUNT(*) FROM partner WHERE kind='CUSTOMER'", Integer.class)
        );
        out.put("quotes", rows.size());
        out.put("amount", sum(rows, "known_amount"));
        var complete = rows
            .stream()
            .filter(q -> Boolean.TRUE.equals(q.get("complete")))
            .toList();
        out.put("incomplete", rows.size() - complete.size());
        out.put("complete_quotes", complete.size());
        out.put("profit", sum(complete, "profit"));
        out.put("cost", sum(complete, "cost"));
        var denominator = sum(complete, "amount");
        out.put(
            "gross_margin",
            denominator.signum() > 0 ? sum(complete, "profit").divide(denominator, 6, RoundingMode.HALF_UP) : null
        );
        out.put("receivable", sum(balances, "outstanding"));
        out.put("received", sum(balances, "received"));
        out.put("accepted", balances.size());
        out.put(
            "overdue",
            balances
                .stream()
                .filter(q -> Boolean.TRUE.equals(q.get("overdue")))
                .count()
        );
        out.put("recent", rows.stream().limit(6).toList());
        out.put("customers_breakdown", breakdown(rows, "customer_name"));
        out.put("brands_breakdown", breakdown(rows, "brand"));
        out.put("imports", db.jdbc.queryForList("SELECT * FROM import_batch ORDER BY created_at DESC"));
        return out;
    }

    private BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows
            .stream()
            .map(q -> q.get(key))
            .filter(Objects::nonNull)
            .map(v -> new BigDecimal(v.toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Map<String, Object>> breakdown(List<Map<String, Object>> rows, String key) {
        var groups = new TreeMap<String, List<Map<String, Object>>>();
        rows.forEach(q -> groups.computeIfAbsent(q.get(key).toString(), k -> new ArrayList<>()).add(q));
        return groups
            .entrySet()
            .stream()
            .map(e -> {
                Map<String, Object> x = new HashMap<>();
                x.put("name", e.getKey());
                x.put("amount", sum(e.getValue(), "known_amount"));
                x.put("count", e.getValue().size());
                return x;
            })
            .sorted((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")))
            .toList();
    }
}
