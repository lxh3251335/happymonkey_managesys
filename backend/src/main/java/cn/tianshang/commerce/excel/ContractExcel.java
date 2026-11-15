package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.*;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 合同台账与开票记录的 Excel 导入导出。
 *
 * <p>合同台账：按“客户 + 合同号”匹配，更新合同要素（签约主体、合同内容、签订日期、签约金额、条款、负责人等），
 * 匹配不到则新建合同；门店订单、开票、到款等统计列只导出、不导入。导入由管理员执行，每份合同的修改记入合同变更记录。
 * 开票记录：按发票号码匹配，只新增未登记的发票，已登记的发票不能通过导入修改。
 */
@Component
public class ContractExcel {

    static final String STATS = "统计值，仅供查看，导入时忽略";
    private static final Map<String, String> STATUS_BY_NAME = Map.of(
        "拟定中",
        "DRAFT",
        "已签订",
        "SIGNED",
        "已完结",
        "COMPLETED",
        "已终止",
        "TERMINATED"
    );

    private final Database db;
    private final ContractService contracts;
    private final ContractRecordService records;
    private final ContractExecutionService execution;
    private final cn.tianshang.commerce.dictionary.DictionaryService dictionary;

    public ContractExcel(
        Database db,
        ContractService contracts,
        ContractRecordService records,
        ContractExecutionService execution,
        cn.tianshang.commerce.dictionary.DictionaryService dictionary
    ) {
        this.db = db;
        this.contracts = contracts;
        this.records = records;
        this.execution = execution;
        this.dictionary = dictionary;
    }

    // ===================== 列 =====================

    List<Column> contractColumns() {
        var companies = dictionary
            .companies()
            .stream()
            .filter(c -> Boolean.TRUE.equals(c.get("active")))
            .map(c -> db.text(c, "name"))
            .toArray(String[]::new);
        return List.of(
            Column.of("customer", "客户", 18).required().note("填写已登记的客户名称"),
            Column.of("contract_no", "合同号", 20)
                .required()
                .note("以 # 开头的是未填合同号的单店订单（# + 项目编号），请勿修改"),
            Column.of("status", "合同状态", 10).options("拟定中", "已签订", "已完结", "已终止"),
            Column.of("company", "我方签约主体", 22)
                .note("在系统管理 → 签约主体中维护；可填名称或简称")
                .options(companies),
            Column.of("title", "合同名称", 26),
            Column.of("content", "合同内容", 36).note("标的、供货范围等摘要"),
            Column.of("signed_on", "签订日期", 12).note("如 2026-01-05"),
            Column.of("signed_amount", "签约金额", 14).number().note("留空则以门店订单合计作为合同金额"),
            Column.of("payment_terms", "付款条款", 30),
            Column.of("start_on", "履约开始", 12),
            Column.of("end_on", "履约结束", 12),
            Column.of("warranty_months", "质保期（月）", 10).number(),
            Column.of("retention_amount", "质保金", 12).number(),
            Column.of("owner", "销售负责人", 12).note("填写账户显示名称"),
            Column.of("customer_signer", "客户签约人", 12),
            Column.of("archived", "原件已归档", 10).options("是", "否"),
            Column.of("archive_no", "归档编号", 14),
            Column.of("note", "备注", 24),
            Column.of("brands", "业务线", 14).note(STATS),
            Column.of("cities", "城市", 10).note(STATS),
            Column.of("projects", "门店数", 8).number().note(STATS),
            Column.of("shipped", "已发货门店", 10).number().note(STATS),
            Column.of("order_amount", "门店订单金额", 14).number().note(STATS),
            Column.of("amount", "合同金额", 14).number().note(STATS),
            Column.of("invoiced", "已开票", 14).number().note(STATS),
            Column.of("received", "已到款", 14).number().note(STATS),
            Column.of("outstanding", "未收应收", 14).number().note(STATS),
            Column.of("overdue", "逾期金额", 12).number().note(STATS),
            Column.of("cost", "成本", 14).number().note(STATS + "；有实际成本用实际成本，否则用报价预估成本"),
            Column.of("gross_margin", "毛利率", 10).number().note(STATS)
        );
    }

