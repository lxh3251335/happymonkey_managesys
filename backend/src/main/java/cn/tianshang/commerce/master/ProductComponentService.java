package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 标准品的电气件清单（像菜品的原材料）：只记录组成，作为技术资料，和图纸一起看；不参与报价和成本。 */
@Service
public class ProductComponentService {

    private final Database db;

    public ProductComponentService(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> list(String productId) {
        db.one("SELECT id FROM product WHERE id=?", productId);
        return db.jdbc.queryForList(
            "SELECT id,name,model,brand,quantity,unit,note FROM product_component WHERE product_id=? ORDER BY position",
            productId
        );
    }

    public record Component(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 120) String model,
        @Size(max = 80) String brand,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal quantity,
        @Size(max = 20) String unit,
        @Size(max = 300) String note
    ) {}

    public record ComponentsInput(@NotNull @Size(max = 300) List<@Valid Component> items) {}

    /** 整张清单替换保存。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public void save(String productId, @Valid ComponentsInput in) {
        var product = db.one("SELECT id,name,specification FROM product WHERE id=? FOR UPDATE", productId);
        db.jdbc.update("DELETE FROM product_component WHERE product_id=?", productId);
        int pos = 0;
        for (var c : in.items())
            db.jdbc.update(
                "INSERT INTO product_component(id,product_id,position,name,model,brand,quantity,unit,note) VALUES(?,?,?,?,?,?,?,?,?)",
                db.id(),
                productId,
                ++pos,
                c.name().trim(),
                blank(c.model()),
                blank(c.brand()),
                c.quantity(),
                blank(c.unit()),
                blank(c.note())
            );
        db.audit(
            "PRODUCT_COMPONENTS_SAVED",
            productId,
            product.get("name") + " " + product.get("specification") + "：电气件 " + in.items().size() + " 项"
        );
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
