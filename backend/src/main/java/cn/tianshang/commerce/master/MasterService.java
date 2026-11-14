package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.*;
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

    public MasterService(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> partners() {
        return db.jdbc.queryForList("SELECT * FROM partner ORDER BY kind,name");
    }

    public record PartnerInput(
        @NotBlank @Size(max = 200) String name,
        @Pattern(regexp = "CUSTOMER|SUPPLIER") @NotNull String kind,
        @Size(max = 100) String contact,
        @Size(max = 60) String phone,
        @Size(max = 1000) String note
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> partner(@Valid PartnerInput p) {
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO partner(id,name,kind,contact,phone,note) VALUES(?,?,?,?,?,?)",
            id,
            p.name().trim(),
            p.kind(),
            p.contact(),
            p.phone(),
            p.note()
        );
        db.audit("PARTNER_CREATED", id, p.name());
        return Map.of("id", id);
    }

    public List<Map<String, Object>> projects() {
        return db.jdbc.queryForList(
            """
            SELECT p.*, c.name AS customer_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id) AS quote_count
            FROM project p JOIN partner c ON c.id=p.customer_id ORDER BY p.created_at DESC,p.code
            """
        );
    }

    public record ProjectInput(
        @NotBlank @Size(max = 300) String name,
        @NotNull @Pattern(regexp = "小象|快乐猴|其他|未指定") String brand,
        @NotBlank String customerId,
        @Size(max = 150) String externalId,
        LocalDate orderDate,
        @Size(max = 2000) String note
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> project(@Valid ProjectInput p) {
        requireCustomer(p.customerId());
        String id = db.id(),
            code = "XM-" + LocalDate.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        db.jdbc.update(
            "INSERT INTO project(id,code,name,brand,customer_id,external_id,order_date,note) VALUES(?,?,?,?,?,?,?,?)",
            id,
            code,
            p.name().trim(),
            p.brand(),
            p.customerId(),
            blankToNull(p.externalId()),
            p.orderDate(),
            p.note()
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
            "UPDATE project SET name=?,brand=?,external_id=?,order_date=?,note=? WHERE id=?",
            p.name().trim(),
            p.brand(),
            blankToNull(p.externalId()),
            p.orderDate(),
            p.note(),
            id
        );
        db.audit("PROJECT_UPDATED", id, "更新项目信息及云智冷关联ID");
    }

    public List<Map<String, Object>> products() {
        return db.jdbc.queryForList(
            """
            SELECT p.*,
            (SELECT i.unit_price FROM quotation_item i JOIN quotation q ON q.id=i.quotation_id
               WHERE i.product_id=p.id AND i.unit_price IS NOT NULL ORDER BY q.created_at DESC,i.id LIMIT 1) AS reference_price,
            (SELECT i.unit_cost FROM quotation_item i JOIN quotation q ON q.id=i.quotation_id
               WHERE i.product_id=p.id AND i.unit_cost IS NOT NULL ORDER BY q.created_at DESC,i.id LIMIT 1) AS reference_cost
            FROM product p ORDER BY p.brand,p.name,p.specification
            """
        );
    }

    public record ProductInput(
        @NotBlank @Size(max = 30) String brand,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String specification,
        @Size(max = 100) String model,
        @NotBlank @Size(max = 30) String unit
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> product(@Valid ProductInput p) {
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO product(id,brand,name,specification,model,unit) VALUES(?,?,?,?,?,?)",
            id,
            p.brand(),
            p.name(),
            p.specification(),
            Objects.toString(p.model(), ""),
            p.unit()
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
}
