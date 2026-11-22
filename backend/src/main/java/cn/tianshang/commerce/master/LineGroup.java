package cn.tianshang.commerce.master;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 明细行属于哪个组合：同一组的行 no 相同。报价明细和项目类型标准配置共用。
 *
 * @param no 组号，同一份报价（或同一个项目类型）里区分不同的组，同一个组合加两次是两组
 * @param bundleId 组合
 * @param sets 套数；行的数量是总数量（每套数量 × 套数）
 */
public record LineGroup(
    @NotNull @Min(1) @Max(999) Integer no,
    @NotBlank @Size(max = 36) String bundleId,
    @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal sets
) {}
