package cn.tianshang.commerce.bot;

import static cn.tianshang.commerce.bot.BotText.*;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.contract.ContractService;
import cn.tianshang.commerce.contract.Contracts;
import cn.tianshang.commerce.project.DeliveryParser;
import cn.tianshang.commerce.project.ProjectService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 机器人的查询指令。数据都来自网页用的同一批服务，所以业务员只查得到自己负责的，成本和毛利只给能看成本的角色。
 */
@Component
class BotQueries {

    private static final int MAX_LINES = 15;

    private final ContractService contracts;
    private final ProjectService projects;
    private final Database db;

    BotQueries(ContractService contracts, ProjectService projects, Database db) {
        this.contracts = contracts;
        this.projects = projects;
        this.db = db;
    }

    /** 已成交的门店（有合同或有已确认的报价），发货只和这些门店有关。 */
    List<Map<String, Object>> dealtStores() {
        return projects
            .list()
            .stream()
            .filter(p -> !"NONE".equals(str(p.get("contract_state"))) || num(p.get("confirmed_quotes")).signum() > 0)
            .toList();
    }

    private static boolean contains(Object value, String needle) {
        String v = str(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return !v.isEmpty() && v.contains(needle);
    }

    private static String needle(String arg) {
        return arg.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    // ===================== 应收 =====================

    String receivable(String arg) {
        var open = contracts
            .contracts()
            .stream()
            .filter(c -> num(c.get("outstanding")).signum() > 0)
            .toList();
        if (arg.isBlank()) return receivableByCustomer(open);
        String n = needle(arg);
        var mine = open
            .stream()
            .filter(c -> contains(c.get("customer_name"), n))
            .toList();
        if (mine.isEmpty()) {
            boolean known = contracts
                .contracts()
                .stream()
                .anyMatch(c -> contains(c.get("customer_name"), n));
            return known
                ? "名称里带“" + arg + "”的客户目前没有未回款。"
                : "没有找到名称里带“" + arg + "”的客户（也可能是你没有负责他们的合同）。";
        }
        var names = mine
            .stream()
            .map(c -> str(c.get("customer_name")))
            .distinct()
            .toList();
        if (names.size() > 1) return (
            "有 " + names.size() + " 个客户的名称里带“" + arg + "”：\n" + receivableByCustomer(mine)
        );
        var today = LocalDate.now();
        var sorted = new ArrayList<>(mine);
        sorted.sort(
            Comparator.comparing(
                (Map<String, Object> c) -> date(c.get("due_date")),
                Comparator.nullsLast(Comparator.naturalOrder())
            )
        );
        var sb = new StringBuilder("**" + names.get(0) + "** 未回款 " + money(total(mine, "outstanding")) + " 元");
        BigDecimal overdue = total(mine, "overdue");
        if (overdue.signum() > 0) sb.append("，其中逾期 ").append(money(overdue)).append(" 元");
        sb.append("（").append(mine.size()).append(" 份合同）");
        for (var c : sorted.subList(0, Math.min(MAX_LINES, sorted.size())))
            sb
                .append("\n· ")
                .append(c.get("contract_key"))
                .append("　未回款 ")
                .append(money(c.get("outstanding")))
                .append("　截止 ")
                .append(due(date(c.get("due_date")), today));
        return sb.append(more(sorted.size(), MAX_LINES)).toString();
    }

    private String receivableByCustomer(List<Map<String, Object>> open) {
        if (open.isEmpty()) return "目前没有未回款的合同。";
        var byCustomer = new LinkedHashMap<String, BigDecimal[]>();
        for (var c : open) {
            var sums = byCustomer.computeIfAbsent(str(c.get("customer_name")), k ->
                new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO }
            );
            sums[0] = sums[0].add(num(c.get("outstanding")));
            sums[1] = sums[1].add(num(c.get("overdue")));
        }
        var rows = new ArrayList<>(byCustomer.entrySet());
        rows.sort((a, b) -> b.getValue()[0].compareTo(a.getValue()[0]));
        var sb = new StringBuilder(
            "**未回款合计 " +
                money(total(open, "outstanding")) +
                " 元**，其中逾期 " +
                money(total(open, "overdue")) +
                " 元"
        );
        for (var r : rows.subList(0, Math.min(MAX_LINES, rows.size()))) {
            sb.append("\n· ").append(r.getKey()).append("　").append(money(r.getValue()[0]));
            if (r.getValue()[1].signum() > 0) sb.append("（逾期 ").append(money(r.getValue()[1])).append("）");
        }
        return sb.append(more(rows.size(), MAX_LINES)).append("\n发“应收 客户名”看这个客户的每份合同。").toString();
    }

    String overdue() {
        var rows = new ArrayList<>(
            contracts
                .contracts()
                .stream()
                .filter(c -> num(c.get("overdue")).signum() > 0)
                .toList()
        );
        if (rows.isEmpty()) return "目前没有逾期的应收。";
        rows.sort((a, b) -> num(b.get("overdue")).compareTo(num(a.get("overdue"))));
        var today = LocalDate.now();
        var sb = new StringBuilder("**逾期 " + money(total(rows, "overdue")) + " 元**，" + rows.size() + " 份合同");
        for (var c : rows.subList(0, Math.min(MAX_LINES, rows.size())))
            sb
                .append("\n· ")
                .append(c.get("customer_name"))
                .append(" ")
                .append(c.get("contract_key"))
                .append("　")
                .append(money(c.get("overdue")))
                .append("　截止 ")
                .append(due(date(c.get("due_date")), today));
        return sb.append(more(rows.size(), MAX_LINES)).toString();
    }

    // ===================== 合同 =====================

    String contract(String arg) {
        if (arg.isBlank()) return "请带上合同号或客户名，例如：合同 TJ2026-BJXYTL-20";
        String n = needle(arg);
        var all = contracts.contracts();
        var hits = all
            .stream()
            .filter(c -> str(c.get("contract_key")).equalsIgnoreCase(arg.trim()))
            .toList();
        if (hits.isEmpty()) hits = all
            .stream()
            .filter(c -> contains(c.get("contract_key"), n) || contains(c.get("title"), n))
            .toList();
        if (hits.isEmpty()) hits = all
            .stream()
            .filter(c -> contains(c.get("customer_name"), n))
            .toList();
        if (hits.isEmpty()) return "没有找到和“" + arg + "”有关的合同（也可能是你没有负责它）。";
        if (hits.size() > 1) {
            var sb = new StringBuilder("找到 " + hits.size() + " 份合同，发完整的合同号看详情：");
            for (var c : hits.subList(0, Math.min(MAX_LINES, hits.size())))
                sb
                    .append("\n· ")
                    .append(c.get("contract_key"))
                    .append("　")
                    .append(c.get("customer_name"))
                    .append("　")
                    .append(money(c.get("amount")))
                    .append("　未回款 ")
                    .append(money(c.get("outstanding")));
            return sb.append(more(hits.size(), MAX_LINES)).toString();
        }
        var c = hits.get(0);
        var sb = new StringBuilder("**" + c.get("contract_key") + "**　" + c.get("customer_name"));
        sb.append("\n状态：").append(ContractService.STATUS.getOrDefault(str(c.get("status")), str(c.get("status"))));
        if (!str(c.get("owner_name")).isEmpty()) sb.append("　负责人：").append(c.get("owner_name"));
        sb.append("\n合同金额：").append(money(c.get("amount")));
        sb
            .append("\n已开票：")
            .append(money(c.get("invoiced")))
            .append("　未开票：")
            .append(money(c.get("uninvoiced")));
        sb
            .append("\n已回款：")
            .append(money(c.get("received")))
            .append("　未回款：")
            .append(money(c.get("outstanding")));
        if (num(c.get("outstanding")).signum() > 0) {
            sb.append("\n应收截止：").append(due(date(c.get("due_date")), LocalDate.now()));
            if (num(c.get("overdue")).signum() > 0) sb.append("，逾期 ").append(money(c.get("overdue")));
        }
        sb.append("\n发货：已发 ").append(c.get("shipped")).append(" / ").append(c.get("projects")).append(" 店");
        // 成本和毛利只给能看成本的角色
        if (Roles.canSeeCost() && c.get("profit") != null && c.get("gross_margin") != null) sb
            .append("\n毛利：")
            .append(money(c.get("profit")))
            .append("（毛利率 ")
            .append(num(c.get("gross_margin")).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP))
            .append("%）");
        return sb.toString();
    }

