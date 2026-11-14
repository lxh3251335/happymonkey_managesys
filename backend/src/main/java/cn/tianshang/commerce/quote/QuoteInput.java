package cn.tianshang.commerce.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建报价。projectId 与 newProject 二选一：newProject 在同一事务中登记新门店。
 * 明细的单台成本：填写了材料/人工/财务任一项时按三项之和计算，否则取 unitCost。
 */
public record QuoteInput(
    @Size(max = 36) String projectId,
    @Valid NewProject newProject,
    @NotNull @Pattern(regexp = "INCLUDED|EXCLUDED|UNKNOWN") String taxBasis,
    @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
    @NotNull LocalDate validUntil,
    @Size(max = 2000) String note,
    @Size(max = 36) String templateId,
    @Size(max = 36) String supplierId,
    @Size(max = 36) String projectTypeId,
    @NotEmpty @Size(max = 200) List<@Valid Line> items
) {
    public record NewProject(
        @NotBlank String customerId,
        @NotBlank @Size(max = 300) String name,
        @Size(max = 50) String city,
        @NotBlank @Size(max = 36) String projectTypeId,
        @Size(max = 100) String contractNo,
        LocalDate orderDate
    ) {}

    public record Line(
        @NotBlank String productId,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal quantity,
        @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitPrice,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal materialCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal laborCost,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal financeCost
    ) {
        /** 单台成本：有拆分时为三项之和，否则为 unitCost；都没有则为空（待补充）。 */
        public BigDecimal effectiveCost() {
            if (materialCost == null && laborCost == null && financeCost == null) return unitCost;
            return zero(materialCost).add(zero(laborCost)).add(zero(financeCost));
        }

        private static BigDecimal zero(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }
}
