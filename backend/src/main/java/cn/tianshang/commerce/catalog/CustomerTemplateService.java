package cn.tianshang.commerce.catalog;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户报价模板：某客户在某项目类型下的产品、数量与协议售价。
 *
 * <p>新建报价时，客户有模板就按模板带出，没有才用项目类型的标准配置。模板只管对客的产品与售价，
 * 成本仍由供应商成本模板核算，因此这里不涉及任何成本字段。
 */
@Service
public class CustomerTemplateService {

    private final Database db;
    private final Ownership ownership;

    public CustomerTemplateService(Database db, Ownership ownership) {
        this.db = db;
        this.ownership = ownership;
    }

    public List<Map<String, Object>> list() {
        var templates = db.jdbc.queryForList(
            """
            SELECT s.*,c.name AS customer_name,t.name AS project_type_name,t.brand
            FROM customer_template s JOIN partner c ON c.id=s.customer_id JOIN project_type t ON t.id=s.project_type_id
            ORDER BY s.active DESC,c.name,t.name
            """
        );
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList(
            """
            SELECT i.*,p.name,p.specification,p.model,p.unit FROM customer_template_item i
            JOIN product p ON p.id=i.product_id ORDER BY i.position
            """
        ))
            items.computeIfAbsent(i.get("template_id").toString(), k -> new ArrayList<>()).add(i);
        for (var t : templates) t.put("items", items.getOrDefault(t.get("id").toString(), List.of()));
        return templates;
    }

    public record Input(
        @NotBlank String customerId,
        @NotBlank String projectTypeId,
        @Size(max = 1000) String note,
        boolean active,
        @NotEmpty @Size(max = 100) List<CatalogService.@Valid TypeLine> items
    ) {}

    /** 同一客户、同一项目类型只保留一份模板：已存在则整体替换。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public String save(@Valid Input in) {
        var customer = db.one("SELECT * FROM partner WHERE id=?", in.customerId());
        if (!"CUSTOMER".equals(customer.get("kind"))) throw new ApiException("请选择客户");
        var type = db.one("SELECT * FROM project_type WHERE id=?", in.projectTypeId());
        var productIds = in.items().stream().map(CatalogService.TypeLine::productId).toList();
        if (new HashSet<>(productIds).size() != productIds.size()) throw new ApiException("同一产品不能重复出现");
        var existing = db.jdbc.queryForList(
            "SELECT id FROM customer_template WHERE customer_id=? AND project_type_id=? FOR UPDATE",
            in.customerId(),
            in.projectTypeId()
        );
        String id;
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (existing.isEmpty()) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO customer_template(id,customer_id,project_type_id,note,active) VALUES(?,?,?,?,?)",
                id,
                in.customerId(),
                in.projectTypeId(),
                note,
                in.active()
            );
        } else {
            id = existing.get(0).get("id").toString();
            db.jdbc.update(
                "UPDATE customer_template SET note=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                note,
                in.active(),
                id
            );
            db.jdbc.update("DELETE FROM customer_template_item WHERE template_id=?", id);
        }
        int pos = 0;
        for (var line : in.items()) {
            db.one("SELECT id FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                "INSERT INTO customer_template_item(id,template_id,product_id,position,quantity,unit_price) VALUES(?,?,?,?,?,?)",
                db.id(),
                id,
                line.productId(),
                ++pos,
                line.quantity(),
                line.unitPrice()
            );
        }
        db.audit(
            "CUSTOMER_TEMPLATE_SAVED",
            id,
            customer.get("name") + " · " + type.get("name") + " 报价模板，" + in.items().size() + " 项"
        );
        return id;
    }

    /** 该客户此项目类型下启用的模板明细（产品、数量、售价）；没有返回空。 */
    public Optional<Map<String, Object>> active(String customerId, String typeId) {
        if (customerId == null || customerId.isBlank() || typeId == null) return Optional.empty();
        var rows = db.jdbc.queryForList(
            "SELECT * FROM customer_template WHERE customer_id=? AND project_type_id=? AND active=TRUE",
            customerId,
            typeId
        );
        if (rows.isEmpty()) return Optional.empty();
        var template = rows.get(0);
        var items = db.jdbc.queryForList(
            """
            SELECT i.product_id,i.quantity,i.unit_price,p.name,p.specification,p.unit
            FROM customer_template_item i JOIN product p ON p.id=i.product_id WHERE i.template_id=? ORDER BY i.position
            """,
            template.get("id")
        );
        if (items.isEmpty()) return Optional.empty();
        template.put("items", items);
        return Optional.of(template);
    }

    /**
     * 从该客户的历史报价生成模板草稿：取此项目类型下最近一份已确认的报价（没有则取最近一份未关闭的报价）。
     * 门店没有登记项目类型的历史数据按业务线匹配。业务员只能取自己负责门店的报价。只返回产品、数量与售价。
     */
    public Map<String, Object> fromHistory(String customerId, String typeId) {
        var type = db.one("SELECT * FROM project_type WHERE id=?", typeId);
        var args = new ArrayList<Object>(List.of(customerId, typeId, type.get("brand")));
        String owner = "";
        if (ownership.restricted()) {
            owner = " AND p.owner_id=?";
            args.add(Objects.toString(ownership.userId(), "-"));
        }
        var quotes = db.jdbc.queryForList(
            """
                SELECT q.id,q.code,q.status,p.name AS project_name FROM quotation q JOIN project p ON p.id=q.project_id
                WHERE p.customer_id=? AND q.status<>'LOST'
                  AND (COALESCE(q.project_type_id,p.project_type_id)=?
                       OR (q.project_type_id IS NULL AND p.project_type_id IS NULL AND p.brand=?))
                """ +
                owner +
                " ORDER BY CASE WHEN q.status='ACCEPTED' THEN 0 ELSE 1 END,q.created_at DESC,q.version DESC",
            args.toArray()
        );
        if (quotes.isEmpty()) throw new ApiException("该客户在此项目类型下还没有报价可参考");
        var quote = quotes.get(0);
        var out = new LinkedHashMap<String, Object>();
        out.put("quote_code", quote.get("code"));
        out.put("project_name", quote.get("project_name"));
        out.put("confirmed", "ACCEPTED".equals(quote.get("status")));
        out.put(
            "items",
            db.jdbc.queryForList(
                """
                SELECT i.product_id,i.quantity,i.unit_price,i.name,i.specification,i.unit
                FROM quotation_item i WHERE i.quotation_id=? ORDER BY i.position
                """,
                quote.get("id")
            )
        );
        return out;
    }
}
