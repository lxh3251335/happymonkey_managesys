package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.quote.QuoteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 合同台账：按合同号汇总门店订单，确认合同应收，整笔登记回款并分摊到门店。 */
@Service
public class ContractService {

    private final Database db;
    private final ContractLedger ledger;
    private final QuoteService quotes;
    private final ContractAttachmentService attachments;
    private final Ownership ownership;

    public ContractService(
        Database db,
        ContractLedger ledger,
        QuoteService quotes,
        ContractAttachmentService attachments,
        Ownership ownership
    ) {
        this.db = db;
        this.ledger = ledger;
        this.quotes = quotes;
        this.attachments = attachments;
        this.ownership = ownership;
    }

    /** 合同状态（合同记录）：草拟中、已签订、已完结、已终止。没有合同记录的历史合同视为已签订。 */
    public static final Map<String, String> STATUS = Map.of(
        "DRAFT",
        "拟定中",
        "SIGNED",
        "已签订",
        "COMPLETED",
        "已完结",
        "TERMINATED",
        "已终止"
    );

    /**
     * 合同台账：门店订单按“客户 + 合同号”汇总，叠加合同信息、开票、回款、实际成本与变更状态。
     * 商务只看到自己负责的合同。只登记了合同信息、尚未关联门店的合同也列出。
     */
    public List<Map<String, Object>> contracts() {
        var groups = new LinkedHashMap<String, List<ContractLedger.Fact>>();
        for (var f : ledger.contracted())
            groups.computeIfAbsent(f.customerId() + "::" + f.contractKey(), k -> new ArrayList<>()).add(f);
        var headers = new HashMap<String, Map<String, Object>>();
        for (var h : db.jdbc.queryForList(
            """
            SELECT k.*,c.name AS customer_name,m.name AS company_name,m.short_name AS company_short,
              u.display_name AS owner_name,
              (SELECT COUNT(*) FROM contract_change x WHERE x.contract_id=k.id AND x.status='PENDING') AS pending_changes
            FROM contract k JOIN partner c ON c.id=k.customer_id LEFT JOIN company m ON m.id=k.company_id
            LEFT JOIN app_user u ON u.id=k.owner_id
            """
        ))
            headers.put(h.get("customer_id") + "::" + h.get("contract_no"), h);
        // 只有合同记录、没有（可见）门店订单的合同
        for (var e : headers.entrySet())
            if (!groups.containsKey(e.getKey()) && ownership.visible(e.getValue().get("owner_id"))) {
                boolean anyProject =
                    db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
                        Integer.class,
                        e.getValue().get("customer_id"),
                        e.getValue().get("contract_no"),
                        e.getValue().get("contract_no")
                    ) >
                    0;
                // 商务看不到别人门店的合同；门店存在但对当前用户不可见时不列出
                if (!anyProject || !ownership.restricted()) groups.put(e.getKey(), new ArrayList<>());
            }
        var invoiced = sums(
            "SELECT customer_id,contract_no,SUM(CASE WHEN kind='INVOICE' THEN amount ELSE -amount END) AS v,MAX(issued_on) AS d FROM contract_invoice WHERE deleted_at IS NULL GROUP BY customer_id,contract_no"
        );
        var costs = sums(
            "SELECT customer_id,contract_no,SUM(amount) AS v,MAX(incurred_on) AS d FROM contract_cost WHERE deleted_at IS NULL GROUP BY customer_id,contract_no"
        );
        var lastReceipt = sums(
            "SELECT customer_id,contract_no,COUNT(*) AS v,MAX(paid_on) AS d FROM contract_receipt WHERE kind='RECEIPT' GROUP BY customer_id,contract_no"
        );
        var today = LocalDate.now();
        var attachmentCounts = attachments.counts();
        // 发货与物流由项目台账维护，这里只读展示
        var delivery = new HashMap<String, Map<String, Object>>();
        for (var r : db.jdbc.queryForList("SELECT id,expected_ship_on,courier,tracking_no FROM project"))
            delivery.put(r.get("id").toString(), r);
        var out = new ArrayList<Map<String, Object>>();
        for (var entry : groups.entrySet()) {
            var g = entry.getValue();
            var header = headers.get(entry.getKey());
            String[] key = entry.getKey().split("::", 2);
            var first = g.isEmpty() ? null : g.get(0);
            BigDecimal amount = sum(g, ContractLedger.Fact::amount),
                received = sum(g, ContractLedger.Fact::received),
                outstanding = sum(g, ContractLedger.Fact::outstanding),
                confirmedAmount = sum(
                    g.stream().filter(ContractLedger.Fact::confirmed).toList(),
                    ContractLedger.Fact::amount
                ),
                overdue = sum(
                    g
                        .stream()
                        .filter(f -> f.confirmed() && f.dueDate() != null && f.dueDate().isBefore(today))
                        .toList(),
                    ContractLedger.Fact::outstanding
                );
            long confirmed = g.stream().filter(ContractLedger.Fact::confirmed).count();
            BigDecimal signed = header == null ? null : ContractLedger.decimal(header.get("signed_amount"));
            BigDecimal contractAmount = signed != null ? signed : amount;
            BigDecimal invoice = value(invoiced, entry.getKey()),
                actualCost = value(costs, entry.getKey());
            // 预估成本：报价明细成本；有缺失时标记不完整
            BigDecimal estimated = BigDecimal.ZERO;
            boolean costComplete = !g.isEmpty();
            for (var f : g)
                for (var i : f.items()) {
                    if (i.amount() == null) continue;
                    if (i.cost() == null) costComplete = false;
                    else estimated = estimated.add(i.cost());
                }
            BigDecimal cost = actualCost.signum() > 0 ? actualCost : costComplete ? estimated : null;
            var row = new LinkedHashMap<String, Object>();
            row.put("id", header == null ? null : header.get("id"));
            row.put("customer_id", key[0]);
            row.put("customer_name", first != null ? first.customerName() : header.get("customer_name"));
            String contractKey = key[1];
            row.put("contract_no", contractKey.startsWith("#") ? null : contractKey);
            row.put("contract_key", contractKey);
            row.put("status", header == null ? "SIGNED" : header.get("status"));
            row.put("recorded", header != null);
            row.put("title", header == null ? null : header.get("title"));
            row.put("content", header == null ? null : header.get("content"));
            row.put("signed_on", header == null ? null : header.get("signed_on"));
            row.put("company_id", header == null ? null : header.get("company_id"));
            row.put(
                "company_name",
                header == null
                    ? null
                    : header.get("company_short") != null
                        ? header.get("company_short")
                        : header.get("company_name")
            );
            String ownerId =
                header != null && header.get("owner_id") != null ? header.get("owner_id").toString() : commonOwner(g);
            row.put("owner_id", ownerId);
            row.put(
                "owner_name",
                header != null && header.get("owner_name") != null
                    ? header.get("owner_name")
                    : g
                          .stream()
                          .filter(f -> Objects.equals(f.ownerId(), ownerId))
                          .map(ContractLedger.Fact::ownerName)
                          .filter(Objects::nonNull)
                          .findFirst()
                          .orElse(null)
            );
            row.put("archived", header != null && Boolean.TRUE.equals(header.get("archived")));
            row.put("pending_changes", header == null ? 0 : ((Number) header.get("pending_changes")).intValue());
            row.put("brands", g.stream().map(ContractLedger.Fact::brand).distinct().sorted().toList());
            row.put(
                "cities",
                g.stream().map(ContractLedger.Fact::city).filter(Objects::nonNull).distinct().sorted().toList()
            );
            row.put("projects", g.size());
            row.put("shipped", g.stream().filter(ContractLedger.Fact::shipped).count());
            row.put("confirmed_projects", confirmed);
            row.put(
                "confirmation",
                g.isEmpty() || confirmed == 0 ? "PENDING" : confirmed == g.size() ? "CONFIRMED" : "PARTIAL"
            );
            row.put("order_amount", amount);
            row.put("signed_amount", signed);
            row.put("amount", contractAmount);
            row.put("amount_gap", signed == null ? null : signed.subtract(amount));
            row.put("confirmed_amount", confirmedAmount);
            row.put("received", received);
            row.put("outstanding", outstanding);
            row.put("overdue", overdue);
            row.put("collection_rate", ratio(received, contractAmount));
            row.put("invoiced", invoice);
            row.put("uninvoiced", contractAmount.subtract(invoice));
            row.put("invoiced_unreceived", invoice.subtract(received).max(BigDecimal.ZERO));
            row.put(
                "invoice_status",
                invoice.signum() <= 0 ? "NONE" : invoice.compareTo(contractAmount) >= 0 ? "FULL" : "PARTIAL"
            );
            row.put("last_invoice_on", date(invoiced, entry.getKey()));
            row.put("last_receipt_on", date(lastReceipt, entry.getKey()));
            row.put("actual_cost", actualCost);
            row.put("estimated_cost", costComplete ? estimated : null);
            row.put("cost", cost);
            row.put("cost_source", actualCost.signum() > 0 ? "ACTUAL" : costComplete ? "ESTIMATED" : "INCOMPLETE");
            row.put("profit", cost == null ? null : contractAmount.subtract(cost));
            row.put("gross_margin", cost == null ? null : ratio(contractAmount.subtract(cost), contractAmount));
            row.put("attachments", attachmentCounts.getOrDefault(entry.getKey(), 0));
            row.put(
                "order_date",
                g
                    .stream()
                    .map(ContractLedger.Fact::orderDate)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null)
            );
            var dueDate = g
                .stream()
                .map(ContractLedger.Fact::dueDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
            row.put("due_date", dueDate);
            var overdueSince = g
                .stream()
                .filter(
                    f ->
                        f.confirmed() &&
                        f.outstanding().signum() > 0 &&
                        f.dueDate() != null &&
                        f.dueDate().isBefore(today)
                )
                .map(ContractLedger.Fact::dueDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
            row.put(
                "overdue_days",
                overdueSince == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(overdueSince, today)
            );
            row.put(
                "items",
                g
                    .stream()
                    .map(f -> {
                        var x = new LinkedHashMap<String, Object>();
                        x.put("project_id", f.projectId());
                        x.put("project_code", f.projectCode());
                        x.put("project_name", f.projectName());
                        x.put("brand", f.brand());
                        x.put("city", f.city());
                        x.put("owner_name", f.ownerName());
                        x.put("quote_id", f.quoteId());
                        x.put("quote_code", f.quoteCode());
                        x.put("status", f.quoteStatus());
                        x.put("shipping_status", f.shippingStatus());
                        x.put("shipped_on", f.shippedOn());
                        var d = delivery.getOrDefault(f.projectId(), Map.of());
                        x.put("expected_ship_on", d.get("expected_ship_on"));
                        x.put("courier", d.get("courier"));
                        x.put("tracking_no", d.get("tracking_no"));
                        x.put("order_date", f.orderDate());
                        x.put("due_date", f.dueDate());
                        x.put("amount", f.amount());
                        x.put("received", f.received());
                        x.put("outstanding", f.outstanding());
                        return x;
                    })
                    .toList()
            );
            out.add(row);
        }
        out.sort(
            Comparator.comparing((Map<String, Object> r) ->
                Objects.toString(r.get("signed_on") != null ? r.get("signed_on") : r.get("order_date"), "0000")
            )
                .reversed()
                .thenComparing(r -> r.get("contract_key").toString())
        );
        return out;
    }

