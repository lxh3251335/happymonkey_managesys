package cn.tianshang.commerce.excel;

import static cn.tianshang.commerce.excel.ExcelService.*;
import static cn.tianshang.commerce.excel.Rows.*;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.contract.ContractExecutionService;
import cn.tianshang.commerce.contract.ContractService;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.excel.ExcelService.Dataset;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import cn.tianshang.commerce.finance.ReceiptBatchService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 回款和成本的 Excel 导入导出。
 * 回款：一行是“一笔到账分到一份合同”的金额；同一客户、到账日期、流水号的行合成一笔到账，没分完的余额留作待分配。
 * 成本：一行一笔实际成本；已登记过完全相同的一笔时跳过。
 */
@Component
public class FinanceExcel {

    private final Database db;
    private final ReceiptBatchService receipts;
    private final ContractService contracts;
    private final ContractExecutionService execution;
    private final Validator validator;

    public FinanceExcel(
        Database db,
        ReceiptBatchService receipts,
        ContractService contracts,
        ContractExecutionService execution,
        Validator validator
    ) {
        this.db = db;
        this.receipts = receipts;
        this.contracts = contracts;
        this.execution = execution;
        this.validator = validator;
    }

    // ===================== 列 =====================

    List<Column> receiptColumns() {
        return List.of(
            Column.of("customer", "客户", 24).required(),
            Column.of("paid_on", "到账日期", 12).required().note("如 2026-03-01"),
            Column.of("amount", "到账金额", 12)
                .number()
                .required()
                .note("这笔到账的总金额；分到多份合同时每行都写同样的总金额"),
            Column.of("reference", "流水号", 20).note(
                "银行流水号或凭证号；同一笔到账的各行相同。留空时系统自动编号，且无法识别重复导入"
            ),
            Column.of("payer", "付款方", 20),
            Column.of("contract_no", "合同号", 20).note(
                "这一行分到哪份合同；留空为待分配。没有合同号的单店订单填 #门店编码（见合同台账导出）"
            ),
            Column.of("allocated", "分到该合同金额", 14).number().note("填了合同号时必填"),
            Column.of("note", "备注", 24)
        );
    }

    List<Column> costColumns() {
        return List.of(
            Column.of("customer", "客户", 24).required(),
            Column.of("contract_no", "合同号", 20)
                .required()
                .note("没有合同号的单店订单填 #门店编码（见合同台账导出）"),
            Column.of("category", "成本类别", 10)
                .required()
                .options(ContractExecutionService.COST_CATEGORIES.values().toArray(String[]::new)),
            Column.of("supplier", "供应商", 18).note("已登记的供应商名称，可留空"),
            Column.of("amount", "金额", 12).number().required(),
            Column.of("incurred_on", "发生日期", 12).required(),
            Column.of("reference", "单据号", 20).note("发票号、付款凭证号等"),
            Column.of("note", "备注", 24)
        );
    }

    // ===================== 导出 =====================

