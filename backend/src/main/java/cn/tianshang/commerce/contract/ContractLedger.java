package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.Ownership;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 以门店项目为单位整理合同口径的经营事实，供合同台账和经营总览共用。
 *
 * <p>合同额口径：项目已确认订单；或项目已填合同号且报价为历史/已出具状态（未确认应收的“待确认合同”）。
 * 每个项目只取一份报价：已确认的那份，否则取最新版本。
 */
@Component
public class ContractLedger {

    private final Database db;
    private final Ownership ownership;

    public ContractLedger(Database db, Ownership ownership) {
        this.db = db;
        this.ownership = ownership;
    }

    public record Item(String name, String line, BigDecimal quantity, BigDecimal amount, BigDecimal cost) {}

    public record Fact(
        String projectId,
        String projectCode,
        String projectName,
        String brand,
        String customerId,
        String customerName,
        String contractNo,
        String shippingStatus,
        LocalDate orderDate,
        String quoteId,
        String quoteCode,
        String quoteStatus,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal received,
        List<Item> items,
        String city,
        String ownerId,
        String ownerName,
        LocalDate shippedOn,
        /** 应收口径的订单金额（经营分析按签约金额调整 amount 时，应收仍按确认的订单金额）。 */
        BigDecimal orderAmount,
        /** 门店所属合同的状态：NONE 没有合同；有合同号但没有合同记录的历史门店为 SIGNED。 */
        String contractState
    ) {
        /** 客户已确认这份报价。 */
        public boolean accepted() {
            return "ACCEPTED".equals(quoteStatus);
        }

        /**
         * 已建立应收：客户已确认报价，或门店所属合同已签订（含已完结）。
         * 合同签订即产生应收，不需要再单独“确认应收”；只是没有截止日期的应收不计逾期。
         */
        public boolean confirmed() {
            return (
                accepted() ||
                (List.of("SIGNED", "COMPLETED").contains(contractState) &&
                    List.of("HISTORICAL", "ISSUED").contains(quoteStatus))
            );
        }

        public boolean contracted() {
            return accepted() || (contractNo != null && List.of("HISTORICAL", "ISSUED").contains(quoteStatus));
        }

        public BigDecimal outstanding() {
            return confirmed() ? orderAmount.subtract(received) : BigDecimal.ZERO;
        }

        public boolean shipped() {
            return Contracts.SHIPPED.equals(shippingStatus);
        }

        /** 合同分组键：有合同号按合同号，否则每个项目单独成组。 */
        public String contractKey() {
            return contractNo != null ? contractNo : "#" + projectCode;
        }

        public String month() {
            return orderDate == null ? null : orderDate.toString().substring(0, 7);
        }
    }

