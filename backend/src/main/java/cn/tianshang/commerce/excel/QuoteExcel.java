package cn.tianshang.commerce.excel;

import static cn.tianshang.commerce.excel.ExcelService.*;
import static cn.tianshang.commerce.excel.Rows.*;

import cn.tianshang.commerce.catalog.CatalogService;
import cn.tianshang.commerce.catalog.CustomerTemplateService;
import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.dictionary.DictionaryService;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import cn.tianshang.commerce.project.ProjectService;
import cn.tianshang.commerce.quote.QuoteInput;
import cn.tianshang.commerce.quote.QuoteService;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 门店报价明细的 Excel 导入导出：同一客户 + 门店写多行，每行一个产品或一个标准组合（组合名 + 套数）。
 * 每个门店生成一份新的报价版本；门店不存在时顺带登记门店。门店有合同号时报价直接出具，否则为草稿。
 * 已有相同明细的报价版本时跳过（重复导入同一份文件不会产生重复版本）。
 */
@Component
public class QuoteExcel {

    private static final Map<String, String> TAX_BASIS = Map.of(
        "含税",
        "INCLUDED",
        "不含税",
        "EXCLUDED",
        "未定",
        "UNKNOWN"
    );

    private final Database db;
    private final QuoteService quotes;
    private final ProjectService projects;
    private final CatalogService catalog;
    private final CustomerTemplateService customerTemplates;
    private final DictionaryService dictionary;
    private final Validator validator;

    public QuoteExcel(
        Database db,
        QuoteService quotes,
        ProjectService projects,
        CatalogService catalog,
        CustomerTemplateService customerTemplates,
        DictionaryService dictionary,
        Validator validator
    ) {
        this.db = db;
        this.quotes = quotes;
        this.projects = projects;
        this.catalog = catalog;
        this.customerTemplates = customerTemplates;
        this.dictionary = dictionary;
        this.validator = validator;
    }

    List<Column> columns() {
        return List.of(
            Column.of("customer", "客户", 24).required().note("已登记的客户名称"),
            Column.of("store", "门店名称", 28)
                .required()
                .note("同一客户 + 门店写多行；门店不存在时按下面的信息登记新门店"),
            Column.of("city", "城市", 10).note("新门店用；须在城市字典中"),
            Column.of("type", "项目类型", 16).note("新门店必填；已有门店留空时沿用门店原项目类型"),
            Column.of("contract_no", "合同号", 20).note("新门店用；填了合同号的门店报价直接出具，否则为草稿"),
            Column.of("order_date", "下单日期", 12).note("新门店用，如 2026-03-01"),
            Column.of("valid_until", "报价有效期", 12).note("留空为 30 天后"),
            Column.of("tax_basis", "税口径", 8).options("含税", "不含税", "未定").note("留空为含税"),
            Column.of("tax_rate", "税率", 8).number().note("如 0.13；留空为 0.13"),
            Column.of("cost_supplier", "成本供应商", 18).note("报价成本按这家供应商的成本模板取，可留空"),
            Column.of("quote_note", "报价备注", 24),
            Column.of("bundle", "组合名称", 20).note(
                "填组合名称、产品留空：整个组合按套数展开；填了产品：该产品属于这个组合，相邻同名行同一组"
            ),
            Column.of("sets", "套数", 8).number().note("组合行用，留空为 1"),
            Column.of("scenario", "产品应用场景", 14),
            Column.of("name", "产品名称", 22),
            Column.of("specification", "规格", 16),
            Column.of("model", "型号", 12),
            Column.of("supplier", "供应商", 18).note("同一种产品有多家供应商时必填"),
            Column.of("quantity", "数量", 8).number(),
            Column.of("unit_price", "单价", 12).number().note("元/台；留空时按客户报价模板或项目类型的标准售价"),
            Column.of("unit_cost", "单台成本", 12).number().note("可留空（按成本供应商的成本模板取，或待财务补充）")
        );
    }

    // ===================== 导出 =====================