    /** 单份合同的台账行（含权限检查）。 */
    public Map<String, Object> contract(String customerId, String contractKey) {
        ownership.checkContract(customerId, contractKey);
        return contracts()
            .stream()
            .filter(r -> r.get("customer_id").equals(customerId) && r.get("contract_key").equals(contractKey))
            .findFirst()
            .orElseThrow(() -> new ApiException("未找到该合同"));
    }

    /** 门店负责人一致时作为合同负责人。 */
    private static String commonOwner(List<ContractLedger.Fact> g) {
        var owners = g.stream().map(ContractLedger.Fact::ownerId).distinct().toList();
        return owners.size() == 1 ? owners.get(0) : null;
    }

    private record Sum(BigDecimal value, Object date) {}

    private Map<String, Sum> sums(String sql) {
        var out = new HashMap<String, Sum>();
        for (var r : db.jdbc.queryForList(sql))
            out.put(
                r.get("customer_id") + "::" + r.get("contract_no"),
                new Sum(ContractLedger.decimal(r.get("v")), r.get("d"))
            );
        return out;
    }

    private static BigDecimal value(Map<String, Sum> sums, String key) {
        var s = sums.get(key);
        return s == null || s.value() == null ? BigDecimal.ZERO : s.value();
    }

    private static Object date(Map<String, Sum> sums, String key) {
        var s = sums.get(key);
        return s == null ? null : s.date();
    }

