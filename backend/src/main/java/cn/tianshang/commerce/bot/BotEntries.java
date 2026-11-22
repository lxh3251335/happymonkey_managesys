package cn.tianshang.commerce.bot;

import static cn.tianshang.commerce.bot.BotText.*;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.Contracts;
import cn.tianshang.commerce.finance.*;
import cn.tianshang.commerce.project.DeliveryParser;
import cn.tianshang.commerce.project.ProjectService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 机器人的录入：把转发来的运单、收货信息、到账短信、发票 PDF 认出来，先回一份“将登记什么”的预览，
 * 对方回复“确认”才真正写入。写入走的是网页用的同一批服务，权限、校验和操作日志都一样。
 *
 * <p>预览只存在内存里，10 分钟内有效；系统重启后需要重新发一次。改不了的细节（比如换一种分配方式）请到网页上登记。
 */
@Component
class BotEntries {

    static final Duration PENDING_TTL = Duration.ofMinutes(10);
    static final String VIA = "经企业微信机器人登记";
    private static final String CONFIRM_HINT = "\n\n回复“确认”登记，回复“取消”放弃。";
    private static final Pattern RECEIPT_WORDS = Pattern.compile(
        "收到|收入|入账|转入|存入|汇入|到账|来账|贷记|付款人|付款方|付方|对方户名|交易金额|流水号|回单|汇款"
    );
    private static final Map<String, String> RECEIPT_RULES = Map.of(
        "INVOICE",
        "金额和一张发票相同",
        "EXACT",
        "金额正好等于这些合同的未回款",
        "OLDEST",
        "从最早到期的合同开始冲抵"
    );
    private static final Map<String, String> INVOICE_RULES = Map.of(
        "REMARK",
        "发票上写了合同号",
        "EXACT",
        "金额正好等于这些合同的未开票金额",
        "OLDEST",
        "从最早的合同开始"
    );

    private record Pending(Instant at, Supplier<String> run) {}

    private final ProjectService projects;
    private final BotQueries queries;
    private final ReceiptBatchService receiptBatches;
    private final InvoiceEntryService invoiceEntries;
    private final Database db;
    private final Validator validator;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    BotEntries(
        ProjectService projects,
        BotQueries queries,
        ReceiptBatchService receiptBatches,
        InvoiceEntryService invoiceEntries,
        Database db,
        Validator validator
    ) {
        this.projects = projects;
        this.queries = queries;
        this.receiptBatches = receiptBatches;
        this.invoiceEntries = invoiceEntries;
        this.db = db;
        this.validator = validator;
    }

    // ===================== 确认 / 取消 =====================

    String confirm(String key) {
        var p = pending.remove(key);
        if (p == null) return "现在没有等待确认的内容。";
        if (p.at().plus(PENDING_TTL).isBefore(Instant.now())) return "这份预览已经超过 10 分钟，请重新发一次。";
        return p.run().get();
    }

    /** 这个人有没有还在有效期内、等待确认的预览。 */
    boolean waiting(String key) {
        var p = pending.get(key);
        return p != null && !p.at().plus(PENDING_TTL).isBefore(Instant.now());
    }

    String cancel(String key) {
        return pending.remove(key) == null ? "现在没有等待确认的内容。" : "已取消，什么都没有登记。";
    }

    private String hold(String key, String preview, Supplier<String> run) {
        pending.put(key, new Pending(Instant.now(), run));
        pending.values().removeIf(p -> p.at().plus(PENDING_TTL).isBefore(Instant.now()));
        return preview + CONFIRM_HINT;
    }

    /** 接口层的校验注解在这里同样执行（服务方法本身不校验）。 */
    private <T> T valid(T input) {
        var violations = validator.validate(input);
        if (!violations.isEmpty()) {
            var v = violations.iterator().next();
            throw new ApiException(v.getPropertyPath() + "：" + v.getMessage());
        }
        return input;
    }

    // ===================== 运单与收货信息 =====================

    static boolean looksLikeDelivery(String text) {
        var lines = DeliveryParser.trackingLines(text, ProjectService.COURIERS);
        if (lines.stream().anyMatch(l -> l.trackingNo() != null)) return true;
        var p = DeliveryParser.parse(text, ProjectService.COURIERS);
        return p.phone() != null && p.address() != null;
    }

    String delivery(String key, String text) {
        var stores = queries.dealtStores();
        var lines = DeliveryParser.trackingLines(text, ProjectService.COURIERS);
        boolean allTracking = !lines.isEmpty() && lines.stream().allMatch(l -> l.trackingNo() != null);
        return allTracking ? tracking(key, lines, stores) : consignee(key, text, stores);
    }