    // ===================== 发货 =====================

    String shipping(String arg) {
        if (arg.isBlank()) return "请带上门店名称，例如：发货 盛泰站";
        var hits = DeliveryParser.matchStores(arg, projects.list());
        if (hits.isEmpty()) return "没有找到名称里带“" + arg + "”的门店（也可能是你没有负责它）。";
        if (hits.size() > 1) {
            var sb = new StringBuilder("有 " + hits.size() + " 个门店名称相近，请写得更完整一些：");
            for (var p : hits.subList(0, Math.min(MAX_LINES, hits.size()))) sb.append("\n· ").append(p.get("name"));
            return sb.append(more(hits.size(), MAX_LINES)).toString();
        }
        var p = hits.get(0);
        String status = Contracts.shipping((String) p.get("shipping_status"));
        var sb = new StringBuilder("**" + p.get("name") + "**　" + p.get("customer_name"));
        sb.append("\n发货状态：").append(status == null ? "未发货" : status);
        if (Contracts.SHIPPED.equals(status)) {
            if (p.get("shipped_on") != null) sb.append("（").append(date(p.get("shipped_on"))).append("）");
            sb
                .append("\n运单：")
                .append(str(p.get("courier")).isEmpty() ? "" : p.get("courier") + " ")
                .append(str(p.get("tracking_no")).isEmpty() ? "没有登记运单号" : p.get("tracking_no"));
        } else sb.append("\n预计发货：").append(due(date(p.get("expected_ship_on")), LocalDate.now()));
        boolean hasConsignee =
            !str(p.get("consignee")).isEmpty() ||
            !str(p.get("consignee_phone")).isEmpty() ||
            !str(p.get("ship_address")).isEmpty();
        if (hasConsignee) sb
            .append("\n收货：")
            .append(str(p.get("consignee")))
            .append(" ")
            .append(str(p.get("consignee_phone")))
            .append("\n地址：")
            .append(str(p.get("ship_address")).isEmpty() ? "没有登记" : p.get("ship_address"));
        else sb.append("\n收货信息还没有登记");
        return sb.toString();
    }

