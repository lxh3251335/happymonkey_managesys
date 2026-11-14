package cn.tianshang.commerce.quote;

import cn.tianshang.commerce.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {

    private final Database db;
    private final QuoteTemplateService templates;
    private final ObjectMapper json;

    public QuoteService(Database db, QuoteTemplateService templates, ObjectMapper json) {
        this.db = db;
        this.templates = templates;
        this.json = json;
    }

    public List<Map<String, Object>> list() {
        var rows = db.jdbc.queryForList(
            """
            SELECT q.*,p.name AS project_name,p.brand,c.name AS customer_name
            FROM quotation q JOIN project p ON p.id=q.project_id JOIN partner c ON c.id=p.customer_id
            ORDER BY q.created_at DESC,q.code
            """
        );
        var grouped = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList("SELECT quotation_id,amount,cost FROM quotation_item"))
            grouped.computeIfAbsent(i.get("quotation_id").toString(), k -> new ArrayList<>()).add(i);
        for (var q : rows) q.putAll(QuoteMath.totals(grouped.getOrDefault(q.get("id").toString(), List.of())));
        return rows;
    }

    public Map<String, Object> detail(String id) {
        var q = db.one(
            """
            SELECT q.*,p.name AS project_name,p.brand,p.code AS project_code,p.external_id,
            c.name AS customer_name,t.name AS template_name FROM quotation q JOIN project p ON p.id=q.project_id
            JOIN partner c ON c.id=p.customer_id LEFT JOIN quote_template t ON t.id=q.template_id WHERE q.id=?
            """,
            id
        );
        var items = db.jdbc.queryForList("SELECT * FROM quotation_item WHERE quotation_id=? ORDER BY position", id);
        q.put("items", items);
        q.putAll(QuoteMath.totals(items));
        return q;
    }

    @Transactional
    public String create(QuoteInput input) {
        db.one("SELECT id FROM project WHERE id=? FOR UPDATE", input.projectId());
        if (input.validUntil().isBefore(LocalDate.now())) throw new ApiException("报价有效期不能早于今天");
        if (!input.taxBasis().equals("UNKNOWN") && input.taxRate() == null) throw new ApiException("请填写税率");
        String templateId = templates.resolveForNewQuote(input.templateId());
        int version = db.jdbc.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM quotation WHERE project_id=?",
            Integer.class,
            input.projectId()
        );
        String id = db.id(),
            code = "BJ-" + LocalDate.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        db.jdbc.update(
            "INSERT INTO quotation(id,code,project_id,version,status,tax_basis,tax_rate,valid_until,note,template_id) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?)",
            id,
            code,
            input.projectId(),
            version,
            input.taxBasis(),
            input.taxRate(),
            input.validUntil(),
            input.note(),
            templateId
        );
        int pos = 0;
        for (var line : input.items()) {
            var product = db.one("SELECT * FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                """
                INSERT INTO quotation_item(id,quotation_id,product_id,position,name,specification,unit,quantity,unit_price,unit_cost,amount,cost)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                db.id(),
                id,
                line.productId(),
                ++pos,
                product.get("name"),
                product.get("specification"),
                product.get("unit"),
                line.quantity(),
                line.unitPrice(),
                line.unitCost(),
                QuoteMath.line(line.quantity(), line.unitPrice()),
                QuoteMath.line(line.quantity(), line.unitCost())
            );
        }
        db.audit("QUOTE_CREATED", id, "新建报价版本 V" + version);
        return id;
    }

    @Transactional
    public void transition(String id, String action, LocalDate dueDate) {
        var quote = db.one("SELECT * FROM quotation WHERE id=? FOR UPDATE", id);
        String status = quote.get("status").toString();
        if (action.equals("issue")) {
            if (!status.equals("DRAFT")) throw new ApiException("只有草稿可以出具");
            if (detail(id).get("amount") == null) throw new ApiException("报价金额不完整");
            // 出具时固定模板内容：之后修改模板不改变已出具的对客报价单
            db.jdbc.update(
                "UPDATE quotation SET status='ISSUED',template_snapshot=? WHERE id=?",
                snapshot(templates.printable((String) quote.get("template_id"))),
                id
            );
        } else if (action.equals("accept")) {
            if (!List.of("ISSUED", "HISTORICAL").contains(status)) throw new ApiException("仅已出具或历史报价可确认");
            if (dueDate == null) throw new ApiException("请填写应收截止日期");
            if (detail(id).get("amount") == null) throw new ApiException("报价金额不完整，无法确认应收");
            db.one("SELECT id FROM project WHERE id=? FOR UPDATE", quote.get("project_id"));
            db.jdbc.update(
                "UPDATE quotation SET status='ACCEPTED',due_date=?,accepted_project=project_id WHERE id=?",
                dueDate,
                id
            );
        } else throw new ApiException("不支持此操作");
        db.audit("QUOTE_" + action.toUpperCase(), id, "状态变更自 " + status);
    }

    public Map<String, Object> customerCopy(String id) {
        var original = detail(id);
        if (original.get("status").equals("DRAFT")) throw new ApiException("请先出具报价单");
        var out = new LinkedHashMap<String, Object>();
        for (String key : List.of(
            "id",
            "code",
            "project_name",
            "customer_name",
            "brand",
            "version",
            "tax_basis",
            "tax_rate",
            "valid_until",
            "amount",
            "note",
            "status"
        ))
            out.put(key, original.get(key));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) original.get("items");
        out.put("template", printTemplate(original));
        out.put(
            "items",
            items
                .stream()
                .map(i -> {
                    var line = new LinkedHashMap<String, Object>();
                    for (String key : List.of("name", "specification", "unit", "quantity", "unit_price", "amount"))
                        line.put(key, i.get(key));
                    return line;
                })
                .toList()
        );
        return out;
    }

    /** 已出具报价使用出具时的快照；无快照的历史报价使用当前模板或默认模板。 */
    private Map<String, Object> printTemplate(Map<String, Object> quote) {
        Object snap = quote.get("template_snapshot");
        if (snap != null) {
            try {
                return json.readValue(snap.toString(), new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("报价模板快照损坏：" + quote.get("id"), e);
            }
        }
        return templates.printable((String) quote.get("template_id"));
    }

    private String snapshot(Map<String, Object> template) {
        if (template == null) return null;
        try {
            return json.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