    private record Step(Map<String, Object> store, String courier, boolean guessed, String trackingNo) {}

    /** 每行一个门店 + 运单号：对上门店的登记为已发货，对不上的说明原因并跳过。 */
    private String tracking(String key, List<DeliveryParser.TrackingLine> lines, List<Map<String, Object>> stores) {
        var steps = new ArrayList<Step>();
        var problems = new ArrayList<String>();
        var seen = new HashSet<Object>();
        for (var l : lines) {
            if (l.hint().isBlank()) {
                problems.add("“" + l.line() + "”：没写是哪个门店");
                continue;
            }
            var hits = DeliveryParser.matchStores(l.hint(), stores);
            if (hits.isEmpty()) problems.add("“" + l.hint() + "”：没有找到这个门店");
            else if (hits.size() > 1) problems.add(
                "“" + l.hint() + "”：有 " + hits.size() + " 个门店名称相近，请写完整"
            );
            else if (!seen.add(hits.get(0).get("id"))) problems.add("“" + l.hint() + "”：这个门店出现了两次");
            else steps.add(new Step(hits.get(0), l.courier(), l.courierGuessed(), l.trackingNo()));
        }
        var skipped = new StringBuilder();
        if (!problems.isEmpty()) {
            skipped.append("\n没对上、不会登记：");
            for (String p : problems) skipped.append("\n· ").append(p);
        }
        if (steps.isEmpty()) return (
            "认出了运单号，但没有对上门店。" +
            skipped +
            "\n请按“门店名称 运单号”的格式每行写一个，门店名称可以只写后半段（如“盛泰站”）。"
        );
        var sb = new StringBuilder("将登记发货（" + steps.size() + " 个门店）：");
        for (var s : steps) {
            sb.append("\n· ").append(s.store().get("name")).append("：");
            sb.append(s.courier() == null ? "" : s.courier() + " ").append(s.trackingNo());
            String old = str(s.store().get("tracking_no"));
            if (s.guessed()) sb.append("（快递公司按单号推断）");
            if (!old.isEmpty() && !old.equals(s.trackingNo())) sb.append("（将替换原运单 ").append(old).append("）");
        }
        return hold(key, sb.append(skipped).toString(), () -> {
            int done = 0;
            var failed = new ArrayList<String>();
            for (var s : steps) {
                try {
                    saveDelivery(s.store(), null, null, null, s.courier(), s.trackingNo());
                    done++;
                } catch (RuntimeException e) {
                    failed.add(s.store().get("name") + "：" + BotService.reason(e));
                }
            }
            String out = "已为 " + done + " 个门店登记运单并标记已发货。";
            if (!failed.isEmpty()) out += "\n" + failed.size() + " 个没有成功：\n· " + String.join("\n· ", failed);
            return out;
        });
    }

    /** 一个门店的收货信息（可以同时带运单）：第一行或第一个词是门店名称。 */
    private String consignee(String key, String text, List<Map<String, Object>> stores) {
        String t = text.strip();
        int cut = t.indexOf('\n');
        if (cut < 0) {
            var m = Pattern.compile("\\s").matcher(t);
            cut = m.find() ? m.start() : -1;
        }
        String hint = cut < 0 ? t : t.substring(0, cut).strip(),
            rest = cut < 0 ? "" : t.substring(cut + 1);
        var p = DeliveryParser.parse(rest, ProjectService.COURIERS);
        var whole = DeliveryParser.parse(t, ProjectService.COURIERS);
        if (p.empty() && whole.empty()) return null;
        var hits = DeliveryParser.matchStores(hint, stores);
        if (p.empty() || hits.isEmpty()) return (
            "认出了收货信息，但不知道是哪个门店。请把门店名称单独写在第一行再发一次，例如：\n盛泰站\n张三 13800001111 成都市武侯区某某路 1 号"
        );
        if (hits.size() > 1) return (
            "有 " +
            hits.size() +
            " 个门店的名称和“" +
            hint +
            "”相近：" +
            String.join(
                "、",
                hits
                    .stream()
                    .limit(6)
                    .map(s -> str(s.get("name")))
                    .toList()
            ) +
            "。请把门店名称写完整。"
        );
        var store = hits.get(0);
        var sb = new StringBuilder("将更新 **" + store.get("name") + "** 的发货信息：");
        change(sb, "收货人", store.get("consignee"), p.consignee());
        change(sb, "电话", store.get("consignee_phone"), p.phone());
        change(sb, "地址", store.get("ship_address"), p.address());
        if (p.trackingNo() != null) {
            change(
                sb,
                "运单",
                store.get("tracking_no"),
                (p.courier() == null ? "" : p.courier() + " ") + p.trackingNo()
            );
            sb.append("\n并标记为已发货");
        }
        return hold(key, sb.toString(), () -> {
            saveDelivery(store, p.consignee(), p.phone(), p.address(), p.courier(), p.trackingNo());
            return "已更新 " + store.get("name") + " 的发货信息。";
        });
    }