    List<Map<String, Object>> receiptRows() {
        var out = new ArrayList<Map<String, Object>>();
        for (var b : receipts.list()) {
            var base = new HashMap<String, Object>();
            base.put("customer", b.get("customer_name"));
            base.put("paid_on", day(b.get("paid_on")));
            base.put("amount", b.get("amount"));
            base.put("reference", b.get("reference"));
            base.put("payer", b.get("payer"));
            base.put("note", b.get("note"));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) b.get("items");
            var allocations = items
                .stream()
                .filter(i -> "RECEIPT".equals(i.get("kind")))
                .toList();
            if (allocations.isEmpty()) out.add(base);
            for (var i : allocations) {
                var r = new HashMap<String, Object>(base);
                r.put("contract_no", i.get("contract_no"));
                r.put("allocated", i.get("amount"));
                out.add(r);
            }
        }
        return out;
    }

    List<Map<String, Object>> costRows() {
        var out = new ArrayList<Map<String, Object>>();
        for (var c : db.jdbc.queryForList(
            """
            SELECT k.name AS customer,c.contract_no,c.category,s.name AS supplier,c.amount,c.incurred_on,c.reference,c.note
            FROM contract_cost c JOIN partner k ON k.id=c.customer_id LEFT JOIN partner s ON s.id=c.supplier_id
            ORDER BY c.incurred_on DESC,c.created_at DESC
            """
        )) {
            var r = new HashMap<String, Object>(c);
            r.put(
                "category",
                ContractExecutionService.COST_CATEGORIES.getOrDefault(text(c, "category"), text(c, "category"))
            );
            r.put("incurred_on", day(c.get("incurred_on")));
            out.add(r);
        }
        return out;
    }

    private static String day(Object v) {
        String s = Objects.toString(v, "");
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    // ===================== 导入 =====================

    List<Op> plan(Dataset d, ExcelIo.Sheet sheet) {
        return d == Dataset.RECEIPTS ? planReceipts(sheet) : planCosts(sheet);
    }

    /** 客户编号 → 该客户的合同 key 集合（合同号，或无合同号订单的 #门店编码）。 */
    private Map<String, Set<String>> contractKeys() {
        var out = new HashMap<String, Set<String>>();
        for (var c : contracts.contracts())
            out
                .computeIfAbsent(c.get("customer_id").toString(), k -> new HashSet<>())
                .add(c.get("contract_key").toString());
        return out;
    }

    private List<Op> planReceipts(ExcelIo.Sheet sheet) {
        var customers = BundleExcel.partners(db, "CUSTOMER");
        var keys = contractKeys();
        var known = new HashSet<String>(db.jdbc.queryForList("SELECT reference FROM receipt_batch", String.class));
        var groups = group(
            sheet.rows(),
            r ->
                r.get("customer").trim() +
                "\u0000" +
                r.get("paid_on").trim() +
                "\u0000" +
                (r.get("reference").isBlank()
                    ? "@" + r.get("amount").trim() + "@" + r.get("payer").trim()
                    : r.get("reference").trim())
        );
        var ops = new ArrayList<Op>();
        var seenRefs = new HashMap<String, Integer>();
        for (var g : groups.values()) {
            var first = g.get(0);
            var errors = new ArrayList<String>();
            String customerName = first.get("customer").trim();
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”不存在，请先登记或导入客户资料");
            LocalDate paidOn = null;
            BigDecimal amount = null;
            try {
                paidOn = ExcelIo.date(first.get("paid_on"), "到账日期");
                if (paidOn == null) errors.add("到账日期必填");
                amount = ExcelIo.decimal(first.get("amount"), "到账金额");
                if (amount == null || amount.signum() <= 0) errors.add("到账金额必须大于 0");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            String reference = blank(first.get("reference"));
            if (reference != null && seenRefs.put(reference, first.number()) != null) errors.add(
                "流水号与第 " + seenRefs.get(reference) + " 行重复但到账日期或客户不同"
            );
            var allocations = new ArrayList<ReceiptBatchService.Allocation>();
            var seenContracts = new HashSet<String>();
            BigDecimal allocated = BigDecimal.ZERO;
            for (var r : g) {
                if (!r.get("amount").trim().equals(first.get("amount").trim())) errors.add(
                    at(r) + "：到账金额与第 " + first.number() + " 行不一致（同一笔到账各行应写同样的总金额）"
                );
                String key = blank(r.get("contract_no"));
                if (key == null) {
                    if (!r.get("allocated").isBlank()) errors.add(at(r) + "：填了分配金额但没有合同号");
                    continue;
                }
                if (customerId != null && !keys.getOrDefault(customerId, Set.of()).contains(key)) errors.add(
                    at(r) + "：客户“" + customerName + "”没有合同“" + key + "”（请先导入项目台账或合同台账）"
                );
                if (!seenContracts.add(key)) errors.add(at(r) + "：合同“" + key + "”在同一笔到账里重复");
                try {
                    var a = ExcelIo.decimal(r.get("allocated"), "分到该合同金额");
                    if (a == null || a.signum() <= 0) errors.add(at(r) + "：分到该合同金额必须大于 0");
                    else {
                        allocations.add(new ReceiptBatchService.Allocation(key, a));
                        allocated = allocated.add(a);
                    }
                } catch (ApiException e) {
                    errors.add(at(r) + "：" + e.getMessage());
                }
            }
            if (amount != null && allocated.compareTo(amount) > 0) errors.add(
                "分到各合同的金额合计 " +
                    allocated.toPlainString() +
                    " 元，超过了到账金额 " +
                    amount.toPlainString() +
                    " 元"
            );
            var input = new ReceiptBatchService.BatchInput(
                customerId == null ? "" : customerId,
                amount,
                paidOn,
                reference,
                blank(first.get("payer")),
                blank(first.get("note")),
                null,
                allocations
            );
            validate(
                validator,
                input,
                errors,
                Map.of("amount", "到账金额", "paidOn", "到账日期", "reference", "流水号", "payer", "付款方")
            );
            String rows = span(g);
            String label =
                customerName +
                " " +
                (paidOn == null ? "" : paidOn) +
                " 到账 " +
                (amount == null ? "?" : amount.toPlainString()) +
                " 元，分到 " +
                allocations.size() +
                " 份合同";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (reference != null && known.contains(reference)) {
                ops.add(
                    new Op(new Result(rows, SAME, label, List.of("流水号 " + reference + " 已登记过，跳过")), null)
                );
                continue;
            }
            var notes = new ArrayList<String>();
            BigDecimal left = amount.subtract(allocated);
            if (left.signum() > 0) notes.add("未分配 " + left.toPlainString() + " 元留作待分配");
            if (reference == null) notes.add("没有流水号，系统自动编号；重复导入会重复登记");
            ops.add(new Op(new Result(rows, CREATE, label, notes), () -> receipts.create(input)));
        }
        return ops;
    }

    private List<Op> planCosts(ExcelIo.Sheet sheet) {
        var customers = BundleExcel.partners(db, "CUSTOMER");
        var suppliers = BundleExcel.partners(db, "SUPPLIER");
        var keys = contractKeys();
        var categories = new HashMap<String, String>();
        ContractExecutionService.COST_CATEGORIES.forEach((code, name) -> {
            categories.put(name, code);
            categories.put(code, code);
        });
        var known = new HashSet<String>();
        for (var c : db.jdbc.queryForList(
            "SELECT customer_id,contract_no,category,amount,incurred_on,reference FROM contract_cost"
        ))
            known.add(
                costKey(
                    c.get("customer_id").toString(),
                    text(c, "contract_no"),
                    text(c, "category"),
                    decimal(c, "amount"),
                    day(c.get("incurred_on")),
                    blank(text(c, "reference"))
                )
            );
        var ops = new ArrayList<Op>();
        for (var r : sheet.rows()) {
            var errors = new ArrayList<String>();
            String customerName = r.get("customer").trim();
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”不存在，请先登记或导入客户资料");
            String key = blank(r.get("contract_no"));
            if (key == null) errors.add("合同号必填");
            else if (customerId != null && !keys.getOrDefault(customerId, Set.of()).contains(key)) errors.add(
                "客户“" + customerName + "”没有合同“" + key + "”（请先导入项目台账或合同台账）"
            );
            String category = categories.get(r.get("category").trim());
            if (category == null) errors.add(
                "成本类别只能是：" + String.join(" / ", ContractExecutionService.COST_CATEGORIES.values())
            );
            String supplierId = null;
            if (!r.get("supplier").isBlank()) {
                supplierId = suppliers.get(r.get("supplier").trim());
                if (supplierId == null) errors.add("供应商“" + r.get("supplier").trim() + "”不存在");
            }
            BigDecimal amount = null;
            LocalDate incurredOn = null;
            try {
                amount = ExcelIo.decimal(r.get("amount"), "金额");
                incurredOn = ExcelIo.date(r.get("incurred_on"), "发生日期");
                if (incurredOn == null) errors.add("发生日期必填");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            var input = new ContractExecutionService.CostInput(
                customerId == null ? "" : customerId,
                key == null ? "" : key,
                category == null ? "OTHER" : category,
                supplierId,
                amount,
                incurredOn,
                blank(r.get("reference")),
                blank(r.get("note"))
            );
            validate(
                validator,
                input,
                errors,
                Map.of("amount", "金额", "incurredOn", "发生日期", "reference", "单据号", "note", "备注")
            );
            String label =
                customerName +
                " / " +
                (key == null ? "" : key) +
                " · " +
                r.get("category").trim() +
                " " +
                (amount == null ? "" : amount.toPlainString() + " 元");
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(at(r), ERROR, label, errors), null));
                continue;
            }
            String k = costKey(customerId, key, category, amount, incurredOn.toString(), blank(r.get("reference")));
            if (!known.add(k)) {
                ops.add(new Op(new Result(at(r), SAME, label, List.of("已登记过相同的一笔成本，跳过")), null));
                continue;
            }
            ops.add(new Op(new Result(at(r), CREATE, label, List.of()), () -> execution.cost(input)));
        }
        return ops;
    }

    private static String costKey(
        String customerId,
        String contractNo,
        String category,
        BigDecimal amount,
        String day,
        String reference
    ) {
        return String.join(
            "\u0000",
            customerId,
            contractNo,
            category,
            amount == null ? "" : amount.stripTrailingZeros().toPlainString(),
            day,
            reference == null ? "" : reference
        );
    }
}