    /** 导出每个门店最新一个版本的报价明细（业务员只有自己负责的门店）。 */
    List<Map<String, Object>> rows() {
        var latest = new LinkedHashMap<String, Map<String, Object>>();
        for (var q : quotes.list()) {
            String pid = q.get("project_id").toString();
            var known = latest.get(pid);
            if (
                known == null || ((Number) q.get("version")).intValue() > ((Number) known.get("version")).intValue()
            ) latest.put(pid, q);
        }
        var ids = latest
            .values()
            .stream()
            .map(q -> q.get("id").toString())
            .toList();
        var items = new HashMap<String, List<Map<String, Object>>>();
        if (!ids.isEmpty()) for (var i : db.jdbc.queryForList(
            """
            SELECT i.*,p.scenario,p.model,s.name AS supplier_name FROM quotation_item i JOIN product p ON p.id=i.product_id
            LEFT JOIN partner s ON s.id=p.supplier_id ORDER BY i.position
            """
        ))
            items.computeIfAbsent(i.get("quotation_id").toString(), k -> new ArrayList<>()).add(i);
        var suppliers = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT id,name FROM partner WHERE kind='SUPPLIER'"))
            suppliers.put(p.get("id").toString(), text(p, "name"));
        var typeNames = new HashMap<String, String>();
        for (var t : catalog.types()) typeNames.put(t.get("id").toString(), text(t, "name"));
        var cities = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT id,city,project_type_id,order_date FROM project"))
            cities.put(
                p.get("id").toString(),
                text(p, "city") + "\u0000" + text(p, "project_type_id") + "\u0000" + text(p, "order_date")
            );
        var out = new ArrayList<Map<String, Object>>();
        for (var q : latest.values()) {
            var base = new HashMap<String, Object>();
            var pj = cities.getOrDefault(q.get("project_id").toString(), "\u0000\u0000").split("\u0000", -1);
            base.put("customer", q.get("customer_name"));
            base.put("store", q.get("project_name"));
            base.put("city", pj[0]);
            String typeId = q.get("project_type_id") == null ? pj[1] : q.get("project_type_id").toString();
            base.put("type", typeNames.getOrDefault(typeId, ""));
            base.put("contract_no", q.get("contract_no"));
            base.put("order_date", pj.length > 2 && pj[2].length() >= 10 ? pj[2].substring(0, 10) : null);
            base.put(
                "valid_until",
                Objects.toString(q.get("valid_until"), "").length() >= 10
                    ? q.get("valid_until").toString().substring(0, 10)
                    : null
            );
            String basis = text(q, "tax_basis");
            base.put(
                "tax_basis",
                TAX_BASIS.entrySet()
                    .stream()
                    .filter(e -> e.getValue().equals(basis))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse("")
            );
            base.put("tax_rate", q.get("tax_rate"));
            base.put(
                "cost_supplier",
                q.get("supplier_id") == null ? null : suppliers.get(q.get("supplier_id").toString())
            );
            base.put("quote_note", q.get("note"));
            for (var i : items.getOrDefault(q.get("id").toString(), List.of())) {
                var r = new HashMap<String, Object>(base);
                r.put("bundle", i.get("bundle_name"));
                r.put("sets", i.get("bundle_sets"));
                r.put("scenario", i.get("scenario"));
                r.put("name", i.get("name"));
                r.put("specification", i.get("specification"));
                r.put("model", i.get("model"));
                r.put("supplier", i.get("supplier_name"));
                r.put("quantity", i.get("quantity"));
                r.put("unit_price", i.get("unit_price"));
                r.put("unit_cost", i.get("unit_cost"));
                out.add(r);
            }
        }
        return out;
    }

    // ===================== 导入 =====================

