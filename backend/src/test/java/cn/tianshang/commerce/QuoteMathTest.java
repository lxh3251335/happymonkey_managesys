package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.quote.QuoteMath;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuoteMathTest {

    @Test
    void decimalRoundingAndZeroDenominators() {
        assertThat(QuoteMath.line(new BigDecimal("3"), new BigDecimal("0.335"))).isEqualByComparingTo("1.01");
        var zero = QuoteMath.totals(List.of(Map.of("amount", BigDecimal.ZERO, "cost", BigDecimal.ZERO)));
        assertThat(zero.get("gross_margin")).isNull();
        assertThat(zero.get("cost_margin")).isNull();
        assertThat(zero.get("complete")).isEqualTo(true);
        var missing = new HashMap<String, Object>();
        missing.put("amount", new BigDecimal("100"));
        missing.put("cost", null);
        var incomplete = QuoteMath.totals(List.of(missing));
        assertThat(incomplete.get("profit")).isNull();
        assertThat(incomplete.get("complete")).isEqualTo(false);
    }
}
