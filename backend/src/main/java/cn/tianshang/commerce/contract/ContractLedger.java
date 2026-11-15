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
        String ownerName
    ) {
        public boolean confirmed() {
            return "ACCEPTED".equals(quoteStatus);
        }

        public boolean contracted() {
            return confirmed() || (contractNo != null && List.of("HISTORICAL", "ISSUED").contains(quoteStatus));
        }

        public BigDecimal outstanding() {
            return confirmed() ? amount.subtract(received) : BigDecimal.ZERO;
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
            SELECT p.*,c.name AS customer_name,u.display_name AS owner_name
            FROM project p JOIN partner c ON c.id=p.customer_id LEFT JOIN app_user u ON u.id=p.owner_id
            """;
        var projects = ownership.restricted()
            ? db.jdbc.queryForList(
                  sql + " WHERE p.owner_id=? ORDER BY p.order_date,p.code",
                  Objects.toString(ownership.userId(), "-")
              )
            : db.jdbc.queryForList(sql + " ORDER BY p.order_date,p.code");
        var quotes = new HashMap<String, Map<String, Object>>();
        for (var q : db.jdbc.queryForList("SELECT * FROM quotation ORDER BY version")) {
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
        var received = new HashMap<String, BigDecimal>();
        for (var e : db.jdbc.queryForList(
            """
            SELECT quotation_id,SUM(CASE WHEN kind='RECEIPT' THEN amount ELSE -amount END) AS net
            FROM cash_entry GROUP BY quotation_id
            """
        ))
            received.put(e.get("quotation_id").toString(), decimal(e.get("net")));

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
                    received.getOrDefault(quoteId, BigDecimal.ZERO),
                    lines,
                    Contracts.blankToNull((String) p.get("city")),
                    (String) p.get("owner_id"),
                    (String) p.get("owner_name")
                )
            );
        }
        return out;
    }

    public List<Fact> contracted() {
        return facts().stream().filter(Fact::contracted).toList();
    }

    static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    static LocalDate date(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
    }
}