    List<Op> plan(ExcelIo.Sheet sheet) {
        var products = new ProductIndex(db);
        var bundleLines = new BundleLines(db);
        var customers = BundleExcel.partners(db, "CUSTOMER");
        var suppliers = BundleExcel.partners(db, "SUPPLIER");
        var types = index(catalog.types(), t -> text(t, "name"));
        var typesById = index(catalog.types(), t -> t.get("id").toString());
        var stores = new HashMap<String, Map<String, Object>>();
        for (var p : db.jdbc.queryForList("SELECT id,customer_id,name,contract_no,project_type_id,city FROM project"))
            stores.put(p.get("customer_id") + "::" + text(p, "name").trim(), p);
        var groups = group(sheet.rows(), r -> r.get("customer").trim() + "::" + r.get("store").trim());
        var ops = new ArrayList<Op>();
        for (var g : groups.values()) {
            var first = g.get(0);
            var errors = new ArrayList<String>();
            var notes = new ArrayList<String>();
            String customerName = first.get("customer").trim(),
                storeName = first.get("store").trim();
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”不存在，请先登记或导入客户资料");
            if (storeName.isEmpty()) errors.add("门店名称必填");
            var store = customerId == null ? null : stores.get(customerId + "::" + storeName);
            // 项目类型：文件里填的优先，否则沿用门店的
            String typeName = first.get("type").trim();
            Map<String, Object> type = null;
            if (!typeName.isEmpty()) {
                type = types.get(typeName);
                if (type == null) errors.add("项目类型“" + typeName + "”不存在，请先维护项目类型");
            } else if (store != null && store.get("project_type_id") != null) type = typesById.get(
                store.get("project_type_id").toString()
            );
            if (type == null && typeName.isEmpty()) errors.add(
                store == null ? "新门店必须填写项目类型" : "该门店没有项目类型，请填写项目类型"
            );
            String typeId = type == null ? null : type.get("id").toString();
            // 新门店的要素
            String city = null;
            LocalDate orderDate = null;
            try {
                city = store == null ? dictionary.city(first.get("city"), null) : null;
                orderDate = ExcelIo.date(first.get("order_date"), "下单日期");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            String contractNo = store == null ? blank(first.get("contract_no")) : blank(text(store, "contract_no"));
            if (
                store != null &&
                !first.get("contract_no").isBlank() &&
                !first.get("contract_no").trim().equals(contractNo)
            ) notes.add(
                "门店已有合同号" +
                    (contractNo == null ? "为空" : contractNo) +
                    "，文件中的合同号不会改变门店；请在项目台账中修改"
            );
            // 报价条件
            LocalDate validUntil = null;
            BigDecimal taxRate = null;
            String taxBasis = "INCLUDED";
            try {
                validUntil = first.get("valid_until").isEmpty()
                    ? LocalDate.now().plusDays(30)
                    : ExcelIo.date(first.get("valid_until"), "报价有效期");
                taxRate = first.get("tax_rate").isEmpty() ? null : ExcelIo.decimal(first.get("tax_rate"), "税率");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            if (!first.get("tax_basis").isEmpty()) {
                taxBasis = TAX_BASIS.get(first.get("tax_basis").trim());
                if (taxBasis == null) {
                    errors.add("税口径只能是 含税 / 不含税 / 未定");
                    taxBasis = "INCLUDED";
                }
            }
            if (taxBasis.equals("UNKNOWN")) taxRate = null;
            else if (taxRate == null) taxRate = new BigDecimal("0.13");
            String costSupplier = null;
            if (!first.get("cost_supplier").isBlank()) {
                costSupplier = suppliers.get(first.get("cost_supplier").trim());
                if (costSupplier == null) errors.add("成本供应商“" + first.get("cost_supplier").trim() + "”不存在");
            }
            // 默认售价：客户报价模板 → 项目类型标准售价
            var prices = new HashMap<String, BigDecimal>();
            if (type != null) {
                @SuppressWarnings("unchecked")
                var typeItems = (List<Map<String, Object>>) type.get("items");
                for (var i : typeItems)
                    if (i.get("unit_price") != null) prices.put(
                        i.get("product_id").toString(),
                        decimal(i, "unit_price")
                    );
                if (customerId != null) customerTemplates
                    .active(customerId, typeId)
                    .ifPresent(t -> {
                        @SuppressWarnings("unchecked")
                        var items = (List<Map<String, Object>>) t.get("items");
                        for (var i : items)
                            if (i.get("unit_price") != null) prices.put(
                                i.get("product_id").toString(),
                                decimal(i, "unit_price")
                            );
                    });
            }
            var parsed = bundleLines.parse(g, products, prices::get, true, errors);
            if (parsed.isEmpty() && errors.isEmpty()) errors.add("没有明细行");
            boolean withCost = Roles.canSeeCost() && sheet.present().contains("unit_cost");
            var lines = new ArrayList<QuoteInput.Line>();
            for (var l : parsed) {
                BigDecimal cost = null;
                if (withCost) {
                    var row = g
                        .stream()
                        .filter(r -> r.number() == l.rowNo())
                        .findFirst()
                        .orElse(null);
                    // 组合展开行没有单独的成本列
                    if (row != null && l.group() == null) try {
                        cost = ExcelIo.decimal(row.get("unit_cost"), "单台成本");
                    } catch (ApiException e) {
                        errors.add(at(row) + "：" + e.getMessage());
                    }
                }
                lines.add(
                    new QuoteInput.Line(l.productId(), l.quantity(), l.unitPrice(), cost, null, null, null, l.group())
                );
            }
            var input = new QuoteInput(
                store == null ? null : store.get("id").toString(),
                store != null
                    ? null
                    : new QuoteInput.NewProject(
                          customerId == null ? "" : customerId,
                          storeName,
                          city,
                          typeId == null ? "" : typeId,
                          contractNo,
                          orderDate
                      ),
                taxBasis,
                taxRate,
                validUntil == null ? LocalDate.now().plusDays(30) : validUntil,
                blank(first.get("quote_note")),
                null,
                costSupplier,
                typeId,
                null,
                lines
            );
            validate(validator, input, errors, Map.of("validUntil", "报价有效期", "taxRate", "税率", "items", "明细"));
            if (store != null && errors.isEmpty()) {
                String state = projects.contractState(store.get("id").toString());
                if (!List.of("NONE", "DRAFT").contains(state)) {
                    if (Roles.canApprove()) notes.add("该门店的合同已签订，导入的新版本属于合同变更");
                    else errors.add("该门店的合同已签订，新增报价版本属于合同变更，请联系管理层办理");
                }
            }
            BigDecimal total = BigDecimal.ZERO;
            for (var l : lines)
                total = total.add(l.quantity().multiply(l.unitPrice() == null ? BigDecimal.ZERO : l.unitPrice()));
            String rows = span(g);
            String label =
                customerName +
                " / " +
                storeName +
                "（" +
                lines.size() +
                " 行，合计 " +
                total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() +
                " 元）";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            Integer sameVersion = store == null ? null : sameVersion(store.get("id").toString(), lines);
            if (sameVersion != null) {
                ops.add(
                    new Op(
                        new Result(rows, SAME, label, List.of("已有相同明细的报价 V" + sameVersion + "，跳过")),
                        null
                    )
                );
                continue;
            }
            boolean issue = contractNo != null;
            notes.add(0, (store == null ? "登记新门店并" : "") + "新建报价版本" + (issue ? "并出具" : "（草稿）"));
            ops.add(
                new Op(new Result(rows, CREATE, label, notes), () -> {
                    String id = quotes.create(input);
                    if (issue) quotes.transition(id, "issue", null);
                })
            );
        }
        return ops;
    }

    /** 门店最新版本的明细与文件相同（产品、数量、单价）时返回版本号。 */
    private Integer sameVersion(String projectId, List<QuoteInput.Line> lines) {
        var latest = db.jdbc.queryForList(
            "SELECT id,version FROM quotation WHERE project_id=? ORDER BY version DESC LIMIT 1",
            projectId
        );
        if (latest.isEmpty()) return null;
        var items = db.jdbc.queryForList(
            "SELECT product_id,quantity,unit_price FROM quotation_item WHERE quotation_id=? ORDER BY position",
            latest.get(0).get("id")
        );
        if (items.size() != lines.size()) return null;
        for (int i = 0; i < lines.size(); i++) {
            var o = items.get(i);
            var n = lines.get(i);
            if (
                !o.get("product_id").equals(n.productId()) ||
                !eq(o.get("quantity"), n.quantity()) ||
                !eq(o.get("unit_price"), n.unitPrice())
            ) return null;
        }
        return ((Number) latest.get(0).get("version")).intValue();
    }
}
