package cn.tianshang.commerce.excel;

import static cn.tianshang.commerce.excel.ExcelService.*;
import static cn.tianshang.commerce.excel.Rows.*;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.dictionary.DictionaryService;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelService.Dataset;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import cn.tianshang.commerce.master.BundleService;
import cn.tianshang.commerce.master.ProductComponentService;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

/** 标准产品的电气件清单、标准组合的 Excel 导入导出。两者都是“同一产品 / 组合写多行”，导入时整份替换。 */
@Component
public class BundleExcel {

    private final Database db;
    private final ProductComponentService components;
    private final BundleService bundles;
    private final DictionaryService dictionary;
    private final Validator validator;

    public BundleExcel(
        Database db,
        ProductComponentService components,
        BundleService bundles,
        DictionaryService dictionary,
        Validator validator
    ) {
        this.db = db;
        this.components = components;
        this.bundles = bundles;
        this.dictionary = dictionary;
        this.validator = validator;
    }

    // ===================== 列 =====================

    List<Column> componentColumns() {
        return List.of(
            Column.of("scenario", "产品应用场景", 14).required().note("与标准产品库一致，用于定位产品"),
            Column.of("name", "产品名称", 22).required(),
            Column.of("specification", "规格", 16),
            Column.of("model", "型号", 12),
            Column.of("supplier", "供应商", 18).note("同一种产品有多家供应商时必填"),
            Column.of("comp_name", "电气件名称", 22)
                .required()
                .note("同一产品写多行，每行一个电气件；导入时以文件中的清单替换该产品原有清单"),
            Column.of("comp_model", "电气件型号", 16),
            Column.of("brand", "品牌", 12),
            Column.of("quantity", "数量", 8).number().required(),
            Column.of("unit", "单位", 8),
            Column.of("comp_note", "备注", 24)
        );
    }

    List<Column> bundleColumns() {
        return List.of(
            Column.of("bundle", "组合名称", 22)
                .required()
                .note("同一组合写多行，每行一个产品；导入时以文件中的产品替换该组合原有内容"),
            Column.of("scenario", "应用场景", 14)
                .required()
                .note("组合所属的应用场景")
                .options(dictionary.activeScenarioNames().toArray(String[]::new)),
            Column.of("supplier", "供应商", 18).required().note("一个组合一家供应商，组合里只能放这家供应商的标准品"),
            Column.of("active", "启用", 8).options("是", "否"),
            Column.of("bundle_note", "组合说明", 24),
            Column.of("product_scenario", "产品应用场景", 14).note("留空时与组合的应用场景相同"),
            Column.of("name", "产品名称", 22).required(),
            Column.of("specification", "规格", 16),
            Column.of("model", "型号", 12),
            Column.of("quantity", "每套数量", 8).number().required()
        );
    }

    // ===================== 导出 =====================

    List<Map<String, Object>> componentRows() {
        return db.jdbc.queryForList(
            """
            SELECT p.scenario,p.name,p.specification,p.model,s.name AS supplier,
              c.name AS comp_name,c.model AS comp_model,c.brand,c.quantity,c.unit,c.note AS comp_note
            FROM product_component c JOIN product p ON p.id=c.product_id LEFT JOIN partner s ON s.id=p.supplier_id
            ORDER BY p.scenario,p.name,p.specification,s.name,c.position
            """
        );
    }

