package cn.tianshang.commerce.excel;

import static cn.tianshang.commerce.excel.ExcelService.*;
import static cn.tianshang.commerce.excel.Rows.*;

import cn.tianshang.commerce.catalog.CatalogService;
import cn.tianshang.commerce.catalog.CustomerTemplateService;
import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

/** 客户报价模板（某客户在某项目类型下的协议配置与售价）的 Excel 导入导出：同一客户 + 项目类型写多行，导入时整份替换。 */
@Component
public class CustomerTemplateExcel {

    private final Database db;
    private final CustomerTemplateService templates;
    private final CatalogService catalog;
    private final Validator validator;

    public CustomerTemplateExcel(
        Database db,
        CustomerTemplateService templates,
        CatalogService catalog,
        Validator validator
    ) {
        this.db = db;
        this.templates = templates;
        this.catalog = catalog;
        this.validator = validator;
    }

    List<Column> columns() {
        return List.of(
            Column.of("customer", "客户", 24).required().note("已登记的客户名称"),
            Column.of("type", "项目类型", 16)
                .required()
                .note("同一客户 + 项目类型写多行；导入时以文件中的配置替换该模板的全部内容"),
            Column.of("active", "启用", 8).options("是", "否"),
            Column.of("template_note", "模板备注", 20),
            Column.of("scenario", "产品应用场景", 14).required().note("与标准产品库一致，用于定位产品"),
            Column.of("name", "产品名称", 22).required(),
            Column.of("specification", "规格", 16),
            Column.of("model", "型号", 12),
            Column.of("supplier", "供应商", 18).note("同一种产品有多家供应商时必填"),
            Column.of("quantity", "数量", 8).number().required(),
            Column.of("unit_price", "协议售价", 12).number().note("与该客户约定的售价（元），可留空")
        );
    }

    List<Map<String, Object>> rows() {
        var products = new ProductIndex(db);
        var out = new ArrayList<Map<String, Object>>();
        for (var t : templates.list()) {
            var base = new HashMap<String, Object>();
            base.put("customer", t.get("customer_name"));
            base.put("type", t.get("project_type_name"));
            base.put("active", Boolean.TRUE.equals(t.get("active")) ? "是" : "否");
            base.put("template_note", t.get("note"));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) t.get("items");
            for (var i : items) {
                var p = products.byId(i.get("product_id").toString());
                var r = new HashMap<String, Object>(base);
                r.put("scenario", p == null ? "" : p.scenario());
                r.put("name", i.get("name"));
                r.put("specification", i.get("specification"));
                r.put("model", i.get("model"));
                r.put("supplier", p == null ? "" : p.supplierName());
                r.put("quantity", i.get("quantity"));
                r.put("unit_price", i.get("unit_price"));
                out.add(r);
            }
        }
        return out;
    }

    List<Op> plan(ExcelIo.Sheet sheet) {
        var products = new ProductIndex(db);
        var customers = BundleExcel.partners(db, "CUSTOMER");
        var types = index(catalog.types(), t -> text(t, "name"));
        var existing = index(templates.list(), t -> t.get("customer_id") + "::" + t.get("project_type_id"));
        var groups = group(sheet.rows(), r -> r.get("customer").trim() + "\u0000" + r.get("type").trim());
        var ops = new ArrayList<Op>();
        for (var g : groups.values()) {
            var first = g.get(0);
            var errors = new ArrayList<String>();
            String customerName = first.get("customer").trim(),
                typeName = first.get("type").trim();
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”不存在，请先登记或导入客户资料");
            var type = types.get(typeName);
            if (typeName.isEmpty()) errors.add("项目类型必填");
            else if (type == null) errors.add("项目类型“" + typeName + "”不存在，请先维护项目类型");
            var cur = customerId == null || type == null ? null : existing.get(customerId + "::" + type.get("id"));
            boolean active = flag(sheet, first, "active", cur, "active", true);
            String note = blank(value(sheet, first, "template_note", cur, "note"));
            var lines = new ArrayList<CatalogService.TypeLine>();
            var seen = new HashSet<String>();
            for (var r : g) {
                if (r.get("name").isEmpty() && r.get("specification").isEmpty()) continue;
                String pid = products.find(r, errors);
                if (pid == null) continue;
                if (!seen.add(pid)) errors.add(at(r) + "：同一产品重复");
                try {
                    var qty = ExcelIo.decimal(r.get("quantity"), "数量");
                    if (qty == null || qty.signum() <= 0) errors.add(at(r) + "：数量必须大于 0");
                    else lines.add(
                        new CatalogService.TypeLine(pid, qty, ExcelIo.decimal(r.get("unit_price"), "协议售价"))
                    );
                } catch (ApiException e) {
                    errors.add(at(r) + "：" + e.getMessage());
                }
            }
            var input = new CustomerTemplateService.Input(
                customerId == null ? "" : customerId,
                type == null ? "" : type.get("id").toString(),
                note,
                active,
                lines
            );
            validate(validator, input, errors, Map.of("items", "产品", "note", "模板备注"));
            String rows = span(g);
            String label = customerName + " · " + typeName + "（" + lines.size() + " 项配置）";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (cur == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> templates.save(input)));
                continue;
            }
            @SuppressWarnings("unchecked")
            var old = (List<Map<String, Object>>) cur.get("items");
            boolean same =
                active == Boolean.TRUE.equals(cur.get("active")) &&
                Objects.equals(note, blank(text(cur, "note"))) &&
                old.size() == lines.size();
            for (int i = 0; same && i < lines.size(); i++) same =
                old.get(i).get("product_id").equals(lines.get(i).productId()) &&
                eq(old.get(i).get("quantity"), lines.get(i).quantity()) &&
                eq(old.get(i).get("unit_price"), lines.get(i).unitPrice());
            ops.add(
                same
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(
                          new Result(rows, UPDATE, label, List.of("以文件中的配置替换原有 " + old.size() + " 项")),
                          () -> templates.save(input)
                      )
            );
        }
        return ops;
    }
}
