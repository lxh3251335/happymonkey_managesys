package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.master.LineGroup;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * 读明细行（项目类型标准配置、门店报价明细）：支持标准组合。
 *
 * <ul>
 *   <li>“组合名称”填了、产品名称留空：整行是一个组合，按“套数”展开成组内各产品（数量 = 每套数量 × 套数）。</li>
 *   <li>“组合名称”填了、产品也填了：这一行是组合里的一个产品，相邻且组合名称相同的行属于同一组；数量是总数量。</li>
 *   <li>“组合名称”留空：普通单品行。</li>
 * </ul>
 * 组合名称可以写“应用场景/名称”以区分不同场景下的同名组合。
 */
final class BundleLines {

    record Line(String productId, BigDecimal quantity, BigDecimal unitPrice, LineGroup group, int rowNo) {}

    private record Bundle(String id, String scenario, String name, List<Map<String, Object>> items) {}

    private final Map<String, List<Bundle>> byName = new HashMap<>();

    BundleLines(Database db) {
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList("SELECT bundle_id,product_id,quantity FROM bundle_item ORDER BY position"))
            items.computeIfAbsent(i.get("bundle_id").toString(), k -> new ArrayList<>()).add(i);
        for (var b : db.jdbc.queryForList("SELECT id,scenario,name FROM bundle WHERE active=TRUE")) {
            var bundle = new Bundle(
                b.get("id").toString(),
                Rows.text(b, "scenario"),
                Rows.text(b, "name"),
                items.getOrDefault(b.get("id").toString(), List.of())
            );
            byName.computeIfAbsent(bundle.name(), k -> new ArrayList<>()).add(bundle);
            byName.computeIfAbsent(bundle.scenario() + "/" + bundle.name(), k -> new ArrayList<>()).add(bundle);
        }
    }

    private Bundle find(String text, int rowNo, List<String> errors) {
        var list = byName.getOrDefault(text.trim(), List.of());
        if (list.isEmpty()) {
            errors.add("第 " + rowNo + " 行：没有启用的标准组合“" + text.trim() + "”");
            return null;
        }
        if (list.size() > 1) {
            errors.add(
                "第 " +
                    rowNo +
                    " 行：有 " +
                    list.size() +
                    " 个同名组合，请写成“应用场景/组合名称”（" +
                    String.join(
                        "、",
                        list
                            .stream()
                            .map(b -> b.scenario() + "/" + b.name())
                            .toList()
                    ) +
                    "）"
            );
            return null;
        }
        return list.get(0);
    }

    /**
     * @param priceOf 产品的默认售价（组合展开行、没填单价的行用它），可返回 null
     * @param priceRequired 单价必填（报价明细）；项目类型的标准售价可以留空
     */
    List<Line> parse(
        List<Row> rows,
        ProductIndex products,
        Function<String, BigDecimal> priceOf,
        boolean priceRequired,
        List<String> errors
    ) {
        var out = new ArrayList<Line>();
        var seen = new HashSet<String>();
        int groupNo = 0;
        String openBundle = null;
        Bundle openTarget = null;
        BigDecimal openSets = null;
        for (var r : rows) {
            String bundleText = r.get("bundle");
            boolean hasProduct = !r.get("name").isEmpty() || !r.get("specification").isEmpty();
            if (bundleText.isEmpty() && !hasProduct) continue;
            String where = "第 " + r.number() + " 行：";
            if (bundleText.isEmpty()) {
                openBundle = null;
                String pid = products.find(r, errors);
                if (pid == null) continue;
                if (!seen.add(pid)) errors.add(where + "同一产品重复");
                var qty = quantity(r, errors);
                var price = price(r, pid, priceOf, priceRequired, errors);
                if (qty != null) out.add(new Line(pid, qty, price, null, r.number()));
                continue;
            }
            var bundle = find(bundleText, r.number(), errors);
            if (bundle == null) {
                openBundle = null;
                continue;
            }
            if (!hasProduct) {
                // 整个组合一行：按套数展开
                openBundle = null;
                var sets = sets(r, errors);
                if (sets == null) continue;
                if (bundle.items().isEmpty()) {
                    errors.add(where + "组合“" + bundle.name() + "”里没有产品");
                    continue;
                }
                int no = ++groupNo;
                var group = new LineGroup(no, bundle.id(), sets);
                for (var item : bundle.items()) {
                    String pid = item.get("product_id").toString();
                    var per = new BigDecimal(item.get("quantity").toString());
                    BigDecimal price = priceOf.apply(pid);
                    if (price == null && priceRequired) {
                        var p = products.byId(pid);
                        errors.add(
                            where +
                                "组合“" +
                                bundle.name() +
                                "”里的“" +
                                (p == null ? pid : p.label()) +
                                "”没有售价，请在客户报价模板或项目类型里填写标准售价，或把组合的产品逐行列出并填单价"
                        );
                    }
                    out.add(new Line(pid, per.multiply(sets), price, group, r.number()));
                }
                continue;
            }
            // 组合里的一个产品：相邻同名的行同一组
            if (openBundle == null || !openBundle.equals(bundleText.trim()) || openTarget != bundle) {
                openBundle = bundleText.trim();
                openTarget = bundle;
                openSets = sets(r, errors);
                groupNo++;
            }
            String pid = products.find(r, errors);
            if (pid == null || openSets == null) continue;
            var qty = quantity(r, errors);
            var price = price(r, pid, priceOf, priceRequired, errors);
            if (qty != null) out.add(
                new Line(pid, qty, price, new LineGroup(groupNo, bundle.id(), openSets), r.number())
            );
        }
        return out;
    }

    private static BigDecimal quantity(Row r, List<String> errors) {
        try {
            var qty = ExcelIo.decimal(r.get("quantity"), "数量");
            if (qty == null || qty.signum() <= 0) {
                errors.add("第 " + r.number() + " 行：数量必须大于 0");
                return null;
            }
            return qty;
        } catch (ApiException e) {
            errors.add("第 " + r.number() + " 行：" + e.getMessage());
            return null;
        }
    }

    private static BigDecimal sets(Row r, List<String> errors) {
        try {
            var sets = r.get("sets").isEmpty() ? BigDecimal.ONE : ExcelIo.decimal(r.get("sets"), "套数");
            if (sets == null || sets.signum() <= 0) {
                errors.add("第 " + r.number() + " 行：套数必须大于 0");
                return null;
            }
            return sets;
        } catch (ApiException e) {
            errors.add("第 " + r.number() + " 行：" + e.getMessage());
            return null;
        }
    }

    private static BigDecimal price(
        Row r,
        String pid,
        Function<String, BigDecimal> priceOf,
        boolean required,
        List<String> errors
    ) {
        try {
            var price = ExcelIo.decimal(r.get("unit_price"), "单价");
            if (price == null) price = priceOf.apply(pid);
            if (price == null && required) errors.add("第 " + r.number() + " 行：没有单价，也没有可用的标准售价");
            else if (price != null && price.signum() < 0) errors.add("第 " + r.number() + " 行：单价不能为负数");
            return price;
        } catch (ApiException e) {
            errors.add("第 " + r.number() + " 行：" + e.getMessage());
            return null;
        }
    }
}