    List<Column> invoiceColumns() {
        return List.of(
            Column.of("customer", "客户", 18).required(),
            Column.of("contract_no", "合同号", 20).required(),
            Column.of("invoice_no", "发票号码", 24)
                .required()
                .note("发票号码不能重复；已登记的发票导入时跳过，不能修改"),
            Column.of("kind", "类型", 8).options("蓝字", "红字").note("留空为蓝字"),
            Column.of("reverses_no", "原发票号码", 24).note("红字发票必填：冲销的蓝字发票号码"),
            Column.of("invoice_type", "发票类型", 10).options("专票", "普票").note("留空按客户资料"),
            Column.of("issued_on", "开票日期", 12).required(),
            Column.of("amount", "价税合计", 14).required().number(),
            Column.of("tax_rate", "税率", 8).note("如 13% 或 0.13"),
            Column.of("note", "备注", 24),
            Column.of("company", "开票方", 22).note(STATS)
        );
    }

    // ===================== 导出 =====================

    List<Map<String, Object>> contractRows() {
        var out = new ArrayList<Map<String, Object>>();
        var users = new HashMap<String, String>();
        for (var u : db.jdbc.queryForList("SELECT id,display_name FROM app_user"))
            users.put(u.get("id").toString(), db.text(u, "display_name"));
        var headers = new HashMap<String, Map<String, Object>>();
        for (var h : db.jdbc.queryForList(
            "SELECT k.*,m.name AS company_full FROM contract k LEFT JOIN company m ON m.id=k.company_id"
        ))
            headers.put(h.get("customer_id") + "::" + h.get("contract_no"), h);
        for (var c : contracts.contracts()) {
            var r = new HashMap<String, Object>(c);
            var h = headers.get(c.get("customer_id") + "::" + c.get("contract_key"));
            r.put("customer", c.get("customer_name"));
            r.put("contract_no", c.get("contract_key"));
            r.put("status", ContractService.STATUS.get(c.get("status").toString()));
            r.put("company", h == null ? null : h.get("company_full"));
            r.put("owner", c.get("owner_id") == null ? null : users.get(c.get("owner_id").toString()));
            r.put("archived", Boolean.TRUE.equals(c.get("archived")) ? "是" : "否");
            if (h != null) for (String k : List.of(
                "payment_terms",
                "start_on",
                "end_on",
                "warranty_months",
                "retention_amount",
                "customer_signer",
                "archive_no",
                "note"
            ))
                r.put(k, text(h.get(k)));
            r.put("signed_on", text(c.get("signed_on")));
            r.put("brands", String.join("、", strings(c.get("brands"))));
            r.put("cities", String.join("、", strings(c.get("cities"))));
            out.add(r);
        }
        return out;
    }

    List<Map<String, Object>> invoiceRows() {
        var out = new ArrayList<Map<String, Object>>();
        for (var i : execution.invoices(null, null)) {
            var r = new HashMap<String, Object>(i);
            r.put("customer", i.get("customer_name"));
            r.put("kind", "RED".equals(i.get("kind")) ? "红字" : "蓝字");
            r.put("reverses_no", i.get("reverses_no"));
            r.put("invoice_type", ExcelService.invoiceTypeName(db.text(i, "invoice_type")));
            r.put("issued_on", text(i.get("issued_on")));
            r.put(
                "tax_rate",
                i.get("tax_rate") == null
                    ? null
                    : new BigDecimal(i.get("tax_rate").toString())
                          .movePointRight(2)
                          .stripTrailingZeros()
                          .toPlainString() +
                      "%"
            );
            r.put("company", i.get("company_name"));
            out.add(r);
        }
        return out;
    }

    // ===================== 导入：合同台账 =====================

