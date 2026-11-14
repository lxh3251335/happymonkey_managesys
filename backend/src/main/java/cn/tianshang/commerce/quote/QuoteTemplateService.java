package cn.tianshang.commerce.quote;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 报价模板：对客报价单的公司抬头、联系方式、收款信息与标准条款。 */
@Service
public class QuoteTemplateService {

    /** 写入报价快照、对客输出的字段；不含内部状态字段。 */
    static final List<String> PRINT_FIELDS = List.of(
        "name",
        "company_name",
        "title",
        "address",
        "contact",
        "phone",
        "email",
        "tax_no",
        "bank_name",
        "bank_account",
        "terms",
        "footer"
    );

    private final Database db;

    public QuoteTemplateService(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> list() {
        return db.jdbc.queryForList(
            """
            SELECT t.*,(SELECT COUNT(*) FROM quotation q WHERE q.template_id=t.id) AS used
            FROM quote_template t ORDER BY t.is_default DESC,t.active DESC,t.name
            """
        );
    }

    public record TemplateInput(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Size(max = 100) String title,
        @Size(max = 300) String address,
        @Size(max = 100) String contact,
        @Size(max = 60) String phone,
        @Size(max = 120) String email,
        @Size(max = 60) String taxNo,
        @Size(max = 200) String bankName,
        @Size(max = 60) String bankAccount,
        @Size(max = 4000) String terms,
        @Size(max = 500) String footer,
        boolean active
    ) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String create(@Valid TemplateInput t) {
        String id = db.id();
        db.jdbc.update(
            """
            INSERT INTO quote_template(id,name,company_name,title,address,contact,phone,email,tax_no,bank_name,bank_account,terms,footer,active)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            values(id, t)
        );
        db.audit("TEMPLATE_CREATED", id, t.name().trim());
        return id;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void update(String id, @Valid TemplateInput t) {
        var current = db.one("SELECT * FROM quote_template WHERE id=? FOR UPDATE", id);
        if (Boolean.TRUE.equals(current.get("is_default")) && !t.active()) throw new ApiException(
            "默认模板不能停用，请先把其他模板设为默认"
        );
        var v = values(id, t);
        // 参数顺序：字段在前、id 在后
        var args = new ArrayList<Object>(Arrays.asList(v).subList(1, v.length));
        args.add(id);
        db.jdbc.update(
            """
            UPDATE quote_template SET name=?,company_name=?,title=?,address=?,contact=?,phone=?,email=?,tax_no=?,
            bank_name=?,bank_account=?,terms=?,footer=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
            """,
            args.toArray()
        );
        db.audit("TEMPLATE_UPDATED", id, t.name().trim() + "（已出具报价保留原快照）");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void makeDefault(String id) {
        var t = db.one("SELECT * FROM quote_template WHERE id=? FOR UPDATE", id);
        if (!Boolean.TRUE.equals(t.get("active"))) throw new ApiException("停用的模板不能设为默认");
        db.jdbc.update("UPDATE quote_template SET is_default=FALSE WHERE is_default=TRUE");
        db.jdbc.update("UPDATE quote_template SET is_default=TRUE WHERE id=?", id);
        db.audit("TEMPLATE_DEFAULT", id, db.text(t, "name"));
    }

    /** 新建报价时选用的模板：未指定则用默认模板；指定的模板必须启用。 */
    String resolveForNewQuote(String templateId) {
        if (templateId == null || templateId.isBlank()) return defaultId();
        var rows = db.jdbc.queryForList("SELECT id FROM quote_template WHERE id=? AND active=TRUE", templateId);
        if (rows.isEmpty()) throw new ApiException("报价模板不存在或已停用");
        return templateId;
    }

    String defaultId() {
        var rows = db.jdbc.queryForList("SELECT id FROM quote_template WHERE is_default=TRUE");
        return rows.isEmpty() ? null : rows.get(0).get("id").toString();
    }

    /** 模板的对客字段；模板缺失时返回空。 */
    Map<String, Object> printable(String templateId) {
        String id = templateId == null ? defaultId() : templateId;
        if (id == null) return null;
        var rows = db.jdbc.queryForList("SELECT * FROM quote_template WHERE id=?", id);
        if (rows.isEmpty()) return null;
        var out = new LinkedHashMap<String, Object>();
        for (String key : PRINT_FIELDS) out.put(key, rows.get(0).get(key));
        return out;
    }

    private static Object[] values(String id, TemplateInput t) {
        return new Object[] {
            id,
            t.name().trim(),
            t.companyName().trim(),
            t.title().trim(),
            blank(t.address()),
            blank(t.contact()),
            blank(t.phone()),
            blank(t.email()),
            blank(t.taxNo()),
            blank(t.bankName()),
            blank(t.bankAccount()),
            blank(t.terms()),
            blank(t.footer()),
            t.active(),
        };
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
