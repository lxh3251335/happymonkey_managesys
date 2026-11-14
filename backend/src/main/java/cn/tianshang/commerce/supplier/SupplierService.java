package cn.tianshang.commerce.supplier;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {

    private final Database db;

    public SupplierService(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> offers() {
        return db.jdbc.queryForList(
            """
            SELECT o.*,s.name AS supplier_name,p.name AS product_name,p.specification,p.brand,p.model
            FROM supplier_offer o JOIN partner s ON s.id=o.supplier_id JOIN product p ON p.id=o.product_id
            ORDER BY o.quoted_on DESC,o.id
            """
        );
    }

    public record OfferInput(
        @NotBlank String supplierId,
        @NotBlank String productId,
        @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitPrice,
        @NotNull @Pattern(regexp = "INCLUDED|EXCLUDED") String taxBasis,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
        @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 2) BigDecimal freight,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal minQuantity,
        @Min(0) @Max(3650) int leadDays,
        @NotNull LocalDate quotedOn,
        @NotNull LocalDate validUntil,
        @Size(max = 1000) String note
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    @Transactional
    public Map<String, String> create(@Valid OfferInput o) {
        db.one("SELECT id FROM partner WHERE id=? AND kind='SUPPLIER'", o.supplierId());
        db.one("SELECT id FROM product WHERE id=?", o.productId());
        if (o.validUntil().isBefore(o.quotedOn())) throw new ApiException("有效期不得早于报价日期");
        if (o.quotedOn().isAfter(LocalDate.now())) throw new ApiException("报价日期不能晚于今天");
        String id = db.id();
        db.jdbc.update(
            """
            INSERT INTO supplier_offer(id,supplier_id,product_id,unit_price,tax_basis,tax_rate,freight,min_quantity,lead_days,quoted_on,valid_until,note)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            o.supplierId(),
            o.productId(),
            o.unitPrice(),
            o.taxBasis(),
            o.taxRate(),
            o.freight(),
            o.minQuantity(),
            o.leadDays(),
            o.quotedOn(),
            o.validUntil(),
            o.note()
        );
        db.audit("SUPPLIER_OFFER_CREATED", id, "供应商报价快照");
        return Map.of("id", id);
    }

    public List<Map<String, Object>> compare(String productId, BigDecimal quantity) {
        if (quantity.signum() <= 0 || quantity.compareTo(new BigDecimal("999999")) > 0) throw new ApiException(
            "请输入有效比价数量"
        );
        var rows = offers()
            .stream()
            .filter(o -> o.get("product_id").equals(productId))
            .toList();
        for (var o : rows) {
            var price = (BigDecimal) o.get("unit_price");
            var rate = (BigDecimal) o.get("tax_rate");
            if (o.get("tax_basis").equals("EXCLUDED")) price = price.multiply(BigDecimal.ONE.add(rate));
            var total = price.multiply(quantity).add((BigDecimal) o.get("freight")).setScale(2, RoundingMode.HALF_UP);
            boolean eligible =
                !LocalDate.parse(o.get("valid_until").toString()).isBefore(LocalDate.now()) &&
                !LocalDate.parse(o.get("quoted_on").toString()).isAfter(LocalDate.now()) &&
                quantity.compareTo((BigDecimal) o.get("min_quantity")) >= 0;
            o.put("eligible", eligible);
            o.put("landed_total", total);
            o.put("landed_unit", total.divide(quantity, 2, RoundingMode.HALF_UP));
        }
        return rows
            .stream()
            .sorted(
                Comparator.<Map<String, Object>, Boolean>comparing(o -> !(Boolean) o.get("eligible")).thenComparing(o ->
                    (BigDecimal) o.get("landed_total")
                )
            )
            .toList();
    }
}