    List<Op> planContracts(ExcelIo.Sheet sheet) {
        var customers = customers();
        var owners = new HashMap<String, List<String>>();
        for (var u : db.jdbc.queryForList("SELECT id,display_name FROM app_user WHERE role IN ('ADMIN','SALES')"))
            owners.computeIfAbsent(db.text(u, "display_name"), k -> new ArrayList<>()).add(u.get("id").toString());
        var known = new HashSet<String>();
        for (var c : contracts.contracts()) known.add(c.get("customer_id") + "::" + c.get("contract_key"));
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            var errors = new ArrayList<String>();
            String customerName = row.get("customer"),
                no = row.get("contract_no");
            String label = customerName + " / " + (no.isEmpty() ? "（未填合同号）" : no);
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”未登记");
            if (no.isEmpty()) errors.add("合同号必填");
            String key = customerId + "::" + no;
            if (customerId != null && !no.isEmpty() && seen.put(key, row.number()) != null) errors.add(
                "与第 " + seen.get(key) + " 行是同一份合同"
            );
            boolean exists = customerId != null && known.contains(key);
            if (!exists && no.startsWith("#")) errors.add("以 # 开头的合同不存在，新建合同请填写真实合同号");
            Map<String, Object> current = customerId == null ? null : header(customerId, no);
            if (current == null && exists) current = records.baseline(customerId, no);
            ContractRecordService.Info info = null;
            try {
                info = info(sheet, row, current, owners);
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            String rows = "第 " + row.number() + " 行";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ExcelService.ERROR, label, errors), null));
                continue;
            }
            final var input = info;
            final String cid = customerId;
            try {
                var changes = records.previewChanges(customerId, no, input);
                if (!exists) {
                    ops.add(
                        new Op(new Result(rows, ExcelService.CREATE, label, List.of("新建合同")), () ->
                            records.create(new ContractRecordService.CreateInput(cid, no, input))
                        )
                    );
                } else if (changes.isEmpty()) ops.add(
                    new Op(new Result(rows, ExcelService.SAME, label, List.of()), null)
                );
                else ops.add(new Op(new Result(
                        rows,
                        ExcelService.UPDATE,
                        label,
                        List.of(
                            "变更：" +
                                String.join(
                                    "、",
                                    changes
                                        .stream()
                                        .map(c -> c.get("label").toString())
                                        .toList()
                                )
                        )
                    ), () -> records.update(cid, no, new ContractRecordService.UpdateInput(input, "Excel 批量导入"))));
            } catch (ApiException e) {
                ops.add(new Op(new Result(rows, ExcelService.ERROR, label, List.of(e.getMessage())), null));
            }
        }
        return ops;
    }

    /** 由表格行与当前合同记录合成合同要素：表中有的列取表中值，没有的列保持原值。 */
    private ContractRecordService.Info info(
        ExcelIo.Sheet sheet,
        Row row,
        Map<String, Object> cur,
        Map<String, List<String>> owners
    ) {
        java.util.function.Function<String, String> v = k ->
            sheet.present().contains(k) ? row.get(k) : cur == null ? "" : Objects.toString(text(cur.get(k)), "");
        String status;
        if (sheet.present().contains("status") && !row.get("status").isEmpty()) {
            status = STATUS_BY_NAME.get(row.get("status"));
            if (status == null) throw new ApiException("合同状态只能填 拟定中 / 已签订 / 已完结 / 已终止");
        } else status = cur == null ? "SIGNED" : cur.get("status").toString();
        String companyId;
        if (sheet.present().contains("company")) {
            companyId = dictionary.companyByName(row.get("company"));
            if (companyId == null && !row.get("company").isEmpty()) throw new ApiException(
                "签约主体“" + row.get("company") + "”不存在"
            );
        } else companyId = cur == null ? null : (String) cur.get("company_id");
        String ownerId;
        if (sheet.present().contains("owner")) {
            String name = row.get("owner");
            if (name.isEmpty()) ownerId = null;
            else {
                var ids = owners.getOrDefault(name, List.of());
                if (ids.isEmpty()) throw new ApiException("销售负责人“" + name + "”不是商务或管理员账户");
                if (ids.size() > 1) throw new ApiException("有多个账户叫“" + name + "”，请先在用户管理中区分显示名称");
                ownerId = ids.get(0);
            }
        } else ownerId = cur == null ? null : (String) cur.get("owner_id");
        String warranty = v.apply("warranty_months");
        Integer months;
        try {
            months = warranty.isEmpty() ? null : new BigDecimal(warranty).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiException("质保期（月）须为整数");
        }
        boolean archived = sheet.present().contains("archived")
            ? ExcelIo.yes(row.get("archived"))
            : cur != null && Boolean.TRUE.equals(cur.get("archived"));
        return new ContractRecordService.Info(
            status,
            companyId,
            v.apply("title"),
            v.apply("content"),
            ExcelIo.date(v.apply("signed_on"), "签订日期"),
            ExcelIo.decimal(v.apply("signed_amount"), "签约金额"),
            v.apply("payment_terms"),
            ExcelIo.date(v.apply("start_on"), "履约开始"),
            ExcelIo.date(v.apply("end_on"), "履约结束"),
            months,
            ExcelIo.decimal(v.apply("retention_amount"), "质保金"),
            ownerId,
            v.apply("customer_signer"),
            archived,
            v.apply("archive_no"),
            v.apply("note")
        );
    }

    // ===================== 导入：开票记录 =====================

    List<Op> planInvoices(ExcelIo.Sheet sheet) {
        var customers = customers();
        var byKey = new HashMap<String, Map<String, Object>>();
        for (var c : contracts.contracts()) byKey.put(c.get("customer_id") + "::" + c.get("contract_key"), c);
        var registered = new HashMap<String, Map<String, Object>>();
        for (var i : db.jdbc.queryForList("SELECT * FROM contract_invoice WHERE deleted_at IS NULL"))
            registered.put(db.text(i, "invoice_no"), i);
        // 本批次内累计：合同已开票、蓝字发票已冲销
        var invoicedDelta = new HashMap<String, BigDecimal>();
        var blueInBatch = new HashMap<String, BigDecimal[]>(); // 发票号 → [金额, 已冲销]
        var keyOfBlue = new HashMap<String, String>();
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            var errors = new ArrayList<String>();
            String customerName = row.get("customer"),
                no = row.get("contract_no"),
                invoiceNo = row.get("invoice_no").replaceAll("\\s", "");
            String label = invoiceNo + " / " + customerName + " " + no;
            String rows = "第 " + row.number() + " 行";
            String customerId = customers.get(customerName);
            if (customerId == null) errors.add(
                customerName.isEmpty() ? "客户必填" : "客户“" + customerName + "”未登记"
            );
            String key = customerId + "::" + no;
            var contract = byKey.get(key);
            if (customerId != null && contract == null) errors.add("合同“" + no + "”不存在");
            if (invoiceNo.isEmpty()) errors.add("发票号码必填");
            if (!invoiceNo.isEmpty() && seen.put(invoiceNo, row.number()) != null) errors.add(
                "与第 " + seen.get(invoiceNo) + " 行发票号码重复"
            );
            String kind =
                row.get("kind").isEmpty() || row.get("kind").equals("蓝字")
                    ? "INVOICE"
                    : row.get("kind").equals("红字")
                        ? "RED"
                        : null;
            if (kind == null) errors.add("类型只能填“蓝字”或“红字”");
            LocalDate issued = null;
            BigDecimal amount = null,
                rate = null;
            try {
                issued = ExcelIo.date(row.get("issued_on"), "开票日期");
                if (issued == null) errors.add("开票日期必填");
                else if (issued.isAfter(LocalDate.now())) errors.add("开票日期不能晚于今天");
                amount = ExcelIo.decimal(row.get("amount"), "价税合计");
                if (amount == null || amount.signum() <= 0) errors.add("价税合计须为正数");
                else if (amount.scale() > 2) errors.add("价税合计最多两位小数");
                String r = row.get("tax_rate").replace("%", "").trim();
                if (!r.isEmpty()) {
                    rate = new BigDecimal(r);
                    if (row.get("tax_rate").contains("%") || rate.compareTo(BigDecimal.ONE) > 0) rate =
                        rate.movePointLeft(2);
                    if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) errors.add("税率不正确");
                }
            } catch (ApiException e) {
                errors.add(e.getMessage());
            } catch (NumberFormatException e) {
                errors.add("税率不是数字");
            }
            String type = ExcelService.invoiceTypeCode(row.get("invoice_type"));
            if (type == null && !row.get("invoice_type").isEmpty()) errors.add("发票类型只能填“专票”或“普票”");
            var existing = registered.get(invoiceNo);
            if (existing != null && errors.isEmpty()) {
                boolean same =
                    existing.get("customer_id").equals(customerId) &&
                    existing.get("contract_no").equals(no) &&
                    amount != null &&
                    new BigDecimal(existing.get("amount").toString()).compareTo(amount) == 0;
                ops.add(
                    same
                        ? new Op(new Result(rows, ExcelService.SAME, label, List.of("已登记")), null)
                        : new Op(
                              new Result(
                                  rows,
                                  ExcelService.ERROR,
                                  label,
                                  List.of("该发票号码已登记，且合同或金额与表中不同；已登记发票不能通过导入修改")
                              ),
                              null
                          )
                );
                continue;
            }
            String reversesNo = row.get("reverses_no").replaceAll("\\s", "");
            if (errors.isEmpty()) {
                if ("INVOICE".equals(kind)) {
                    if (!reversesNo.isEmpty()) errors.add("蓝字发票不填原发票号码");
                    BigDecimal total = (BigDecimal) contract.get("amount");
                    BigDecimal used = ((BigDecimal) contract.get("invoiced")).add(
                        invoicedDelta.getOrDefault(key, BigDecimal.ZERO)
                    );
                    if (total.signum() <= 0) errors.add("合同金额为零，请先确认门店订单或填写签约金额");
                    else if (used.add(amount).compareTo(total) > 0) errors.add(
                        "开票超过合同未开票金额 " + total.subtract(used).toPlainString() + " 元"
                    );
                    else {
                        invoicedDelta.merge(key, amount, BigDecimal::add);
                        blueInBatch.put(invoiceNo, new BigDecimal[] { amount, BigDecimal.ZERO });
                        keyOfBlue.put(invoiceNo, key);
                    }
                } else {
                    if (reversesNo.isEmpty()) errors.add("红字发票须填写原发票号码");
                    else {
                        var original = registered.get(reversesNo);
                        BigDecimal[] left;
                        String originalKey;
                        if (original != null && "INVOICE".equals(original.get("kind"))) {
                            BigDecimal reversed = db.jdbc.queryForObject(
                                "SELECT COALESCE(SUM(amount),0) FROM contract_invoice WHERE reverses_id=? AND deleted_at IS NULL",
                                BigDecimal.class,
                                original.get("id")
                            );
                            left = blueInBatch.computeIfAbsent(reversesNo, k ->
                                new BigDecimal[] { new BigDecimal(original.get("amount").toString()), reversed }
                            );
                            originalKey = original.get("customer_id") + "::" + original.get("contract_no");
                        } else {
                            left = blueInBatch.get(reversesNo);
                            originalKey = keyOfBlue.get(reversesNo);
                        }
                        if (left == null) errors.add("原发票 " + reversesNo + " 未登记");
                        else if (!key.equals(originalKey)) errors.add("原发票不属于该合同");
                        else if (left[1].add(amount).compareTo(left[0]) > 0) errors.add(
                            "红字金额超过原发票未冲销金额 " + left[0].subtract(left[1]).toPlainString() + " 元"
                        );
                        else {
                            left[1] = left[1].add(amount);
                            invoicedDelta.merge(key, amount.negate(), BigDecimal::add);
                        }
                    }
                }
            }
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ExcelService.ERROR, label, errors), null));
                continue;
            }
            final String cid = customerId,
                k = kind,
                t = type,
                rn = reversesNo,
                note = row.get("note");
            final LocalDate d = issued;
            final BigDecimal a = amount,
                tr = rate;
            ops.add(
                new Op(
                    new Result(
                        rows,
                        ExcelService.CREATE,
                        label,
                        List.of("RED".equals(kind) ? "红字冲销 " + reversesNo : "蓝字发票")
                    ),
                    () -> {
                        String reverses = null;
                        if ("RED".equals(k)) reverses = db.jdbc.queryForObject(
                            "SELECT id FROM contract_invoice WHERE invoice_no=? AND deleted_at IS NULL",
                            String.class,
                            rn
                        );
                        execution.invoice(
                            new ContractExecutionService.InvoiceInput(
                                cid,
                                no,
                                k,
                                invoiceNo,
                                t,
                                d,
                                a,
                                tr,
                                reverses,
                                note
                            )
                        );
                    }
                )
            );
        }
        return ops;
    }

    // ===================== 工具 =====================

    private Map<String, String> customers() {
        var out = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT id,name FROM partner WHERE kind='CUSTOMER'"))
            out.put(db.text(p, "name").trim(), p.get("id").toString());
        return out;
    }

    private Map<String, Object> header(String customerId, String no) {
        var rows = db.jdbc.queryForList("SELECT * FROM contract WHERE customer_id=? AND contract_no=?", customerId, no);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String text(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        String s = v.toString();
        return s.matches("\\d{4}-\\d{2}-\\d{2}.*") ? s.substring(0, 10) : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }
}
