package cn.tianshang.commerce.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record QuoteInput(
    @NotBlank String projectId,
    @NotNull @Pattern(regexp = "INCLUDED|EXCLUDED|UNKNOWN") String taxBasis,
    @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
    @NotNull LocalDate validUntil,
    @Size(max = 2000) String note,
    @NotEmpty @Size(max = 200) List<@Valid Line> items
) {
    public record Line(
        @NotBlank String productId,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal quantity,
        @NotNull @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitPrice,
        @DecimalMin("0") @DecimalMax("999999999") @Digits(integer = 9, fraction = 6) BigDecimal unitCost
    ) {}
}
