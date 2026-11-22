package cn.tianshang.commerce.catalog;

import cn.tianshang.commerce.business.BusinessService;
import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报价配置：项目类型（门店标准配置 + 对客标准售价）与供应商成本模板（材料 + 人工成本 + 财务成本）。
 *
 * <p>新建报价时选项目类型和供应商，按类型带出产品、数量、售价，按供应商模板带出成本；报价中可逐行修改。
 */
@Service
public class CatalogService {

    private final Database db;
    private final BusinessService business;
    private final CustomerTemplateService customerTemplates;

    private final cn.tianshang.commerce.master.BundleService bundles;

    public CatalogService(
        Database db,
        BusinessService business,
        CustomerTemplateService customerTemplates,
        cn.tianshang.commerce.master.BundleService bundles
    ) {
        this.db = db;
        this.business = business;
        this.customerTemplates = customerTemplates;
        this.bundles = bundles;
    }

    // ---------- 项目类型 ----------

    public List<Map<String, Object>> types() {
        var types = db.jdbc.queryForList(
            """
            SELECT t.*,
              (SELECT COUNT(*) FROM supplier_template s WHERE s.project_type_id=t.id AND s.active=TRUE) AS supplier_templates,
              (SELECT COUNT(*) FROM project p WHERE p.project_type_id=t.id) AS projects
            FROM project_type t ORDER BY t.active DESC,t.name
            """
        );
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList(
            """
            SELECT i.*,p.name,p.specification,p.model,p.unit,p.scenario AS product_scenario
            FROM project_type_item i JOIN product p ON p.id=i.product_id ORDER BY i.position
            """
        ))
            items.computeIfAbsent(i.get("project_type_id").toString(), k -> new ArrayList<>()).add(i);
        for (var t : types) t.put("items", items.getOrDefault(t.get("id").toString(), List.of()));
        return types;
    }

    public record TypeLine(
        @NotBlank String productId,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal quantity,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitPrice,
        /** 这一行属于哪个组合；不属于组合时为空。 */
        @Valid cn.tianshang.commerce.master.LineGroup group
    ) {
        public TypeLine(String productId, BigDecimal quantity, BigDecimal unitPrice) {
            this(productId, quantity, unitPrice, null);
        }
    }

    public record TypeInput(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 30) String brand,
        @Size(max = 1000) String note,
        boolean active,
        @NotNull @Size(max = 100) List<@Valid TypeLine> items
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public String saveType(String id, @Valid TypeInput t) {
        // 同一产品在同一组（或都不在组合里）只能出现一次；不同组合里可以有同一个产品
        requireDistinct(
            t
                .items()
                .stream()
                .map(l -> (l.group() == null ? "-" : l.group().no()) + ":" + l.productId())
                .toList()
        );
        var groups = bundles.resolve(t.items(), TypeLine::group);
        String current =
            id == null
                ? null
                : db.jdbc
                      .queryForList("SELECT brand FROM project_type WHERE id=?", String.class, id)
                      .stream()
                      .findFirst()
                      .orElse(null);
        String brand = business.resolve(t.brand(), current);
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO project_type(id,name,brand,note,active) VALUES(?,?,?,?,?)",
                id,
                t.name().trim(),
                brand,
                blank(t.note()),
                t.active()
            );
        } else {
            db.one("SELECT id FROM project_type WHERE id=? FOR UPDATE", id);
            db.jdbc.update(
                "UPDATE project_type SET name=?,brand=?,note=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                t.name().trim(),
                brand,
                blank(t.note()),
                t.active(),
                id
            );
            db.jdbc.update("DELETE FROM project_type_item WHERE project_type_id=?", id);
        }
        int pos = 0;
        for (var line : t.items()) {
            db.one("SELECT id FROM product WHERE id=?", line.productId());
            var group = line.group() == null ? null : groups.get(line.group().no());
            db.jdbc.update(
                """
                INSERT INTO project_type_item(id,project_type_id,product_id,position,quantity,unit_price,
                  bundle_id,bundle_name,bundle_sets,bundle_group) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                db.id(),
                id,
                line.productId(),
                ++pos,
                line.quantity(),
                line.unitPrice(),
                group == null ? null : group.bundleId(),
                group == null ? null : group.bundleName(),
                group == null ? null : group.sets(),
                group == null ? null : group.no()
            );
        }
        db.audit("PROJECT_TYPE_SAVED", id, t.name().trim() + "，" + t.items().size() + " 项标准配置");
        return id;
    }

    // ---------- 供应商成本模板 ----------

    public List<Map<String, Object>> supplierTemplates() {
        var templates = db.jdbc.queryForList(
            """
            SELECT s.*,p.name AS supplier_name,t.name AS project_type_name
            FROM supplier_template s JOIN partner p ON p.id=s.supplier_id JOIN project_type t ON t.id=s.project_type_id
            ORDER BY t.name,p.name
            """
        );
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList(
            """
            SELECT i.*,p.name,p.specification,p.unit FROM supplier_template_item i JOIN product p ON p.id=i.product_id
            ORDER BY p.name,p.specification
            """
        )) {
            i.put("unit_cost", total(i));
            items.computeIfAbsent(i.get("template_id").toString(), k -> new ArrayList<>()).add(i);
        }
        for (var t : templates) t.put("items", items.getOrDefault(t.get("id").toString(), List.of()));
        return templates;
    }

    public record CostLine(
        @NotBlank String productId,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal materialCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal laborCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal financeCost
    ) {}

    public record SupplierTemplateInput(
        @NotBlank String supplierId,
        @NotBlank String projectTypeId,
        @Size(max = 1000) String note,
        boolean active,
        @NotNull @Size(max = 200) List<@Valid CostLine> items
    ) {}

    /** 同一供应商、同一项目类型只保留一份模板：已存在则更新。可包含标准配置以外的产品。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @Transactional
    public String saveSupplierTemplate(@Valid SupplierTemplateInput s) {
        var supplier = db.one("SELECT * FROM partner WHERE id=?", s.supplierId());
        if (!"SUPPLIER".equals(supplier.get("kind"))) throw new ApiException("请选择供应商");
        db.one("SELECT id FROM project_type WHERE id=?", s.projectTypeId());
        requireDistinct(s.items().stream().map(CostLine::productId).toList());
        var existing = db.jdbc.queryForList(
            "SELECT id FROM supplier_template WHERE supplier_id=? AND project_type_id=? FOR UPDATE",
            s.supplierId(),
            s.projectTypeId()
        );
        String id;
        if (existing.isEmpty()) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO supplier_template(id,supplier_id,project_type_id,note,active) VALUES(?,?,?,?,?)",
                id,
                s.supplierId(),
                s.projectTypeId(),
                blank(s.note()),
                s.active()
            );
        } else {
            id = existing.get(0).get("id").toString();
            db.jdbc.update(
                "UPDATE supplier_template SET note=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                blank(s.note()),
                s.active(),
                id
            );
            db.jdbc.update("DELETE FROM supplier_template_item WHERE template_id=?", id);
        }
        for (var line : s.items()) {
            if (line.materialCost() == null && line.laborCost() == null && line.financeCost() == null) continue;
            db.one("SELECT id FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                "INSERT INTO supplier_template_item(id,template_id,product_id,material_cost,labor_cost,finance_cost) VALUES(?,?,?,?,?,?)",
                db.id(),
                id,
                line.productId(),
                line.materialCost(),
                line.laborCost(),
                line.financeCost()
            );
        }
        db.audit("SUPPLIER_TEMPLATE_SAVED", id, supplier.get("name") + " 成本模板，" + s.items().size() + " 项");
        return id;
    }

    /**
     * 从历史报价推荐标准配置：取该业务线各门店的报价（已确认的优先，否则最新版本），
     * 按“产品 + 数量”组合统计出现次数，返回最常见的几种配置；每个产品的售价取该配置下出现最多的单价（并列取最近的）。
     */
    public Map<String, Object> suggest(String brand) {
        String line = business.canonical(brand);
        var rows = db.jdbc.queryForList(
            """
            SELECT p.id AS project_id,q.id AS quote_id,q.status,q.version,q.created_at,
              i.product_id,i.quantity,i.unit_price,pr.name,pr.specification,pr.model
            FROM project p JOIN quotation q ON q.project_id=p.id JOIN quotation_item i ON i.quotation_id=q.id
            JOIN product pr ON pr.id=i.product_id
            WHERE p.brand=? AND q.status NOT IN ('LOST','SUPERSEDED') ORDER BY q.created_at
            """,
            line
        );
        // 每个门店只取一份报价：已确认的优先，否则版本号最大的
        var chosen = new HashMap<String, Map<String, Object>>();
        for (var r : rows) {
            var cur = chosen.get(r.get("project_id").toString());
            boolean accepted = "ACCEPTED".equals(r.get("status"));
            boolean better =
                cur == null ||
                (accepted && !"ACCEPTED".equals(cur.get("status"))) ||
                (accepted == "ACCEPTED".equals(cur.get("status")) &&
                    ((Number) r.get("version")).intValue() > ((Number) cur.get("version")).intValue());
            if (better) chosen.put(r.get("project_id").toString(), r);
        }
        var quoteIds = new HashSet<String>();
        for (var c : chosen.values()) quoteIds.add(c.get("quote_id").toString());
        var lines = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var r : rows)
            if (quoteIds.contains(r.get("quote_id").toString())) lines
                .computeIfAbsent(r.get("quote_id").toString(), k -> new ArrayList<>())
                .add(r);
        // 按配置签名分组
        var groups = new LinkedHashMap<String, List<List<Map<String, Object>>>>();
        for (var items : lines.values()) {
            var sig = items
                .stream()
                .map(
                    i ->
                        i.get("product_id") +
                        "x" +
                        new BigDecimal(i.get("quantity").toString()).stripTrailingZeros().toPlainString()
                )
                .sorted()
                .toList();
            groups.computeIfAbsent(String.join("|", sig), k -> new ArrayList<>()).add(items);
        }
        var ranked = new ArrayList<>(groups.values());
        ranked.sort((a, b) -> b.size() - a.size());
        var options = new ArrayList<Map<String, Object>>();
        for (var g : ranked.subList(0, Math.min(3, ranked.size()))) {
            var items = new ArrayList<Map<String, Object>>();
            for (var first : g.get(0)) {
                String productId = first.get("product_id").toString();
                // 该配置下此产品各单价出现次数，取最多的；并列取最近的
                var counts = new LinkedHashMap<String, Integer>();
                for (var quote : g)
                    for (var i : quote)
                        if (productId.equals(i.get("product_id")) && i.get("unit_price") != null) counts.merge(
                            new BigDecimal(i.get("unit_price").toString()).stripTrailingZeros().toPlainString(),
                            1,
                            Integer::sum
                        );
                String price = null;
                int best = 0;
                for (var e : counts.entrySet())
                    if (e.getValue() >= best) {
                        best = e.getValue();
                        price = e.getKey();
                    }
                var item = new LinkedHashMap<String, Object>();
                item.put("product_id", productId);
                item.put("name", first.get("name"));
                item.put("specification", first.get("specification"));
                item.put("model", first.get("model"));
                item.put("quantity", first.get("quantity"));
                item.put("unit_price", price == null ? null : new BigDecimal(price));
                items.add(item);
            }
            var option = new LinkedHashMap<String, Object>();
            option.put("stores", g.size());
            option.put("items", items);
            options.add(option);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("brand", line);
        out.put("stores", lines.size());
        out.put("options", options);
        return out;
    }

    /** 供应商成本模板中各产品的成本（产品 ID → 材料 / 人工 / 财务成本）；没有模板返回空。 */
    public Map<String, Map<String, Object>> supplierCosts(String supplierId, String typeId) {
        var out = new HashMap<String, Map<String, Object>>();
        if (supplierId == null || typeId == null) return out;
        for (var c : db.jdbc.queryForList(
            """
            SELECT i.* FROM supplier_template_item i JOIN supplier_template t ON t.id=i.template_id
            WHERE t.supplier_id=? AND t.project_type_id=? AND t.active=TRUE
            """,
            supplierId,
            typeId
        ))
            out.put(c.get("product_id").toString(), c);
        return out;
    }

    /**
     * 新建报价的预填明细：客户有报价模板时用模板的产品、数量、协议售价，否则用项目类型的标准配置；
     * 有供应商时带出该供应商模板中的成本拆分。供应商模板缺某产品时成本留空（待补充）。
     */
    public Map<String, Object> prefill(String typeId, String supplierId, String customerId) {
        var type = db.one("SELECT * FROM project_type WHERE id=?", typeId);
        var costs = new HashMap<String, Map<String, Object>>();
        Map<String, Object> template = null;
        if (supplierId != null && !supplierId.isBlank()) {
            var rows = db.jdbc.queryForList(
                "SELECT * FROM supplier_template WHERE supplier_id=? AND project_type_id=? AND active=TRUE",
                supplierId,
                typeId
            );
            if (rows.isEmpty()) throw new ApiException("该供应商还没有此项目类型的成本模板");
            template = rows.get(0);
            for (var c : db.jdbc.queryForList(
                "SELECT * FROM supplier_template_item WHERE template_id=?",
                template.get("id")
            ))
                costs.put(c.get("product_id").toString(), c);
        }
        var lines = new ArrayList<Map<String, Object>>();
        var customerTemplate = customerTemplates.active(customerId, typeId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> source = customerTemplate.isPresent()
            ? (List<Map<String, Object>>) customerTemplate.get().get("items")
            : db.jdbc.queryForList(
                  """
                  SELECT i.product_id,i.quantity,i.unit_price,i.bundle_id,i.bundle_name,i.bundle_sets,i.bundle_group,
                    p.name,p.specification,p.unit
                  FROM project_type_item i JOIN product p ON p.id=i.product_id WHERE i.project_type_id=? ORDER BY i.position
                  """,
                  typeId
              );
        for (var i : source) {
            var c = costs.get(i.get("product_id").toString());
            i.put("material_cost", c == null ? null : c.get("material_cost"));
            i.put("labor_cost", c == null ? null : c.get("labor_cost"));
            i.put("finance_cost", c == null ? null : c.get("finance_cost"));
            i.put("unit_cost", c == null ? null : total(c));
            lines.add(i);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("project_type", type);
        out.put("supplier_template_id", template == null ? null : template.get("id"));
        // 明细来源：CUSTOMER 客户报价模板；TYPE 项目类型标准配置
        out.put("source", customerTemplate.isPresent() ? "CUSTOMER" : "TYPE");
        out.put("customer_template_id", customerTemplate.map(t -> t.get("id")).orElse(null));
        out.put("items", lines);
        return out;
    }

    /** 单台成本 = 材料 + 人工 + 财务；三项都为空时返回空。 */
    static BigDecimal total(Map<String, Object> row) {
        BigDecimal sum = null;
        for (String k : List.of("material_cost", "labor_cost", "finance_cost")) {
            Object v = row.get(k);
            if (v == null) continue;
            sum = (sum == null ? BigDecimal.ZERO : sum).add(new BigDecimal(v.toString()));
        }
        return sum;
    }

    private static void requireDistinct(List<String> productIds) {
        if (new HashSet<>(productIds).size() != productIds.size()) throw new ApiException("同一产品不能重复出现");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
