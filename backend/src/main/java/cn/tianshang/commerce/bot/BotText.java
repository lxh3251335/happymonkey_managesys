package cn.tianshang.commerce.bot;

import cn.tianshang.commerce.contract.ContractLedger;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** 机器人回复里的金额、日期写法。 */
final class BotText {

    private BotText() {}

    static final Map<String, String> ROLE_NAMES = Map.of(
        "ADMIN",
        "系统管理员",
        "MANAGER",
        "管理层",
        "FINANCE",
        "财务",
        "SALES",
        "业务员",
        "VIEWER",
        "只读"
    );

    static String money(Object value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : ContractLedger.decimal(value);
        return new DecimalFormat("#,##0.00").format(v == null ? BigDecimal.ZERO : v);
    }

    static BigDecimal num(Object value) {
        BigDecimal v = value == null ? null : ContractLedger.decimal(value);
        return v == null ? BigDecimal.ZERO : v;
    }

    static LocalDate date(Object value) {
        return value == null ? null : ContractLedger.date(value);
    }

    /** “2026-11-20”，过期的加“已过 3 天”。 */
    static String due(LocalDate date, LocalDate today) {
        if (date == null) return "未定";
        long late = ChronoUnit.DAYS.between(date, today);
        return late > 0 ? date + "，已过 " + late + " 天" : date.toString();
    }

    static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 列表太长时只列前 max 项，说明还有多少。 */
    static String more(int total, int max) {
        return total > max ? "\n……还有 " + (total - max) + " 项，请到网页上查看" : "";
    }
}