    /** 当前用户可见的经营事实：商务只含自己负责的门店。 */
    public List<Fact> facts() {
        String sql = """
            SELECT p.*,c.name AS customer_name,u.display_name AS owner_name,
              CASE WHEN p.contract_no IS NULL OR p.contract_no='' THEN 'NONE' ELSE COALESCE(k.status,'SIGNED') END AS contract_state
            FROM project p JOIN partner c ON c.id=p.customer_id LEFT JOIN app_user u ON u.id=p.owner_id
            LEFT JOIN contract k ON k.customer_id=p.customer_id AND k.contract_no=p.contract_no
            """;
        var projects = ownership.restricted()
            ? db.jdbc.queryForList(
                  sql + " WHERE p.owner_id=? ORDER BY p.order_date,p.code",
                  Objects.toString(ownership.userId(), "-")
              )
            : db.jdbc.queryForList(sql + " ORDER BY p.order_date,p.code");
        var quotes = new HashMap<String, Map<String, Object>>();
        for (var q : db.jdbc.queryForList(
            "SELECT * FROM quotation WHERE status NOT IN ('LOST','SUPERSEDED') ORDER BY version"
        )) {
            String key = q.get("project_id").toString();
            var current = quotes.get(key);
            boolean accepted = "ACCEPTED".equals(q.get("status"));
            if (current == null || accepted || !"ACCEPTED".equals(current.get("status"))) quotes.put(key, q);
        }
        var items = new HashMap<String, List<Item>>();
        for (var i : db.jdbc.queryForList(
            "SELECT quotation_id,name,quantity,amount,cost FROM quotation_item ORDER BY position"
        ))
            items
                .computeIfAbsent(i.get("quotation_id").toString(), k -> new ArrayList<>())
                .add(
                    new Item(
                        i.get("name").toString(),
                        Contracts.productLine(i.get("name").toString()),
                        decimal(i.get("quantity")),
                        decimal(i.get("amount")),
                        decimal(i.get("cost"))
                    )
                );
        // 回款按门店汇总：报价换版本后，旧版本名下的流水仍算在这个门店上
        var received = new HashMap<String, BigDecimal>();
        for (var e : db.jdbc.queryForList(
            """
            SELECT q.project_id,SUM(CASE WHEN e.kind='RECEIPT' THEN e.amount ELSE -e.amount END) AS net
            FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id GROUP BY q.project_id
            """
        ))
            received.put(e.get("project_id").toString(), decimal(e.get("net")));

        var out = new ArrayList<Fact>();
        for (var p : projects) {
            var q = quotes.get(p.get("id").toString());
            if (q == null) continue;
            String quoteId = q.get("id").toString();
            var lines = items.getOrDefault(quoteId, List.of());
            BigDecimal amount = lines
                .stream()
                .map(Item::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            LocalDate order = date(p.get("order_date"));
            // 新建报价缺下单日期时按报价日期归月；历史报价缺日期则保持未知，避免全部落入导入当月。
            if (order == null && !"HISTORICAL".equals(q.get("status"))) order = date(q.get("created_at"));
            out.add(
                new Fact(
                    p.get("id").toString(),
                    p.get("code").toString(),
                    p.get("name").toString(),
                    p.get("brand").toString(),
                    p.get("customer_id").toString(),
                    p.get("customer_name").toString(),
                    Contracts.blankToNull((String) p.get("contract_no")),
                    Contracts.shipping((String) p.get("shipping_status")),
                    order,
                    quoteId,
                    q.get("code").toString(),
                    q.get("status").toString(),
                    date(q.get("due_date")),
                    amount,
                    received.getOrDefault(p.get("id").toString(), BigDecimal.ZERO),
                    lines,
                    Contracts.blankToNull((String) p.get("city")),
                    (String) p.get("owner_id"),
                    (String) p.get("owner_name"),
                    date(p.get("shipped_on")),
                    amount,
                    p.get("contract_state").toString()
                )
            );
        }
        return out;
    }

    public List<Fact> contracted() {
        return facts().stream().filter(Fact::contracted).toList();
    }

    /**
     * 经营分析口径：在门店事实上叠加合同信息。
     *
     * <ul>
     *   <li>已终止的合同不计入；</li>
     *   <li>填了签约金额的合同，按门店订单金额比例把签约金额分摊到各门店、各明细行（应收仍按确认的订单金额）；</li>
     *   <li>登记了实际成本的合同，按金额比例把实际成本分摊到各明细行，替代报价预估成本；</li>
     *   <li>合同有签订日期时按签订月份归月，否则按下单月份；</li>
     *   <li>只登记了合同、尚无门店订单的已签订合同，按签约金额单独计入（产品线归“其他”）。</li>
     * </ul>
     */
    public List<Fact> analysis() {
        var headers = new HashMap<String, Map<String, Object>>();
        for (var h : db.jdbc.queryForList(
            """
            SELECT k.*,c.name AS customer_name,u.display_name AS owner_name FROM contract k
            JOIN partner c ON c.id=k.customer_id LEFT JOIN app_user u ON u.id=k.owner_id
            """
        ))
            headers.put(h.get("customer_id") + "::" + h.get("contract_no"), h);
        var actual = new HashMap<String, BigDecimal>();
        for (var r : db.jdbc.queryForList(
            "SELECT customer_id,contract_no,SUM(amount) AS v FROM contract_cost WHERE deleted_at IS NULL GROUP BY customer_id,contract_no"
        ))
            actual.put(r.get("customer_id") + "::" + r.get("contract_no"), decimal(r.get("v")));
        var groups = new LinkedHashMap<String, List<Fact>>();
        for (var f : contracted())
            groups.computeIfAbsent(f.customerId() + "::" + f.contractKey(), k -> new ArrayList<>()).add(f);
        var out = new ArrayList<Fact>();
        for (var e : groups.entrySet()) {
            var h = headers.get(e.getKey());
            if (h != null && "TERMINATED".equals(h.get("status"))) continue;
            var g = e.getValue();
            BigDecimal orders = g.stream().map(Fact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal signed = h == null ? null : decimal(h.get("signed_amount"));
            BigDecimal total = signed != null && orders.signum() > 0 ? signed : orders;
            BigDecimal cost = actual.get(e.getKey());
            boolean useActual = cost != null && cost.signum() > 0 && total.signum() > 0;
            LocalDate signedOn = h == null ? null : date(h.get("signed_on"));
            for (var f : g) {
                var items = new ArrayList<Item>();
                for (var i : f.items()) {
                    BigDecimal amount = i.amount() == null ? null : share(i.amount(), total, orders);
                    BigDecimal itemCost = useActual ? (amount == null ? null : share(amount, cost, total)) : i.cost();
                    items.add(new Item(i.name(), i.line(), i.quantity(), amount, itemCost));
                }
                out.add(
                    new Fact(
                        f.projectId(),
                        f.projectCode(),
                        f.projectName(),
                        f.brand(),
                        f.customerId(),
                        f.customerName(),
                        f.contractNo(),
                        f.shippingStatus(),
                        signedOn != null ? signedOn : f.orderDate(),
                        f.quoteId(),
                        f.quoteCode(),
                        f.quoteStatus(),
                        f.dueDate(),
                        share(f.amount(), total, orders),
                        f.received(),
                        items,
                        f.city(),
                        f.ownerId(),
                        f.ownerName(),
                        f.shippedOn(),
                        f.amount(),
                        f.contractState()
                    )
                );
            }
        }
        // 只有合同记录的已签订合同（如框架合同）
        for (var e : headers.entrySet()) {
            var h = e.getValue();
            if (groups.containsKey(e.getKey())) continue;
            if (!List.of("SIGNED", "COMPLETED").contains(h.get("status").toString())) continue;
            BigDecimal signed = decimal(h.get("signed_amount"));
            if (signed == null || signed.signum() <= 0 || !ownership.visible(h.get("owner_id"))) continue;
            boolean hasProjects =
                db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
                    Integer.class,
                    h.get("customer_id"),
                    h.get("contract_no"),
                    h.get("contract_no")
                ) >
                0;
            if (hasProjects) continue;
            BigDecimal cost = actual.get(e.getKey());
            String title = Objects.toString(h.get("title"), h.get("contract_no").toString());
            out.add(
                new Fact(
                    h.get("id").toString(),
                    h.get("contract_no").toString(),
                    title,
                    "未指定",
                    h.get("customer_id").toString(),
                    h.get("customer_name").toString(),
                    h.get("contract_no").toString(),
                    null,
                    date(h.get("signed_on")),
                    null,
                    null,
                    "SIGNED",
                    null,
                    signed,
                    BigDecimal.ZERO,
                    List.of(
                        new Item(
                            title,
                            Contracts.OTHER,
                            BigDecimal.ONE,
                            signed,
                            cost != null && cost.signum() > 0 ? cost : null
                        )
                    ),
                    null,
                    (String) h.get("owner_id"),
                    (String) h.get("owner_name"),
                    null,
                    BigDecimal.ZERO,
                    h.get("status").toString()
                )
            );
        }
        return out;
    }

    /** value × part / whole（两位小数）；whole 为零或 part 等于 whole 时原样返回。 */
    private static BigDecimal share(BigDecimal value, BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part.compareTo(whole) == 0) return value;
        return value.multiply(part).divide(whole, 2, java.math.RoundingMode.HALF_UP);
    }

    public static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    public static LocalDate date(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
    }
}