    List<Map<String, Object>> bundleRows() {
        var out = new ArrayList<Map<String, Object>>();
        for (var b : bundles.list()) {
            var base = new HashMap<String, Object>();
            base.put("bundle", b.get("name"));
            base.put("scenario", b.get("scenario"));
            base.put("supplier", b.get("supplier_name"));
            base.put("active", Boolean.TRUE.equals(b.get("active")) ? "是" : "否");
            base.put("bundle_note", b.get("note"));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) b.get("items");
            if (items.isEmpty()) out.add(base);
            for (var i : items) {
                var r = new HashMap<String, Object>(base);
                r.put("product_scenario", i.get("product_scenario"));
                r.put("name", i.get("name"));
                r.put("specification", i.get("specification"));
                r.put("model", i.get("model"));
                r.put("quantity", i.get("quantity"));
                out.add(r);
            }
        }
        return out;
    }

    // ===================== 导入 =====================

    List<Op> plan(Dataset d, ExcelIo.Sheet sheet) {
        return d == Dataset.PRODUCT_COMPONENTS ? planComponents(sheet) : planBundles(sheet);
    }

    private List<Op> planComponents(ExcelIo.Sheet sheet) {
        var products = new ProductIndex(db);
        var existing = new HashMap<String, List<Map<String, Object>>>();
        for (var c : db.jdbc.queryForList(
            "SELECT product_id,name,model,brand,quantity,unit,note FROM product_component ORDER BY position"
        ))
            existing.computeIfAbsent(c.get("product_id").toString(), k -> new ArrayList<>()).add(c);
        var groups = group(
            sheet.rows(),
            r ->
                ProductIndex.key(r.get("scenario"), r.get("name"), r.get("specification"), r.get("model")) +
                "|" +
                r.get("supplier").trim()
        );
        var ops = new ArrayList<Op>();
        var planned = new HashSet<String>();
        for (var g : groups.values()) {
            var first = g.get(0);
            var errors = new ArrayList<String>();
            String pid = products.find(first, errors);
            if (pid != null && !planned.add(pid)) errors.add("该产品在文件中出现了两次（供应商列写法不同）");
            var items = new ArrayList<ProductComponentService.Component>();
            for (var r : g) {
                if (r.get("comp_name").isEmpty()) {
                    errors.add(at(r) + "：电气件名称必填");
                    continue;
                }
                try {
                    var qty = ExcelIo.decimal(r.get("quantity"), "数量");
                    if (qty == null || qty.signum() <= 0) errors.add(at(r) + "：数量必须大于 0");
                    else items.add(
                        new ProductComponentService.Component(
                            r.get("comp_name"),
                            blank(r.get("comp_model")),
                            blank(r.get("brand")),
                            qty,
                            blank(r.get("unit")),
                            blank(r.get("comp_note"))
                        )
                    );
                } catch (ApiException e) {
                    errors.add(at(r) + "：" + e.getMessage());
                }
            }
            var input = new ProductComponentService.ComponentsInput(items);
            validate(validator, input, errors, Map.of());
            var p = pid == null ? null : products.byId(pid);
            String label =
                (p == null ? first.get("name") : p.label() + " · " + p.supplierName()) +
                "（" +
                items.size() +
                " 项电气件）";
            String rows = span(g);
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            var old = existing.getOrDefault(pid, List.of());
            if (sameComponents(old, items)) {
                ops.add(new Op(new Result(rows, SAME, label, List.of()), null));
                continue;
            }
            String action = old.isEmpty() ? CREATE : UPDATE;
            var messages = old.isEmpty() ? List.<String>of() : List.of("以文件中的清单替换原有 " + old.size() + " 项");
            ops.add(new Op(new Result(rows, action, label, messages), () -> components.save(pid, input)));
        }
        return ops;
    }

    private static boolean sameComponents(
        List<Map<String, Object>> old,
        List<ProductComponentService.Component> items
    ) {
        if (old.size() != items.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            var o = old.get(i);
            var n = items.get(i);
            if (
                !text(o, "name").equals(n.name().trim()) ||
                !Objects.equals(blank(text(o, "model")), n.model()) ||
                !Objects.equals(blank(text(o, "brand")), n.brand()) ||
                !eq(o.get("quantity"), n.quantity()) ||
                !Objects.equals(blank(text(o, "unit")), n.unit()) ||
                !Objects.equals(blank(text(o, "note")), n.note())
            ) return false;
        }
        return true;
    }

    private List<Op> planBundles(ExcelIo.Sheet sheet) {
        var products = new ProductIndex(db);
        var suppliers = partners(db, "SUPPLIER");
        var existing = index(bundles.list(), b -> text(b, "scenario") + "/" + text(b, "name"));
        var groups = group(sheet.rows(), r -> r.get("scenario").trim() + "/" + r.get("bundle").trim());
        var ops = new ArrayList<Op>();
        for (var g : groups.entrySet()) {
            var rowsOf = g.getValue();
            var first = rowsOf.get(0);
            var errors = new ArrayList<String>();
            String name = first.get("bundle").trim(),
                scenario = first.get("scenario").trim(),
                supplierName = first.get("supplier").trim();
            if (name.isEmpty()) errors.add("组合名称必填");
            var cur = existing.get(g.getKey());
            if (scenario.isEmpty()) errors.add("应用场景必填");
            else try {
                dictionary.scenario(scenario, cur == null ? null : text(cur, "scenario"));
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            String supplierId = suppliers.get(supplierName);
            if (supplierName.isEmpty()) errors.add("供应商必填");
            else if (supplierId == null) errors.add("供应商“" + supplierName + "”不存在，请先登记或导入供应商资料");
            boolean active = flag(sheet, first, "active", cur, "active", true);
            String note = blank(value(sheet, first, "bundle_note", cur, "note"));
            var items = new ArrayList<BundleService.BundleLine>();
            var seen = new HashSet<String>();
            for (var r : rowsOf) {
                if (r.get("name").isEmpty() && r.get("specification").isEmpty()) continue;
                String ps = r.get("product_scenario").isEmpty() ? scenario : r.get("product_scenario");
                String pid = products.find(
                    ps,
                    r.get("name"),
                    r.get("specification"),
                    r.get("model"),
                    supplierName,
                    supplierId,
                    r.number(),
                    errors
                );
                if (pid == null) continue;
                if (!seen.add(pid)) errors.add(at(r) + "：同一产品重复，请合并数量");
                try {
                    var qty = ExcelIo.decimal(r.get("quantity"), "每套数量");
                    if (qty == null || qty.signum() <= 0) errors.add(at(r) + "：每套数量必须大于 0");
                    else items.add(new BundleService.BundleLine(pid, qty));
                } catch (ApiException e) {
                    errors.add(at(r) + "：" + e.getMessage());
                }
            }
            var input = new BundleService.BundleInput(
                scenario,
                supplierId == null ? "" : supplierId,
                name,
                note,
                active,
                items
            );
            validate(validator, input, errors, Map.of("name", "组合名称", "scenario", "应用场景", "items", "产品"));
            String rows = span(rowsOf);
            String label = scenario + " / " + name + " · " + supplierName + "（" + items.size() + " 种产品）";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (cur == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> bundles.save(null, input)));
                continue;
            }
            @SuppressWarnings("unchecked")
            var old = (List<Map<String, Object>>) cur.get("items");
            boolean same =
                Objects.equals(supplierId, cur.get("supplier_id")) &&
                active == Boolean.TRUE.equals(cur.get("active")) &&
                Objects.equals(note, blank(text(cur, "note"))) &&
                old.size() == items.size();
            for (int i = 0; same && i < items.size(); i++) same =
                old.get(i).get("product_id").equals(items.get(i).productId()) &&
                eq(old.get(i).get("quantity"), items.get(i).quantity());
            String id = cur.get("id").toString();
            ops.add(
                same
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(
                          new Result(rows, UPDATE, label, List.of("以文件中的产品替换原有 " + old.size() + " 种")),
                          () -> bundles.save(id, input)
                      )
            );
        }
        return ops;
    }

    static Map<String, String> partners(Database db, String kind) {
        var out = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT id,name FROM partner WHERE kind=?", kind))
            out.put(text(p, "name").trim(), p.get("id").toString());
        return out;
    }
}