    private static void change(StringBuilder sb, String label, Object before, String after) {
        if (after == null) return;
        sb.append("\n").append(label).append("：").append(after);
        String old = str(before);
        if (!old.isEmpty() && !old.equals(after)) sb.append("（原来是 ").append(old).append("）");
    }

    /** 只改这次认出来的内容，其余保持门店现在的值。 */
    private void saveDelivery(
        Map<String, Object> listed,
        String consignee,
        String phone,
        String address,
        String courier,
        String trackingNo
    ) {
        String id = listed.get("id").toString();
        // 预览之后门店可能被别人改过，按最新的值合并
        var now = db.one("SELECT * FROM project WHERE id=?", id);
        boolean shipped = trackingNo != null;
        projects.delivery(
            id,
            valid(
                new ProjectService.DeliveryInput(
                    shipped
                        ? Contracts.SHIPPED
                        : Objects.toString(Contracts.shipping((String) now.get("shipping_status")), ""),
                    date(now.get("expected_ship_on")),
                    date(now.get("shipped_on")),
                    shipped ? Objects.toString(courier, "") : str(now.get("courier")),
                    shipped ? trackingNo : str(now.get("tracking_no")),
                    consignee != null ? consignee : str(now.get("consignee")),
                    phone != null ? phone : str(now.get("consignee_phone")),
                    address != null ? address : str(now.get("ship_address"))
                )
            )
        );
    }

    // ===================== 回款 =====================

    static boolean looksLikeReceipt(String text) {
        return RECEIPT_WORDS.matcher(text).find() && ReceiptParser.parse(text, LocalDate.now()).amount() != null;
    }

    @SuppressWarnings("unchecked")
    String receipt(String key, String text) {
        var today = LocalDate.now();
        var p = ReceiptParser.parse(text, today);
        if (p.amount() == null) return "没有认出到账金额。请把银行短信或回单上的文字原样发给我。";
        var customers = db.jdbc.queryForList("SELECT id,name,invoice_title FROM partner WHERE kind='CUSTOMER'");
        var customer = p.payer() == null ? null : ReceiptParser.matchCustomer(p.payer(), customers);
        if (customer == null) customer = ReceiptParser.matchCustomer(text, customers);
        String got = "到账 " + money(p.amount()) + " 元" + (p.payer() == null ? "" : "，付款方 " + p.payer());
        if (customer == null) return (
            "认出了" + got + "，但对不上系统里的客户。请在消息最后加一行客户名称再发一次，例如：\n客户 中野"
        );
        String customerId = customer.get("id").toString();
        // 下面这次调用同时检查了角色：只有财务和管理员能登记回款
        var suggestion = receiptBatches.suggest(customerId, p.amount());
        if (
            p.reference() != null &&
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM receipt_batch WHERE reference=?",
                Integer.class,
                p.reference()
            ) >
            0
        ) return "流水号 " + p.reference() + " 的这笔回款已经登记过了。";
        LocalDate paidOn = p.paidOn() == null || p.paidOn().isAfter(today) ? today : p.paidOn();
        var shares = (List<Map<String, Object>>) suggestion.get("allocations");
        var allocations = shares
            .stream()
            .map(s -> new ReceiptBatchService.Allocation(s.get("contractKey").toString(), num(s.get("amount"))))
            .toList();
        BigDecimal unallocated = num(suggestion.get("unallocated"));