    public record ConfirmInput(@NotBlank String customerId, @NotBlank String contractKey, @NotNull LocalDate dueDate) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES','FINANCE')")
    @Transactional
    public Map<String, Object> confirm(@Valid ConfirmInput input) {
        ownership.checkContract(input.customerId(), input.contractKey());
        var group = group(input.customerId(), input.contractKey());
        int count = 0;
        for (var f : group) {
            if (f.confirmed()) continue;
            if (f.amount().signum() <= 0) throw new ApiException("门店 " + f.projectName() + " 报价金额为零，无法确认");
            quotes.transition(f.quoteId(), "accept", input.dueDate());
            count++;
        }
        if (count == 0) throw new ApiException("该合同的门店订单均已确认");
        db.audit(
            "CONTRACT_CONFIRMED",
            input.contractKey(),
            "确认 " + count + " 个门店订单应收，截止 " + input.dueDate()
        );
        return Map.of("confirmed", count);
    }

    public record ReceiptInput(
        @NotBlank String customerId,
        @NotBlank String contractKey,
        @NotNull @Pattern(regexp = "RECEIPT|REFUND") String kind,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal amount,
        @NotNull @PastOrPresent LocalDate paidOn,
        @NotBlank @Size(max = 140) String reference,
        @Size(max = 1000) String note,
        String reversesId
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public Map<String, Object> receipt(@Valid ReceiptInput c) {
        var group = group(c.customerId(), c.contractKey());
        String reference = c.reference().trim();
        if (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_receipt WHERE reference=?",
                Integer.class,
                reference
            ) >
            0
        ) throw new ApiException("该凭证号已登记");
        for (var f : group) db.one("SELECT id FROM quotation WHERE id=? FOR UPDATE", f.quoteId());
        String contractNo = group.get(0).contractKey();
        String id = db.id();
        List<String> targets = new ArrayList<>();
        List<BigDecimal> limits = new ArrayList<>();
        List<String> reverses = new ArrayList<>();
        if (c.kind().equals("RECEIPT")) {
            if (c.reversesId() != null && !c.reversesId().isBlank()) throw new ApiException("收款不能关联退款原流水");
            long pending = group
                .stream()
                .filter(f -> !f.confirmed())
                .count();
            if (pending > 0) throw new ApiException("该合同还有 " + pending + " 个门店订单未确认应收，请先确认合同");
            for (var f : group) {
                if (f.outstanding().signum() <= 0) continue;
                targets.add(f.quoteId());
                limits.add(f.outstanding());
                reverses.add(null);
            }
            if (sum(limits).compareTo(c.amount()) < 0) throw new ApiException(
                "回款超过该合同剩余应收 " + sum(limits).toPlainString() + " 元"
            );
        } else {
            if (c.reversesId() == null || c.reversesId().isBlank()) throw new ApiException("退款必须关联原合同收款");
            var original = db.one(
                "SELECT * FROM contract_receipt WHERE id=? AND customer_id=? AND contract_no=? AND kind='RECEIPT'",
                c.reversesId(),
                c.customerId(),
                contractNo
            );
            if (c.paidOn().isBefore(ContractLedger.date(original.get("paid_on")))) throw new ApiException(
                "退款日期不能早于原收款日期"
            );
            for (var row : db.jdbc.queryForList(
                """
                SELECT e.id,e.quotation_id,e.amount-COALESCE((SELECT SUM(r.amount) FROM cash_entry r WHERE r.reverses_id=e.id),0) AS refundable
                FROM cash_entry e WHERE e.contract_receipt_id=? AND e.kind='RECEIPT' ORDER BY e.reference
                """,
                c.reversesId()
            )) {
                var refundable = ContractLedger.decimal(row.get("refundable"));
                if (refundable.signum() <= 0) continue;
                targets.add(row.get("quotation_id").toString());
                limits.add(refundable);
                reverses.add(row.get("id").toString());
            }
            if (sum(limits).compareTo(c.amount()) < 0) throw new ApiException(
                "退款超过原收款可退金额 " + sum(limits).toPlainString() + " 元"
            );
        }
        db.jdbc.update(
            "INSERT INTO contract_receipt(id,customer_id,contract_no,kind,amount,paid_on,reference,note,reverses_id) VALUES(?,?,?,?,?,?,?,?,?)",
            id,
            c.customerId(),
            contractNo,
            c.kind(),
            c.amount(),
            c.paidOn(),
            reference,
            c.note(),
            c.kind().equals("REFUND") ? c.reversesId() : null
        );
        var shares = allocate(c.amount(), limits);
        int n = 0;
        for (int i = 0; i < shares.size(); i++) {
            if (shares.get(i).signum() <= 0) continue;
            String lineRef = reference + "#" + (++n);
            if (
                db.jdbc.queryForObject("SELECT COUNT(*) FROM cash_entry WHERE reference=?", Integer.class, lineRef) > 0
            ) throw new ApiException("分摊流水号 " + lineRef + " 已存在，请更换凭证号");
            db.jdbc.update(
                "INSERT INTO cash_entry(id,quotation_id,kind,amount,paid_on,reference,note,reverses_id,contract_receipt_id) VALUES(?,?,?,?,?,?,?,?,?)",
                db.id(),
                targets.get(i),
                c.kind(),
                shares.get(i),
                c.paidOn(),
                lineRef,
                "合同" + (c.kind().equals("RECEIPT") ? "回款" : "退款") + "分摊：" + contractNo,
                reverses.get(i),
                id
            );
        }
        db.audit(
            "CONTRACT_" + c.kind(),
            id,
            "合同 " + contractNo + "，金额 " + c.amount() + "，分摊至 " + n + " 个门店订单"
        );
        return Map.of("id", id, "allocations", n);
    }

