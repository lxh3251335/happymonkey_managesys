package cn.tianshang.commerce.todo;

import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.contract.ContractLedger;
import cn.tianshang.commerce.contract.ContractRecordService;
import cn.tianshang.commerce.contract.ContractService;
import cn.tianshang.commerce.finance.ReceiptBatchService;
import cn.tianshang.commerce.project.ProjectService;
import cn.tianshang.commerce.quote.QuoteService;
import cn.tianshang.commerce.quote.QuoteStage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * 今日待办：把散在各个列表里、今天需要有人处理的事情数出来，按当前用户的角色排好顺序。
 * 首页顶部、企业微信机器人的“今日”和每天早上的推送都用这一份。
 *
 * <p>数据都取自网页用的同一批服务，所以业务员只数得到自己负责的；已终止的合同不算。每一项带一个链接，
 * 打开的就是对应的筛选结果，数字和列表一致。
 */
@Service
public class TodoService {

    /** 一项待办。amount 为空表示这一项不涉及金额。 */
    public record Item(String key, String label, int count, String unit, BigDecimal amount, String link) {}

    // 各角色关心的事，按先后排列；不在列表里的不显示给这个角色
    private static final Map<String, List<String>> ORDER = Map.of(
        Roles.ADMIN,
        List.of("CHANGES", "OVERDUE", "LATE_SHIP", "UNALLOCATED", "TO_CONTRACT", "QUOTE_EXPIRED", "DRAFT", "UNDATED"),
        Roles.MANAGER,
        List.of("CHANGES", "OVERDUE", "LATE_SHIP", "TO_CONTRACT", "QUOTE_EXPIRED", "DRAFT", "UNDATED"),
        Roles.FINANCE,
        List.of("UNALLOCATED", "OVERDUE", "UNDATED"),
        Roles.SALES,
        List.of("LATE_SHIP", "TO_CONTRACT", "QUOTE_EXPIRED", "DRAFT", "OVERDUE", "UNDATED"),
        Roles.VIEWER,
        List.of("OVERDUE", "LATE_SHIP")
    );

    private final ContractService contracts;
    private final ContractRecordService records;
    private final ProjectService projects;
    private final QuoteService quotes;
    private final ReceiptBatchService receiptBatches;

    public TodoService(
        ContractService contracts,
        ContractRecordService records,
        ProjectService projects,
        QuoteService quotes,
        ReceiptBatchService receiptBatches
    ) {
        this.contracts = contracts;
        this.records = records;
        this.projects = projects;
        this.quotes = quotes;
        this.receiptBatches = receiptBatches;
    }

    /** 当前用户今天的待办，只含数量大于 0 的项，按角色排序。 */
    public List<Item> today() {
        List<String> order = ORDER.getOrDefault(role(), List.of());
        if (order.isEmpty()) return List.of();
        var today = LocalDate.now();
        var all = new HashMap<String, Item>();

        if (order.contains("CHANGES") && Roles.canApprove()) {
            int n = records.pendingChanges().size();
            all.put("CHANGES", new Item("CHANGES", "待审批变更", n, "条", null, "/cash?tab=changes"));
        }

        var live = contracts
            .contracts()
            .stream()
            .filter(c -> !"TERMINATED".equals(c.get("status")))
            .toList();
        all.put(
            "OVERDUE",
            contractItem(live, "OVERDUE", "逾期应收", c -> num(c.get("overdue")).signum() > 0, "overdue", "OVERDUE")
        );
        all.put(
            "UNDATED",
            contractItem(live, "UNDATED", "应收未定截止日", c -> num(c.get("undated")).signum() > 0, null, "UNDATED")
        );
        all.put(
            "DRAFT",
            contractItem(live, "DRAFT", "拟定中合同", c -> "DRAFT".equals(c.get("status")), null, "DRAFT")
        );

        if (order.contains("UNALLOCATED") && (Roles.has(Roles.ADMIN) || Roles.has(Roles.FINANCE))) {
            var open = receiptBatches
                .list()
                .stream()
                .filter(b -> num(b.get("unallocated")).signum() > 0)
                .toList();
            all.put(
                "UNALLOCATED",
                new Item("UNALLOCATED", "待分配回款", open.size(), "笔", sum(open, "unallocated"), "/cash?tab=ledger")
            );
        }

        if (order.contains("LATE_SHIP")) {
            long late = projects
                .list()
                .stream()
                .filter(p -> dealt(p) && !"TERMINATED".equals(p.get("contract_state")))
                .filter(p -> !"已发货".equals(p.get("shipping_status")))
                .filter(p -> {
                    var d = date(p.get("expected_ship_on"));
                    return d != null && d.isBefore(today);
                })
                .count();
            all.put(
                "LATE_SHIP",
                new Item("LATE_SHIP", "超期未发货", (int) late, "个门店", null, "/projects?quick=LATE")
            );
        }

        if (order.contains("TO_CONTRACT") || order.contains("QUOTE_EXPIRED")) {
            var list = quotes.list();
            long toContract = list
                .stream()
                .filter(q -> QuoteStage.ACCEPTED.equals(q.get("stage")))
                .count();
            long expired = list
                .stream()
                .filter(q -> QuoteStage.ISSUED.equals(q.get("stage")))
                .filter(q -> {
                    var d = date(q.get("valid_until"));
                    return d != null && d.isBefore(today);
                })
                .count();
            all.put(
                "TO_CONTRACT",
                new Item(
                    "TO_CONTRACT",
                    "客户已确认、待生成合同",
                    (int) toContract,
                    "份报价",
                    null,
                    "/quotes?quick=ACCEPTED"
                )
            );
            all.put(
                "QUOTE_EXPIRED",
                new Item("QUOTE_EXPIRED", "报价已过有效期", (int) expired, "份报价", null, "/quotes?quick=EXPIRED")
            );
        }

        var out = new ArrayList<Item>();
        for (String key : order) {
            var item = all.get(key);
            if (item != null && item.count() > 0) out.add(item);
        }
        return out;
    }

    private static Item contractItem(
        List<Map<String, Object>> rows,
        String key,
        String label,
        Predicate<Map<String, Object>> test,
        String amountField,
        String quick
    ) {
        var hit = rows.stream().filter(test).toList();
        return new Item(
            key,
            label,
            hit.size(),
            "份合同",
            amountField == null ? null : sum(hit, amountField),
            "/cash?tab=contracts&quick=" + quick
        );
    }

    /** 已成交的门店：有合同（含拟定中），或客户已确认报价。与项目台账的“已成交”一致。 */
    private static boolean dealt(Map<String, Object> p) {
        return !"NONE".equals(p.get("contract_state")) || num(p.get("confirmed_quotes")).signum() > 0;
    }

    private static String role() {
        for (String r : List.of(Roles.ADMIN, Roles.MANAGER, Roles.FINANCE, Roles.SALES, Roles.VIEWER))
            if (Roles.has(r)) return r;
        return "";
    }

    private static BigDecimal num(Object v) {
        BigDecimal d = v == null ? null : ContractLedger.decimal(v);
        return d == null ? BigDecimal.ZERO : d;
    }

    private static LocalDate date(Object v) {
        return v == null ? null : ContractLedger.date(v);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String field) {
        return rows
            .stream()
            .map(r -> num(r.get(field)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
