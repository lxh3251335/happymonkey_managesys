package cn.tianshang.commerce.master;

import cn.tianshang.commerce.business.BusinessService;
import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.Contracts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MasterService {

    private final Database db;
    private final BusinessService business;

    public MasterService(Database db, BusinessService business) {
        this.db = db;
        this.business = business;
    }

    public List<Map<String, Object>> partners() {
        return db.jdbc.queryForList("SELECT * FROM partner ORDER BY kind,name");
    }

    /** 客户 / 供应商。开票信息（抬头、税号、地址电话、开户行账号、发票类型）用于合同与开票。 */
    public record PartnerInput(
        @NotBlank @Size(max = 200) String name,
        @Pattern(regexp = "CUSTOMER|SUPPLIER") @NotNull String kind,
        @Size(max = 100) String contact,
        @Size(max = 60) String phone,
        @Size(max = 1000) String note,
        @Size(max = 200) String invoiceTitle,
        @Size(max = 30) String taxNo,
        @Size(max = 300) String address,
        @Size(max = 60) String invoicePhone,
        @Size(max = 200) String bankName,
        @Size(max = 40) @Pattern(
            regexp = "^$|[0-9\\- ]{6,40}",
            message = "银行账号只能包含数字、空格和短横线"
        ) String bankAccount,
        @Pattern(regexp = "^$|SPECIAL|NORMAL") String invoiceType
    ) {}

    /** 开票相关字段按插入/更新顺序排列：抬头、税号、地址、电话、开户行、账号、发票类型。 */
    private Object[] invoiceValues(PartnerInput p) {
        return new Object[] {
            blankToNull(p.invoiceTitle()),
            TaxNumbers.normalize(p.taxNo()),
            blankToNull(p.address()),
            blankToNull(p.invoicePhone()),
            blankToNull(p.bankName()),
            blankToNull(p.bankAccount()),
            blankToNull(p.invoiceType()),
        };
    }

    private static Object[] concat(Object[] a, Object... b) {
        var out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> partner(@Valid PartnerInput p) {
        String id = db.id();
        db.jdbc.update(
            """
            INSERT INTO partner(id,name,kind,contact,phone,note,invoice_title,tax_no,address,invoice_phone,bank_name,bank_account,invoice_type)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            concat(new Object[] { id, p.name().trim(), p.kind(), p.contact(), p.phone(), p.note() }, invoiceValues(p))
        );
        db.audit("PARTNER_CREATED", id, p.name());
        return Map.of("id", id);
    }

    public List<Map<String, Object>> projects() {
        return db.jdbc.queryForList(
            """
            SELECT p.*, c.name AS customer_name, t.name AS project_type_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id) AS quote_count
            FROM project p JOIN partner c ON c.id=p.customer_id LEFT JOIN project_type t ON t.id=p.project_type_id
            ORDER BY p.created_at DESC,p.code
            """
        );
    }

    public record ProjectInput(
        @NotBlank @Size(max = 300) String name,
        @NotBlank @Size(max = 30) String brand,
        @NotBlank String customerId,
        @Size(max = 150) String externalId,
        LocalDate orderDate,
        @Size(max = 2000) String note,
        @Size(max = 100) String contractNo,
        @Pattern(regexp = "^$|制作中|已发货") String shippingStatus,
        @Size(max = 50) String city,
        @Size(max = 36) String projectTypeId
    ) {}

    /** 城市未填时按门店名称识别。 */
    String city(ProjectInput p) {
        String c = blankToNull(p.city());
        return c != null ? c : Cities.detect(p.name());
    }

    private String projectType(String id) {
        String v = blankToNull(id);
        if (v != null) db.one("SELECT id FROM project_type WHERE id=?", v);
        return v;
    }

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> project(@Valid ProjectInput p) {
        requireCustomer(p.customerId());
        String id = db.id(),
            code = "XM-" + LocalDate.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        db.jdbc.update(
            "INSERT INTO project(id,code,name,brand,customer_id,external_id,order_date,note,contract_no,shipping_status,city,project_type_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id,
            code,
            p.name().trim(),
            business.resolve(p.brand(), null),
            p.customerId(),
            blankToNull(p.externalId()),
            p.orderDate(),
            p.note(),
            Contracts.blankToNull(p.contractNo()),
            Contracts.shipping(p.shippingStatus()),
            city(p),
            projectType(p.projectTypeId())
        );
        db.audit("PROJECT_CREATED", id, p.name());
        return Map.of("id", id);
    }

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public void updateProject(String id, @Valid ProjectInput p) {
        var original = db.one("SELECT * FROM project WHERE id=?", id);
        requireCustomer(p.customerId());
        if (!original.get("customer_id").equals(p.customerId())) throw new ApiException(
            "已登记项目不允许更换客户，请新建项目"
        );
        db.jdbc.update(
            "UPDATE project SET name=?,brand=?,external_id=?,order_date=?,note=?,contract_no=?,shipping_status=?,city=?,project_type_id=? WHERE id=?",
            p.name().trim(),
            business.resolve(p.brand(), db.text(original, "brand")),
            blankToNull(p.externalId()),
            p.orderDate(),
            p.note(),
            Contracts.blankToNull(p.contractNo()),
            Contracts.shipping(p.shippingStatus()),
            city(p),
            projectType(p.projectTypeId()),
            id
        );
        db.audit("PROJECT_UPDATED", id, "更新项目信息及云智冷关联ID");
    }

    public List<Map<String, Object>> products() {
        return db.jdbc.queryForList(
            """
            SELECT p.*,s.name AS supplier_name,
            (SELECT COUNT(*) FROM product_drawing d WHERE d.product_id=p.id AND d.deleted_at IS NULL) AS drawings,
            (SELECT i.unit_price FROM quotation_item i JOIN quotation q ON q.id=i.quotation_id
               WHERE i.product_id=p.id AND i.unit_price IS NOT NULL ORDER BY q.created_at DESC,i.id LIMIT 1) AS reference_price,
            (SELECT i.unit_cost FROM quotation_item i JOIN quotation q ON q.id=i.quotation_id
               WHERE i.product_id=p.id AND i.unit_cost IS NOT NULL ORDER BY q.created_at DESC,i.id LIMIT 1) AS reference_cost
            FROM product p LEFT JOIN partner s ON s.id=p.supplier_id
            ORDER BY p.mainstream DESC,p.scenario,p.name,p.specification
            """
        );
    }

    /** 标准产品：应用场景（如小象前置仓、快乐猴、冷库）、默认供应商、主流配置标签。 */
    public record ProductInput(
        @NotBlank @Size(max = 60) String scenario,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String specification,
        @Size(max = 100) String model,
        @NotBlank @Size(max = 30) String unit,
        @Size(max = 36) String supplierId,
        boolean mainstream
    ) {}

    private String supplier(String id) {
        String v = blankToNull(id);
        if (v != null) db.one("SELECT id FROM partner WHERE id=? AND kind='SUPPLIER'", v);
        return v;
    }

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> product(@Valid ProductInput p) {
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO product(id,scenario,name,specification,model,unit,supplier_id,mainstream) VALUES(?,?,?,?,?,?,?,?)",
            id,
            p.scenario().trim(),
            p.name().trim(),
            Objects.toString(p.specification(), "").trim(),
            Objects.toString(p.model(), "").trim(),
            p.unit(),
            supplier(p.supplierId()),
            p.mainstream()
        );
        db.audit("PRODUCT_CREATED", id, p.name());
        return Map.of("id", id);
    }

    private void requireCustomer(String id) {
        db.one("SELECT id FROM partner WHERE id=? AND kind='CUSTOMER'", id);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 修改客户/供应商资料。类别不可更改，避免已有项目或报价关联错位。 */
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public void updatePartner(String id, @Valid PartnerInput p) {
        var original = db.one("SELECT * FROM partner WHERE id=? FOR UPDATE", id);
        if (!original.get("kind").equals(p.kind())) throw new ApiException("不能更改客户/供应商类别，请新建资料");
        var invoice = invoiceValues(p);
        db.jdbc.update(
            """
            UPDATE partner SET name=?,contact=?,phone=?,note=?,invoice_title=?,tax_no=?,address=?,invoice_phone=?,
            bank_name=?,bank_account=?,invoice_type=? WHERE id=?
            """,
            concat(concat(new Object[] { p.name().trim(), p.contact(), p.phone(), p.note() }, invoice), id)
        );
        // 审计记录开票信息中改动的字段
        String[] cols = {
            "invoice_title",
            "tax_no",
            "address",
            "invoice_phone",
            "bank_name",
            "bank_account",
            "invoice_type",
        };
        String[] labels = { "开票抬头", "税号", "地址", "开票电话", "开户行", "账号", "发票类型" };
        var changed = new ArrayList<String>();
        for (int i = 0; i < cols.length; i++) if (
            !Objects.equals(original.get(cols[i]) == null ? null : original.get(cols[i]).toString(), invoice[i])
        ) changed.add(labels[i]);
        db.audit(
            "PARTNER_UPDATED",
            id,
            db.text(original, "name") +
                " → " +
                p.name().trim() +
                (changed.isEmpty() ? "" : "；开票信息变更：" + String.join("、", changed))
        );
    }

    /** 修改标准产品。已保存的报价明细保留当时的品名规格快照，不受影响。 */
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public void updateProduct(String id, @Valid ProductInput p) {
        var original = db.one("SELECT * FROM product WHERE id=? FOR UPDATE", id);
        db.jdbc.update(
            "UPDATE product SET scenario=?,name=?,specification=?,model=?,unit=?,supplier_id=?,mainstream=? WHERE id=?",
            p.scenario().trim(),
            p.name().trim(),
            Objects.toString(p.specification(), "").trim(),
            Objects.toString(p.model(), "").trim(),
            p.unit(),
            supplier(p.supplierId()),
            p.mainstream(),
            id
        );
        db.audit(
            "PRODUCT_UPDATED",
            id,
            db.text(original, "name") +
                "/" +
                db.text(original, "specification") +
                " → " +
                p.name() +
                "/" +
                p.specification()
        );
    }

    /** 列表中直接切换主流配置标签。 */
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public void setMainstream(String id, boolean mainstream) {
        var p = db.one("SELECT name,specification FROM product WHERE id=? FOR UPDATE", id);
        db.jdbc.update("UPDATE product SET mainstream=? WHERE id=?", mainstream, id);
        db.audit(
            "PRODUCT_MAINSTREAM",
            id,
            p.get("name") + "/" + p.get("specification") + (mainstream ? " 设为主流配置" : " 取消主流配置")
        );
    }
}