        var sb = new StringBuilder("将登记回款：");
        sb.append("\n客户：").append(customer.get("name"));
        sb.append("\n到账：").append(money(p.amount())).append(" 元，").append(paidOn);
        if (p.paidOn() == null) sb.append("（文字里没有日期，按今天）");
        if (p.payer() != null) sb.append("\n付款方：").append(p.payer());
        if (p.reference() != null) sb.append("\n流水号：").append(p.reference());
        if (allocations.isEmpty()) sb.append("\n这位客户目前没有未回款的合同，整笔先记为待分配。");
        else {
            sb
                .append("\n分到合同（")
                .append(RECEIPT_RULES.getOrDefault(str(suggestion.get("rule")), "建议"))
                .append("）：");
            for (var a : allocations) sb.append("\n· ").append(a.contractKey()).append("　").append(money(a.amount()));
            if (unallocated.signum() > 0) sb.append("\n待分配：").append(money(unallocated)).append(" 元");
        }
        var same = db.jdbc.queryForList(
            "SELECT reference FROM receipt_batch WHERE customer_id=? AND amount=? AND paid_on=?",
            String.class,
            customerId,
            p.amount(),
            paidOn
        );
        if (!same.isEmpty()) sb
            .append("\n⚠ 这位客户同一天已经有一笔同样金额的回款（")
            .append(same.get(0))
            .append("），请先确认不是重复。");
        sb.append("\n要换一种分法，请回复“取消”后到网页上登记。");
        var input = new ReceiptBatchService.BatchInput(
            customerId,
            p.amount(),
            paidOn,
            p.reference(),
            p.payer(),
            VIA,
            text.length() > 4000 ? text.substring(0, 4000) : text,
            allocations
        );
        return hold(key, sb.toString(), () -> {
            var r = receiptBatches.create(valid(input));
            String out =
                "已登记回款 " +
                r.get("reference") +
                "：到账 " +
                money(input.amount()) +
                " 元，分到 " +
                r.get("contracts") +
                " 份合同";
            return num(r.get("unallocated")).signum() > 0
                ? out + "，待分配 " + money(r.get("unallocated")) + " 元。"
                : out + "。";
        });
    }

    // ===================== 发票 =====================

    @SuppressWarnings("unchecked")
    String invoice(String key, String fileName, byte[] bytes) {
        var file = new BytesFile(fileName, "application/pdf", bytes);
        // 同时检查了角色：只有财务和管理员能登记发票
        var p = invoiceEntries.parse(file);
        String web = "请到网页的“登记发票”里手工填写。";
        if (!Boolean.TRUE.equals(p.get("recognized"))) return "这个 PDF 里没有认出发票内容（可能是扫描件）。" + web;
        if (Boolean.TRUE.equals(p.get("duplicate"))) return "发票 " + p.get("invoiceNo") + " 已经登记过了。";
        var missing = new ArrayList<String>();
        if (p.get("invoiceNo") == null) missing.add("发票号码");
        if (p.get("issuedOn") == null) missing.add("开票日期");
        if (p.get("amount") == null) missing.add("价税合计");
        if (!missing.isEmpty()) return "这张发票没有认出" + String.join("、", missing) + "。" + web;
        String head = "发票 " + p.get("invoiceNo") + "，价税合计 " + money(p.get("amount")) + " 元";
        if (p.get("customerId") == null) return (
            "认出了" + head + "，购买方“" + str(p.get("buyerName")) + "”对不上系统里的客户。" + web
        );
        String customerId = p.get("customerId").toString();
        var suggestion = (Map<String, Object>) p.get("suggestion");
        var shares =
            suggestion == null
                ? List.<Map<String, Object>>of()
                : (List<Map<String, Object>>) suggestion.get("allocations");
        if (shares.isEmpty() || num(suggestion.get("unallocated")).signum() > 0) return (
            "认出了" + head + "，但这位客户还没开票的合同金额不够记这张发票。请到网页上核对后登记。"
        );
        var allocations = shares
            .stream()
            .map(s -> new InvoiceEntryService.Allocation(s.get("contractKey").toString(), num(s.get("amount"))))
            .toList();
        String customerName = db.one("SELECT name FROM partner WHERE id=?", customerId).get("name").toString();
        var sb = new StringBuilder("将登记发票：");
        sb.append("\n发票号码：").append(p.get("invoiceNo"));
        sb.append("\n开票日期：").append(p.get("issuedOn"));
        sb.append("\n价税合计：").append(money(p.get("amount"))).append(" 元");
        sb.append("\n客户：").append(customerName);
        sb
            .append("\n记到合同（")
            .append(INVOICE_RULES.getOrDefault(str(suggestion.get("rule")), "建议"))
            .append("）：");
        for (var a : allocations) sb.append("\n· ").append(a.contractKey()).append("　").append(money(a.amount()));
        sb.append("\n发票 PDF 会存进这些合同的附件。要换一种分法，请回复“取消”后到网页上登记。");
        var input = new InvoiceEntryService.EntryInput(
            customerId,
            p.get("invoiceNo").toString(),
            Objects.toString(p.get("invoiceType"), ""),
            (LocalDate) p.get("issuedOn"),
            num(p.get("amount")),
            (BigDecimal) p.get("taxRate"),
            VIA,
            allocations
        );
        return hold(key, sb.toString(), () -> {
            var r = invoiceEntries.create(valid(input), file);
            return "已登记发票 " + input.invoiceNo() + "，记到 " + r.get("contracts") + " 份合同，PDF 已存入合同附件。";
        });
    }
}
