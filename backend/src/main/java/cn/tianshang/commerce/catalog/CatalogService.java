package cn.tianshang.commerce.catalog;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报价配置：项目类型（门店标准配置 + 对客标准售价）与供应商成本模板（材料 + 团队人工 + 财务成本）。
 *
 * <p>新建报价时选项目类型和供应商，按类型带出产品、数量、售价，按供应商模板带出成本；报价中可逐行修改。
 */
@Service
public class CatalogService {

    private final Database db;

    public CatalogService(Database db) {
        this.db = db;
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
            SELECT i.*,p.name,p.specification,p.model,p.unit,p.brand AS product_brand
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
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitPrice
    ) {}

    public record TypeInput(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Pattern(regexp = "小象|快乐猴|其他|未指定") String brand,
        @Size(max = 1000) String note,
        boolean active,
        @NotNull @Size(max = 100) List<@Valid TypeLine> items
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public String saveType(String id, @Valid TypeInput t) {
        requireDistinct(t.items().stream().map(TypeLine::productId).toList());
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO project_type(id,name,brand,note,active) VALUES(?,?,?,?,?)",
                id,
                t.name().trim(),
                t.brand(),
                blank(t.note()),
                t.active()
            );
        } else {
            db.one("SELECT id FROM project_type WHERE id=? FOR UPDATE", id);
            db.jdbc.update(
                "UPDATE project_type SET name=?,brand=?,note=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                t.name().trim(),
                t.brand(),
                blank(t.note()),
                t.active(),
                id
            );
            db.jdbc.update("DELETE FROM project_type_item WHERE project_type_id=?", id);
        }
        int pos = 0;
        for (var line : t.items()) {
            db.one("SELECT id FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                "INSERT INTO project_type_item(id,project_type_id,product_id,position,quantity,unit_price) VALUES(?,?,?,?,?,?)",
                db.id(),
                id,
                line.productId(),
                ++pos,
                line.quantity(),
                line.unitPrice()
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

    /** 同一供应商、同一项目类型只保留一份模板：已存在则更新。 */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
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
     * 新建报价的预填明细：项目类型的产品、数量、标准售价；有供应商时带出该供应商模板中的成本拆分。
     * 供应商模板缺某产品时成本留空（待补充）。
     */
    public Map<String, Object> prefill(String typeId, String supplierId) {
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
        for (var i : db.jdbc.queryForList(
            """
            SELECT i.product_id,i.quantity,i.unit_price,p.name,p.specification,p.unit
            FROM project_type_item i JOIN product p ON p.id=i.product_id WHERE i.project_type_id=? ORDER BY i.position
            """,
            typeId
        )) {
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
