package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import java.util.*;

/**
 * 导入时按“应用场景 + 名称 + 规格 + 型号”找标准产品。
 * 同一种产品每家供应商一条，所以有多家供应商时还要看“供应商”列；没填且只有一家时直接用那一家。
 */
final class ProductIndex {

    record Entry(
        String id,
        String scenario,
        String name,
        String specification,
        String model,
        String supplierId,
        String supplierName
    ) {
        String label() {
            return scenario + " / " + name + (specification.isEmpty() ? "" : " / " + specification);
        }
    }

    private final Map<String, List<Entry>> byKey = new HashMap<>();
    private final Map<String, Entry> byId = new HashMap<>();

    ProductIndex(Database db) {
        for (var p : db.jdbc.queryForList(
            "SELECT p.id,p.scenario,p.name,p.specification,p.model,p.supplier_id,s.name AS supplier_name FROM product p LEFT JOIN partner s ON s.id=p.supplier_id"
        )) {
            var e = new Entry(
                p.get("id").toString(),
                Rows.text(p, "scenario"),
                Rows.text(p, "name"),
                Rows.text(p, "specification"),
                Rows.text(p, "model"),
                (String) p.get("supplier_id"),
                Rows.text(p, "supplier_name")
            );
            byKey
                .computeIfAbsent(key(e.scenario(), e.name(), e.specification(), e.model()), k -> new ArrayList<>())
                .add(e);
            byId.put(e.id(), e);
        }
    }

    static String key(String scenario, String name, String spec, String model) {
        return String.join(
            "\u0000",
            scenario == null ? "" : scenario.trim(),
            name == null ? "" : name.trim(),
            spec == null ? "" : spec.trim(),
            model == null ? "" : model.trim()
        );
    }

    Entry byId(String id) {
        return byId.get(id);
    }

    /** 用行里的 scenario / name / specification / model / supplier 列找产品；找不到时把原因记进 errors 并返回 null。 */
    String find(Row r, List<String> errors) {
        return find(r, null, errors);
    }

    /** 同上，但可以指定优先的供应商（供应商成本模板、组合：没填供应商列时优先取这家的产品）。 */
    String find(Row r, String preferredSupplierId, List<String> errors) {
        return find(
            r.get("scenario"),
            r.get("name"),
            r.get("specification"),
            r.get("model"),
            r.get("supplier"),
            preferredSupplierId,
            r.number(),
            errors
        );
    }

    String find(
        String scenario,
        String name,
        String spec,
        String model,
        String supplierName,
        String preferredSupplierId,
        int rowNo,
        List<String> errors
    ) {
        String where = "第 " + rowNo + " 行：";
        var list = byKey.getOrDefault(key(scenario, name, spec, model), List.of());
        if (list.isEmpty()) {
            errors.add(
                where +
                    "标准产品库中找不到“" +
                    scenario.trim() +
                    " / " +
                    name.trim() +
                    (spec.isBlank() ? "" : " / " + spec.trim()) +
                    (model == null || model.isBlank() ? "" : " / " + model.trim()) +
                    "”"
            );
            return null;
        }
        if (supplierName != null && !supplierName.isBlank()) {
            for (var e : list) if (e.supplierName().equals(supplierName.trim())) return e.id();
            errors.add(where + "供应商“" + supplierName.trim() + "”没有这个产品（" + list.get(0).label() + "）");
            return null;
        }
        if (list.size() == 1) return list.get(0).id();
        if (preferredSupplierId != null) for (var e : list)
            if (preferredSupplierId.equals(e.supplierId())) return e.id();
        errors.add(
            where +
                "“" +
                list.get(0).label() +
                "”有 " +
                list.size() +
                " 家供应商（" +
                String.join(
                    "、",
                    list
                        .stream()
                        .map(e -> e.supplierName().isEmpty() ? "未指定" : e.supplierName())
                        .toList()
                ) +
                "），请在“供应商”列指明"
        );
        return null;
    }
}
