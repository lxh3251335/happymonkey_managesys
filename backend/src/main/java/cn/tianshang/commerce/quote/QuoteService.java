package cn.tianshang.commerce.quote;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.master.MasterService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {

    private final Database db;
    private final QuoteTemplateService templates;
    private final ObjectMapper json;
    private final MasterService masters;
    private final Ownership ownership;
    private final cn.tianshang.commerce.dictionary.DictionaryService dictionary;

    public QuoteService(
        Database db,
        QuoteTemplateService templates,
        ObjectMapper json,
        @Lazy MasterService masters,
        Ownership ownership,
        cn.tianshang.commerce.dictionary.DictionaryService dictionary
    ) {
        this.db = db;
        this.templates = templates;
        this.json = json;
        this.masters = masters;
        this.ownership = ownership;
        this.dictionary = dictionary;
    }

    /** 报价列表；商务只看到自己负责门店的报价。 */
    public List<Map<String, Object>> list() {
        String sql = """
            SELECT q.*,p.name AS project_name,p.brand,p.owner_id,p.contract_no,c.name AS customer_name,
              u.display_name AS owner_name,k.name AS company_name
            FROM quotation q JOIN project p ON p.id=q.project_id JOIN partner c ON c.id=p.customer_id
            LEFT JOIN app_user u ON u.id=p.owner_id LEFT JOIN company k ON k.id=q.company_id
            """;
        var rows = ownership.restricted()
            ? db.jdbc.queryForList(
                  sql + " WHERE p.owner_id=? ORDER BY q.created_at DESC,q.code",
                  Objects.toString(ownership.userId(), "-")
              )
            : db.jdbc.queryForList(sql + " ORDER BY q.created_at DESC,q.code");
        var grouped = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList("SELECT quotation_id,amount,cost FROM quotation_item"))
            grouped.computeIfAbsent(i.get("quotation_id").toString(), k -> new ArrayList<>()).add(i);
        for (var q : rows) q.putAll(QuoteMath.totals(grouped.getOrDefault(q.get("id").toString(), List.of())));
        return rows;
    }

    public Map<String, Object> detail(String id) {
        ownership.checkQuote(id);
        var q = db.one(
            """
            SELECT q.*,p.name AS project_name,p.brand,p.code AS project_code,p.external_id,
            p.city,p.contract_no,c.name AS customer_name,c.contact AS customer_contact,c.phone AS customer_phone,
            c.invoice_title AS customer_invoice_title,c.tax_no AS customer_tax_no,c.address AS customer_address,
            c.invoice_phone AS customer_invoice_phone,c.bank_name AS customer_bank_name,c.bank_account AS customer_bank_account,
            t.name AS template_name,s.name AS supplier_name,pt.name AS project_type_name,
            p.owner_id,u.display_name AS owner_name,k.name AS company_name
            FROM quotation q JOIN project p ON p.id=q.project_id LEFT JOIN app_user u ON u.id=p.owner_id
            LEFT JOIN company k ON k.id=q.company_id
            JOIN partner c ON c.id=p.customer_id LEFT JOIN quote_template t ON t.id=q.template_id
            LEFT JOIN partner s ON s.id=q.supplier_id LEFT JOIN project_type pt ON pt.id=q.project_type_id WHERE q.id=?
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
        String projectId = input.projectId() == null || input.projectId().isBlank() ? null : input.projectId();
        String projectTypeId = blank(input.projectTypeId());
        if (projectId == null && input.newProject() == null) throw new ApiException("请选择项目或登记新门店");
        if (projectId != null && input.newProject() != null) throw new ApiException("已选择项目时不能同时登记新门店");
        if (input.validUntil().isBefore(LocalDate.now())) throw new ApiException("报价有效期不能早于今天");
        if (!input.taxBasis().equals("UNKNOWN") && input.taxRate() == null) throw new ApiException("请填写税率");
        if (input.newProject() != null) {
            var n = input.newProject();
            var type = db.one("SELECT * FROM project_type WHERE id=?", n.projectTypeId());
            projectId = masters
                .project(
                    new MasterService.ProjectInput(
                        n.name(),
                        type.get("brand").toString(),
                        n.customerId(),
                        null,
                        n.orderDate(),
                        null,
                        n.contractNo(),
                        null,
                        n.city(),
                        n.projectTypeId()
                    )
                )
                .get("id");
            if (projectTypeId == null) projectTypeId = n.projectTypeId();
        }
        var project = db.one("SELECT * FROM project WHERE id=? FOR UPDATE", projectId);
        ownership.checkProject(projectId);
        // 只有明确选择签约主体时才覆盖报价模板的公司抬头
        String companyId = blank(input.companyId()) == null ? null : dictionary.company(input.companyId(), null);
        if (projectTypeId == null && project.get("project_type_id") != null) projectTypeId = project
            .get("project_type_id")
            .toString();
        if (projectTypeId != null) db.one("SELECT id FROM project_type WHERE id=?", projectTypeId);
        String supplierId = blank(input.supplierId());
        if (
            supplierId != null &&
            !"SUPPLIER".equals(db.one("SELECT kind FROM partner WHERE id=?", supplierId).get("kind"))
        ) throw new ApiException("请选择供应商");
        if (input.validUntil().isBefore(LocalDate.now())) throw new ApiException("报价有效期不能早于今天");
        if (!input.taxBasis().equals("UNKNOWN") && input.taxRate() == null) throw new ApiException("请填写税率");
        String templateId = templates.resolveForNewQuote(input.templateId());
        int version = db.jdbc.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM quotation WHERE project_id=?",
            Integer.class,
            projectId
        );
        String id = db.id(),
            code = "BJ-" + LocalDate.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        db.jdbc.update(
            "INSERT INTO quotation(id,code,project_id,version,status,tax_basis,tax_rate,valid_until,note,template_id,supplier_id,project_type_id,company_id) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?)",
            id,
            code,
            projectId,
            version,
            input.taxBasis(),
            input.taxRate(),
            input.validUntil(),
            input.note(),
            templateId,
            supplierId,
            projectTypeId,
            companyId
        );
        int pos = 0;
        for (var line : input.items()) {
            var product = db.one("SELECT * FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                """
                INSERT INTO quotation_item(id,quotation_id,product_id,position,name,specification,unit,quantity,unit_price,unit_cost,amount,cost,
                  material_cost,labor_cost,finance_cost)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                line.effectiveCost(),
                QuoteMath.line(line.quantity(), line.unitPrice()),
                QuoteMath.line(line.quantity(), line.effectiveCost()),
                line.materialCost(),
                line.laborCost(),
                line.financeCost()
            );
        }
        db.audit("QUOTE_CREATED", id, "新建报价版本 V" + version);
        return id;
    }

    @Transactional
    public void transition(String id, String action, LocalDate dueDate) {
        ownership.checkQuote(id);
        var quote = db.one("SELECT * FROM quotation WHERE id=? FOR UPDATE", id);
        String status = quote.get("status").toString();
        if (action.equals("issue")) {
            if (!status.equals("DRAFT")) throw new ApiException("只有草稿可以出具");
            if (detail(id).get("amount") == null) throw new ApiException("报价金额不完整");
            // 出具时固定模板内容：之后修改模板不改变已出具的对客报价单
            db.jdbc.update(
                "UPDATE quotation SET status='ISSUED',template_snapshot=? WHERE id=?",
                snapshot(
                    withCompany(
                        templates.printable((String) quote.get("template_id")),
                        (String) quote.get("company_id")
                    )
                ),
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
            "status",
            "city",
            "contract_no",
            "project_code",
            "customer_contact",
            "customer_phone",
            "customer_invoice_title",
            "customer_tax_no",
            "customer_address",
            "customer_invoice_phone",
            "customer_bank_name",
            "customer_bank_account",
            "created_at"
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
                Map<String, Object> t = json.readValue(
                    snap.toString(),
                    new TypeReference<LinkedHashMap<String, Object>>() {}
                );
                // V4 前出具的快照没有合同条款：用当前模板补齐
                if (!t.containsKey("contract_terms")) {
                    var current = templates.printable((String) quote.get("template_id"));
                    t.put("contract_terms", current == null ? null : current.get("contract_terms"));
                }
                return t;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("报价模板快照损坏：" + quote.get("id"), e);
            }
        }
        return withCompany(templates.printable((String) quote.get("template_id")), (String) quote.get("company_id"));
    }

    /** 报价选择了签约主体时，抬头、税号、地址电话、收款账户使用签约主体的资料。 */
    private Map<String, Object> withCompany(Map<String, Object> template, String companyId) {
        var company = dictionary.companyRow(companyId);
        if (template == null || company == null) return template;
        var out = new LinkedHashMap<>(template);
        out.put("company_name", company.get("name"));
        for (String k : List.of("tax_no", "address", "phone", "bank_name", "bank_account"))
            if (company.get(k) != null) out.put(k, company.get(k));
        out.put("legal_rep", company.get("legal_rep"));
        return out;
    }

    private String snapshot(Map<String, Object> template) {
        if (template == null) return null;
        try {
            return json.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record CostFix(
        @NotBlank String itemId,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal materialCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal laborCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal financeCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitCost
    ) {
        BigDecimal effective() {
            return new QuoteInput.Line(
                "x",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                unitCost,
                materialCost,
                laborCost,
                financeCost
            ).effectiveCost();
        }
    }

    public record CostCorrection(@NotBlank @Size(max = 300) String reason, @NotEmpty List<@Valid CostFix> items) {}

    /**
     * 会计修正成本：可改任意状态报价的内部成本（材料/人工/财务），售价与对客报价单不变。每行变更记审计。
     */
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @Transactional
    public int correctCosts(String quoteId, @Valid CostCorrection c) {
        db.one("SELECT id FROM quotation WHERE id=? FOR UPDATE", quoteId);
        int changed = 0;
        for (var fix : c.items()) {
            var item = db.one("SELECT * FROM quotation_item WHERE id=? AND quotation_id=?", fix.itemId(), quoteId);
            var qty = new BigDecimal(item.get("quantity").toString());
            BigDecimal before = item.get("unit_cost") == null ? null : new BigDecimal(item.get("unit_cost").toString());
            BigDecimal after = fix.effective();
            boolean same =
                Objects.equals(num(item.get("material_cost")), num(fix.materialCost())) &&
                Objects.equals(num(item.get("labor_cost")), num(fix.laborCost())) &&
                Objects.equals(num(item.get("finance_cost")), num(fix.financeCost())) &&
                Objects.equals(num(before), num(after));
            if (same) continue;
            db.jdbc.update(
                "UPDATE quotation_item SET unit_cost=?,cost=?,material_cost=?,labor_cost=?,finance_cost=? WHERE id=?",
                after,
                QuoteMath.line(qty, after),
                fix.materialCost(),
                fix.laborCost(),
                fix.financeCost(),
                fix.itemId()
            );
            db.audit(
                "QUOTE_COST_CORRECTED",
                quoteId,
                item.get("name") +
                    " 单台成本 " +
                    (before == null ? "空" : before.stripTrailingZeros().toPlainString()) +
                    " → " +
                    (after == null ? "空" : after.stripTrailingZeros().toPlainString()) +
                    "；原因：" +
                    c.reason().trim()
            );
            changed++;
        }
        return changed;
    }

    private static String num(Object v) {
        return v == null ? null : new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
