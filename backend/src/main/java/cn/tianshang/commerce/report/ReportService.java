package cn.tianshang.commerce.report;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.contract.ContractLedger;
import cn.tianshang.commerce.contract.ContractLedger.Fact;
import cn.tianshang.commerce.contract.Contracts;
import cn.tianshang.commerce.quote.QuoteService;
import java.math.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * 经营总览。合同额按项目下单日期归月，到账额按收款日期归月（收款减退款）。
 * 毛利只统计报价和成本都完整的明细行，并给出覆盖率。
 *
 * <p>可按品牌（业务）筛选：筛选后整页指标、趋势、客户、产品线均只含该品牌；品牌 × 产品线对比始终基于全部业务。
 */
@Service
public class ReportService {

    private static final List<String> AGING = List.of(
        "未到期",
        "逾期 1-30 天",
        "逾期 31-60 天",
        "逾期 61-90 天",
        "逾期 90 天以上"
    );

    private final Database db;
    private final QuoteService quotes;
    private final ContractLedger ledger;

    public ReportService(Database db, QuoteService quotes, ContractLedger ledger) {
        this.db = db;
        this.quotes = quotes;
        this.ledger = ledger;
    }

    /** 一个分组的累计值。 */
    static final class Acc {

        final String name;
        BigDecimal contract = BigDecimal.ZERO,
            received = BigDecimal.ZERO,
            outstanding = BigDecimal.ZERO,
            profit = BigDecimal.ZERO,
            base = BigDecimal.ZERO,
            quantity = BigDecimal.ZERO;
        final Set<String> projects = new HashSet<>(),
            contracts = new HashSet<>();

        Acc(String name) {
            this.name = name;
        }

        Map<String, Object> row(BigDecimal grand) {
            var x = new LinkedHashMap<String, Object>();
            x.put("name", name);
            x.put("contract_amount", contract);
            x.put("received", received);
            x.put("outstanding", outstanding);
            x.put("collection_rate", ratio(received, contract));
            x.put("share", ratio(contract, grand));
            x.put("profit", profit);
            x.put("gross_margin", ratio(profit, base));
            x.put("margin_coverage", ratio(base, contract));
            x.put("projects", projects.size());
            x.put("contracts", contracts.size());
            x.put(
                "avg_per_project",
                projects.isEmpty()
                    ? null
                    : contract.divide(BigDecimal.valueOf(projects.size()), 2, RoundingMode.HALF_UP)
            );
            x.put("quantity", quantity);
            return x;
        }
    }

    public Map<String, Object> dashboard() {
        return dashboard(null);
    }