    public List<Map<String, Object>> receipts() {
        var rows = db.jdbc.queryForList(
            """
            SELECT r.*,c.name AS customer_name,
              (SELECT COUNT(*) FROM cash_entry e WHERE e.contract_receipt_id=r.id) AS allocations,
              (SELECT COALESCE(SUM(x.amount),0) FROM contract_receipt x WHERE x.reverses_id=r.id) AS refunded
            FROM contract_receipt r JOIN partner c ON c.id=r.customer_id
            ORDER BY r.paid_on DESC,r.created_at DESC
            """
        );
        return visibleOnly(rows);
    }

    /** 商务只保留自己可见合同的记录（按 customer_id + contract_no）。 */
    public List<Map<String, Object>> visibleOnly(List<Map<String, Object>> rows) {
        if (!ownership.restricted()) return rows;
        var keys = new HashSet<String>();
        for (var c : contracts()) keys.add(c.get("customer_id") + "::" + c.get("contract_key"));
        return rows
            .stream()
            .filter(r -> keys.contains(r.get("customer_id") + "::" + r.get("contract_no")))
            .toList();
    }

    /**
     * 按各门店可分摊额度比例分配金额：先按比例向下取整到分，余下的分依次补给仍有额度的门店。
     * 结果合计等于 amount，且每项不超过其额度（调用前已确认 amount 不超过额度合计）。
     */
    static List<BigDecimal> allocate(BigDecimal amount, List<BigDecimal> limits) {
        BigDecimal total = sum(limits);
        var shares = new ArrayList<BigDecimal>();
        if (total.signum() <= 0) return shares;
        if (amount.compareTo(total) == 0) return new ArrayList<>(limits);
        BigDecimal assigned = BigDecimal.ZERO;
        var remainders = new ArrayList<BigDecimal>();
        for (var limit : limits) {
            var exact = amount.multiply(limit).divide(total, 10, RoundingMode.HALF_UP);
            var share = exact.setScale(2, RoundingMode.DOWN);
            shares.add(share);
            remainders.add(exact.subtract(share));
            assigned = assigned.add(share);
        }
        // 最大余数法：余下的分优先补给小数部分最大的门店
        var order = new ArrayList<Integer>();
        for (int i = 0; i < shares.size(); i++) order.add(i);
        order.sort(
            Comparator.comparing((Integer i) -> remainders.get(i))
                .reversed()
                .thenComparing(i -> i)
        );
        BigDecimal cent = new BigDecimal("0.01"),
            left = amount.subtract(assigned);
        while (left.signum() > 0) {
            boolean moved = false;
            for (int i : order) {
                if (left.signum() <= 0) break;
                if (shares.get(i).add(cent).compareTo(limits.get(i)) <= 0) {
                    shares.set(i, shares.get(i).add(cent));
                    left = left.subtract(cent);
                    moved = true;
                }
            }
            if (!moved) throw new IllegalStateException("分摊额度不足");
        }
        return shares;
    }

    private List<ContractLedger.Fact> group(String customerId, String contractKey) {
        var group = ledger
            .contracted()
            .stream()
            .filter(f -> f.customerId().equals(customerId) && f.contractKey().equals(contractKey))
            .toList();
        if (group.isEmpty()) throw new ApiException("未找到该合同的门店订单");
        return group;
    }

    private static BigDecimal sum(
        List<ContractLedger.Fact> rows,
        java.util.function.Function<ContractLedger.Fact, BigDecimal> f
    ) {
        return rows.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.divide(whole, 6, RoundingMode.HALF_UP);
    }
}