    String toShip() {
        var rows = new ArrayList<>(
            dealtStores()
                .stream()
                .filter(p -> !Contracts.SHIPPED.equals(Contracts.shipping((String) p.get("shipping_status"))))
                .toList()
        );
        if (rows.isEmpty()) return "目前没有待发货的门店。";
        rows.sort(
            Comparator.comparing(
                (Map<String, Object> p) -> date(p.get("expected_ship_on")),
                Comparator.nullsLast(Comparator.naturalOrder())
            )
        );
        var today = LocalDate.now();
        long late = rows
            .stream()
            .filter(p -> date(p.get("expected_ship_on")) != null && date(p.get("expected_ship_on")).isBefore(today))
            .count();
        var sb = new StringBuilder("**待发货 " + rows.size() + " 个门店**");
        if (late > 0) sb.append("，其中 ").append(late).append(" 个已过预计发货日期");
        for (var p : rows.subList(0, Math.min(MAX_LINES, rows.size())))
            sb
                .append("\n· ")
                .append(p.get("name"))
                .append("　预计 ")
                .append(due(date(p.get("expected_ship_on")), today));
        return sb.append(more(rows.size(), MAX_LINES)).toString();
    }

    // ===================== 本月 =====================

    String month() {
        var today = LocalDate.now();
        var from = today.withDayOfMonth(1);
        var all = contracts.contracts();
        var signed = all
            .stream()
            .filter(c -> {
                var d = date(c.get("signed_on") != null ? c.get("signed_on") : c.get("order_date"));
                return d != null && !d.isBefore(from) && !d.isAfter(today);
            })
            .toList();
        BigDecimal received = BigDecimal.ZERO;
        for (var r : contracts.receipts()) {
            var d = date(r.get("paid_on"));
            if (d == null || d.isBefore(from) || d.isAfter(today)) continue;
            received = "RECEIPT".equals(r.get("kind"))
                ? received.add(num(r.get("amount")))
                : received.subtract(num(r.get("amount")));
        }
        BigDecimal invoiced = BigDecimal.ZERO;
        for (var i : contracts.visibleOnly(
            db.jdbc.queryForList(
                "SELECT customer_id,contract_no,kind,amount FROM contract_invoice WHERE deleted_at IS NULL AND issued_on>=? AND issued_on<=?",
                from,
                today
            )
        ))
            invoiced = "INVOICE".equals(i.get("kind"))
                ? invoiced.add(num(i.get("amount")))
                : invoiced.subtract(num(i.get("amount")));
        return (
            "**" +
            today.getYear() +
            " 年 " +
            today.getMonthValue() +
            " 月（截至今天）**" +
            "\n新签合同：" +
            signed.size() +
            " 份，" +
            money(total(signed, "amount")) +
            " 元" +
            "\n回款：" +
            money(received) +
            " 元" +
            "\n开票：" +
            money(invoiced) +
            " 元" +
            "\n目前未回款：" +
            money(total(all, "outstanding")) +
            " 元，其中逾期 " +
            money(total(all, "overdue")) +
            " 元"
        );
    }

    private static BigDecimal total(List<Map<String, Object>> rows, String key) {
        return rows
            .stream()
            .map(r -> num(r.get(key)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
