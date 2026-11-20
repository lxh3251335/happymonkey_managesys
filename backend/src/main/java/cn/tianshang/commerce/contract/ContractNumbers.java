package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.Database;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 合同号自动编号。
 *
 * <p>沿用现有合同号的规律：前缀 + 年份 - 客户代码 - 序号，如 TJ2026-SHHLZY-109。每个客户按年份各自连续编号。
 * 客户代码取客户资料中的“合同代码”，未填时从该客户已有合同号中识别；前缀取该客户（否则全部合同中最常用）的前缀。
 * 识别不出客户代码时退回到通用格式 HT + 日期 + 序号。
 */
@Component
public class ContractNumbers {

    /** 前缀字母 + 4 位年份 - 客户代码 - 序号。 */
    static final Pattern FORMAT = Pattern.compile("^([A-Za-z]+)(\\d{4})-([A-Za-z0-9]+)-(\\d+)$");
    private static final String FALLBACK_PREFIX = "HT";

    private final Database db;

    public ContractNumbers(Database db) {
        this.db = db;
    }

    /** 建议的合同号及其依据。 */
    public record Suggestion(String number, String code, boolean byCustomerRule) {}

    public String next(String customerId) {
        return suggest(customerId).number();
    }

    public Suggestion suggest(String customerId) {
        var all = numbers();
        int year = LocalDate.now().getYear();
        String code = null,
            lead = null;
        if (customerId != null) {
            var codes = db.jdbc.queryForList("SELECT contract_code FROM partner WHERE id=?", String.class, customerId);
            if (!codes.isEmpty() && codes.get(0) != null && !codes.get(0).isBlank()) code = codes.get(0).trim();
            // 该客户最近的合同号：取年份最大、序号最大的那个的前缀和代码
            int bestYear = -1,
                bestSeq = -1;
            for (var n : all) {
                if (!customerId.equals(n.customerId())) continue;
                var m = FORMAT.matcher(n.number());
                if (!m.matches()) continue;
                int y = Integer.parseInt(m.group(2)),
                    s = parse(m.group(4));
                if (y > bestYear || (y == bestYear && s > bestSeq)) {
                    bestYear = y;
                    bestSeq = s;
                    lead = m.group(1);
                    if (codes.isEmpty() || codes.get(0) == null || codes.get(0).isBlank()) code = m.group(3);
                }
            }
        }
        if (code == null) return new Suggestion(dated(all), null, false);
        if (lead == null) lead = commonLead(all);
        String prefix = lead + year + "-" + code + "-";
        int max = 0;
        for (var n : all)
            if (n.number().startsWith(prefix)) {
                var m = FORMAT.matcher(n.number());
                if (m.matches()) max = Math.max(max, parse(m.group(4)));
            }
        var used = new HashSet<String>();
        for (var n : all) used.add(n.number());
        for (int i = max + 1; i < max + 1000; i++) {
            String no = prefix + (i < 10 ? "0" + i : String.valueOf(i));
            if (!used.contains(no)) return new Suggestion(no, code, true);
        }
        return new Suggestion(dated(all), code, false);
    }

    private record Used(String customerId, String number) {}

    /** 已使用的合同号（门店项目与合同记录），不含“#项目编号”这类占位键。 */
    private List<Used> numbers() {
        var out = new ArrayList<Used>();
        for (var r : db.jdbc.queryForList(
            """
            SELECT customer_id,contract_no FROM project WHERE contract_no IS NOT NULL AND contract_no<>''
            UNION SELECT customer_id,contract_no FROM contract
            """
        )) {
            String no = r.get("contract_no").toString().trim();
            if (!no.startsWith("#")) out.add(new Used(r.get("customer_id").toString(), no));
        }
        return out;
    }

    /** 全部合同号中最常用的前缀字母；没有可识别的合同号时用 HT。 */
    private static String commonLead(List<Used> all) {
        var counts = new HashMap<String, Integer>();
        for (var n : all) {
            var m = FORMAT.matcher(n.number());
            if (m.matches()) counts.merge(m.group(1), 1, Integer::sum);
        }
        return counts
            .entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(FALLBACK_PREFIX);
    }

    /** 通用格式：HT + 日期 + 两位序号，如 HT20261115-01。 */
    private static String dated(List<Used> all) {
        var used = new HashSet<String>();
        for (var n : all) used.add(n.number());
        String prefix = FALLBACK_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int i = 1; i < 1000; i++) {
            String no = prefix + (i < 10 ? "0" + i : String.valueOf(i));
            if (!used.contains(no)) return no;
        }
        throw new cn.tianshang.commerce.common.ApiException("今日合同号已用完，请手工填写合同号");
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
