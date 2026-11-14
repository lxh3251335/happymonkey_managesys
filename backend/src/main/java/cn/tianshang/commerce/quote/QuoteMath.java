package cn.tianshang.commerce.quote;

import java.math.*;
import java.util.*;

public final class QuoteMath {

    private QuoteMath() {}

    public static BigDecimal line(BigDecimal quantity, BigDecimal price) {
        return price == null ? null : quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }

    public static Map<String, Object> totals(List<Map<String, Object>> items) {
        BigDecimal revenue = BigDecimal.ZERO,
            cost = BigDecimal.ZERO;
        boolean completeRevenue = true,
            completeCost = true;
        for (var item : items) {
            if (item.get("amount") == null) completeRevenue = false;
            else revenue = revenue.add(new BigDecimal(item.get("amount").toString()));
            if (item.get("cost") == null) completeCost = false;
            else cost = cost.add(new BigDecimal(item.get("cost").toString()));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("amount", completeRevenue ? revenue : null);
        out.put("cost", completeCost ? cost : null);
        out.put("known_amount", revenue);
        out.put("known_cost", cost);
        BigDecimal profit = completeRevenue && completeCost ? revenue.subtract(cost) : null;
        out.put("profit", profit);
        out.put(
            "gross_margin",
            profit != null && revenue.signum() > 0 ? profit.divide(revenue, 6, RoundingMode.HALF_UP) : null
        );
        out.put(
            "cost_margin",
            profit != null && cost.signum() > 0 ? profit.divide(cost, 6, RoundingMode.HALF_UP) : null
        );
        out.put("complete", completeRevenue && completeCost);
        return out;
    }
}