    public Map<String, Object> dashboard(String brandFilter) {
        String brand = Contracts.blankToNull(brandFilter);
        var all = ledger.contracted();
        var facts =
            brand == null
                ? all
                : all
                      .stream()
                      .filter(f -> f.brand().equals(brand))
                      .toList();
        var today = LocalDate.now();
        String cashSql = """
            SELECT e.quotation_id,e.kind,e.amount,e.paid_on,p.brand,p.city,c.name AS customer_name
            FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id JOIN project p ON p.id=q.project_id
            JOIN partner c ON c.id=p.customer_id
            """;
        var cash =
            brand == null ? db.jdbc.queryForList(cashSql) : db.jdbc.queryForList(cashSql + " WHERE p.brand=?", brand);
        var monthLines = new HashMap<String, Map<String, Acc>>();

        var months = new TreeMap<String, Acc>();
        var customers = new HashMap<String, Acc>();
        var brands = new HashMap<String, Acc>();
        var cities = new HashMap<String, Acc>();
        var lines = new HashMap<String, Acc>();
        var total = new Acc("合计");
        BigDecimal backlog = BigDecimal.ZERO;
        var backlogStatus = new LinkedHashMap<String, Acc>();
        var aging = new LinkedHashMap<String, Acc>();
        AGING.forEach(b -> aging.put(b, new Acc(b)));
        int incompleteCost = 0;
        BigDecimal pending = BigDecimal.ZERO;

        for (var f : facts) {
            String contract = f.customerId() + "::" + f.contractKey();
            for (var acc : List.of(
                total,
                at(months, f.month() == null ? "未知" : f.month()),
                at(customers, f.customerName()),
                at(brands, f.brand()),
                at(cities, f.city() == null ? "未填城市" : f.city())
            )) {
                acc.contract = acc.contract.add(f.amount());
                acc.outstanding = acc.outstanding.add(f.outstanding());
                acc.projects.add(f.projectId());
                acc.contracts.add(contract);
                addMargin(acc, f.items());
            }
            if (!f.confirmed()) pending = pending.add(f.amount());
            if (
                f
                    .items()
                    .stream()
                    .anyMatch(i -> i.amount() != null && i.cost() == null)
            ) incompleteCost++;
            addLines(lines, f, contract);
            addLines(
                monthLines.computeIfAbsent(f.month() == null ? "未知" : f.month(), k -> new HashMap<>()),
                f,
                contract
            );
            if (!f.shipped()) {
                backlog = backlog.add(f.amount());
                var acc = at(backlogStatus, f.shippingStatus() == null ? "未登记发货状态" : f.shippingStatus());
                acc.contract = acc.contract.add(f.amount());
                acc.projects.add(f.projectId());
                acc.contracts.add(contract);
            }
            if (f.confirmed() && f.outstanding().signum() > 0) {
                long days = f.dueDate() == null ? 0 : ChronoUnit.DAYS.between(f.dueDate(), today);
                String bucket =
                    days <= 0
                        ? AGING.get(0)
                        : days <= 30
                            ? AGING.get(1)
                            : days <= 60
                                ? AGING.get(2)
                                : days <= 90
                                    ? AGING.get(3)
                                    : AGING.get(4);
                var acc = aging.get(bucket);
                acc.contract = acc.contract.add(f.outstanding());
                acc.projects.add(f.projectId());
                acc.contracts.add(contract);
            }
        }
        for (var e : cash) {
            var amount = new BigDecimal(e.get("amount").toString());
            if ("REFUND".equals(e.get("kind"))) amount = amount.negate();
            String month = e.get("paid_on").toString().substring(0, 7);
            for (var acc : List.of(
                total,
                at(months, month),
                at(customers, e.get("customer_name").toString()),
                at(brands, e.get("brand").toString()),
                at(cities, e.get("city") == null ? "未填城市" : e.get("city").toString())
            ))
                acc.received = acc.received.add(amount);
        }
        fillMonthGaps(months);

        var out = new LinkedHashMap<String, Object>();
        var totals = total.row(total.contract);
        totals.put("confirmed_amount", total.contract.subtract(pending));
        totals.put("pending_amount", pending);
        totals.put("receivable", total.outstanding);
        var overdue = aging.values().stream().skip(1).toList();
        totals.put(
            "overdue_amount",
            overdue
                .stream()
                .map(a -> a.contract)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
        totals.put(
            "overdue_projects",
            overdue
                .stream()
                .mapToInt(a -> a.projects.size())
                .sum()
        );
        totals.put("backlog_amount", backlog);
        totals.put(
            "backlog_projects",
            backlogStatus
                .values()
                .stream()
                .mapToInt(a -> a.projects.size())
                .sum()
        );
        totals.put("incomplete_cost_projects", incompleteCost);
        String thisMonth = YearMonth.from(today).toString();
        totals.put("current_month", thisMonth);
        var current = months.get(thisMonth);
        totals.put("current_month_contract", current == null ? BigDecimal.ZERO : current.contract);
        totals.put("current_month_received", current == null ? BigDecimal.ZERO : current.received);
        out.put("totals", totals);

        out.put(
            "monthly",
            months
                .values()
                .stream()
                .map(a -> {
                    var row = a.row(total.contract);
                    var ml = monthLines.getOrDefault(a.name, Map.of());
                    var byLine = new LinkedHashMap<String, Object>();
                    for (String line : Contracts.LINES)
                        if (ml.containsKey(line)) byLine.put(line, ml.get(line).row(a.contract));
                    row.put("lines", byLine);
                    return row;
                })
                .toList()
        );
        var byCustomer = ranked(customers, total.contract);
        out.put("by_customer", byCustomer);
        out.put("by_brand", ranked(brands, total.contract));
        out.put("by_city", ranked(cities, total.contract));
        out.put("by_line", ranked(lines, total.contract));
        out.put("line_summary", lineSummary(lines, total.contract));
        out.put("brand", brand);
        out.put("brand_lines", brandLines(all));
        out.put(
            "aging",
            aging
                .values()
                .stream()
                .map(a -> a.row(total.outstanding))
                .toList()
        );
        out.put("backlog", ranked(backlogStatus, backlog));
        out.put("concentration", concentration(byCustomer));

        var latest = new LinkedHashMap<String, Map<String, Object>>();
        for (var q : quotes.list()) {
            String key = q.get("project_id").toString();
            if (
                !latest.containsKey(key) ||
                ((Number) q.get("version")).intValue() > ((Number) latest.get(key).get("version")).intValue()
            ) latest.put(key, q);
        }
        out.put("projects", db.jdbc.queryForObject("SELECT COUNT(*) FROM project", Integer.class));
        out.put(
            "customers",
            db.jdbc.queryForObject("SELECT COUNT(*) FROM partner WHERE kind='CUSTOMER'", Integer.class)
        );
        out.put("recent", latest.values().stream().limit(6).toList());
        out.put("imports", db.jdbc.queryForList("SELECT * FROM import_batch ORDER BY created_at DESC"));
        return out;
    }

    /** 产品线：合同额与毛利按明细行归集，到账额与应收按明细金额比例分摊。 */
    private static void addLines(Map<String, Acc> lines, Fact f, String contract) {
        for (var i : f.items()) {
            if (i.amount() == null) continue;
            var acc = at(lines, i.line());
            acc.contract = acc.contract.add(i.amount());
            acc.projects.add(f.projectId());
            acc.contracts.add(contract);
            acc.quantity = acc.quantity.add(i.quantity());
            addMargin(acc, List.of(i));
            if (f.amount().signum() > 0) {
                acc.received = acc.received.add(
                    f.received().multiply(i.amount()).divide(f.amount(), 2, RoundingMode.HALF_UP)
                );
                acc.outstanding = acc.outstanding.add(
                    f.outstanding().multiply(i.amount()).divide(f.amount(), 2, RoundingMode.HALF_UP)
                );
            }
        }
    }

    /** 主要产品线固定输出（即使为零），其余产品线有数据才输出；按固定顺序。 */
    private static List<Map<String, Object>> lineSummary(Map<String, Acc> lines, BigDecimal grand) {
        var out = new ArrayList<Map<String, Object>>();
        for (String line : Contracts.LINES) {
            boolean main = line.equals(Contracts.CABINET) || line.equals(Contracts.IOT);
            if (main || lines.containsKey(line)) out.add(lines.getOrDefault(line, new Acc(line)).row(grand));
        }
        return out;
    }

    /** 品牌 × 产品线对比：始终基于全部业务，用于比较各品牌两条产品线的规模与毛利。 */
    private static List<Map<String, Object>> brandLines(List<Fact> facts) {
        var byBrand = new LinkedHashMap<String, Map<String, Acc>>();
        var totals = new HashMap<String, Acc>();
        for (var f : facts) {
            String contract = f.customerId() + "::" + f.contractKey();
            addLines(byBrand.computeIfAbsent(f.brand(), k -> new HashMap<>()), f, contract);
            var t = at(totals, f.brand());
            t.contract = t.contract.add(f.amount());
            addMargin(t, f.items());
        }
        return byBrand
            .entrySet()
            .stream()
            .sorted(
                Comparator.comparing((Map.Entry<String, Map<String, Acc>> e) -> totals.get(e.getKey()).contract)
                    .reversed()
                    .thenComparing(Map.Entry::getKey)
            )
            .map(e -> {
                var t = totals.get(e.getKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("brand", e.getKey());
                row.put("contract_amount", t.contract);
                row.put("gross_margin", ratio(t.profit, t.base));
                row.put("lines", lineSummary(e.getValue(), t.contract));
                return row;
            })
            .toList();
    }

    private static Acc at(Map<String, Acc> map, String key) {
        return map.computeIfAbsent(key, Acc::new);
    }

    private static void addMargin(Acc acc, List<ContractLedger.Item> items) {
        for (var i : items) {
            if (i.amount() == null || i.cost() == null) continue;
            acc.base = acc.base.add(i.amount());
            acc.profit = acc.profit.add(i.amount().subtract(i.cost()));
        }
    }

    /** 补齐首尾月份之间没有业务的月份，使趋势连续。 */
    private static void fillMonthGaps(TreeMap<String, Acc> months) {
        var real = months
            .keySet()
            .stream()
            .filter(k -> k.matches("\\d{4}-\\d{2}"))
            .toList();
        if (real.isEmpty()) return;
        var m = YearMonth.parse(real.get(0));
        var end = YearMonth.parse(real.get(real.size() - 1));
        while (!m.isAfter(end)) {
            months.computeIfAbsent(m.toString(), Acc::new);
            m = m.plusMonths(1);
        }
    }

    private static List<Map<String, Object>> ranked(Map<String, Acc> map, BigDecimal grand) {
        return map
            .values()
            .stream()
            .sorted(
                Comparator.comparing((Acc a) -> a.contract)
                    .reversed()
                    .thenComparing(a -> a.name)
            )
            .map(a -> a.row(grand))
            .toList();
    }

    private static Map<String, Object> concentration(List<Map<String, Object>> customers) {
        Function<Integer, BigDecimal> top = n ->
            customers
                .stream()
                .limit(n)
                .map(c -> (BigDecimal) c.get("share"))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var out = new LinkedHashMap<String, Object>();
        out.put("top_customer", customers.isEmpty() ? null : customers.get(0).get("name"));
        out.put("top1_share", customers.isEmpty() ? null : top.apply(1));
        out.put("top3_share", customers.isEmpty() ? null : top.apply(3));
        out.put("customer_count", customers.size());
        return out;
    }

    static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.divide(whole, 6, RoundingMode.HALF_UP);
    }
}
