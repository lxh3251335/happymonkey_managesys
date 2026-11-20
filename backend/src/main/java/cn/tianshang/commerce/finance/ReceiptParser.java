package cn.tianshang.commerce.finance;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回款粘贴识别：从银行短信、回单上复制的文字里找出到账金额、日期、付款方和流水号。纯规则，认不出的留给人填。
 * 余额不是到账金额，会先被排除。
 *
 * <p>与浏览器里的 {@code frontend/src/parsing/receipt.ts} 是同一套规则的两份实现，
 * 两边共用 {@code shared/parsing/receipt.json} 里的用例。改规则时先改用例，再同时改两处。
 */
public final class ReceiptParser {

    private ReceiptParser() {}

    public record Parsed(BigDecimal amount, LocalDate paidOn, String payer, String reference) {
        public boolean empty() {
            return amount == null && paidOn == null && payer == null && reference == null;
        }
    }

    private static final String NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)";
    private static final String CURRENCY = "(?:人民币|RMB|CNY|¥|￥)?";

    // 余额、手续费不是到账金额
    private static final Pattern NOT_AMOUNT = Pattern.compile(
        "(可用余额|当前余额|账户余额|余额|手续费)[^\\d\\n]{0,8}" + NUM + "\\s*元?"
    );
    private static final List<Pattern> AMOUNT = List.of(
        // 收到 / 收入 / 入账……人民币 30,000.00 元
        Pattern.compile(
            "(?:收到|收入|入账|转入|存入|汇入|到账|来账|贷记|收款|已收)[^\\d\\n]{0,16}?" + CURRENCY + "\\s*" + NUM
        ),
        // 金额：30,000.00
        Pattern.compile(
            "(?:交易金额|转账金额|付款金额|汇款金额|到账金额|金额)(?:\\(元\\)|（元）)?\\s*[:：]?\\s*" +
                CURRENCY +
                "\\s*" +
                NUM
        ),
        Pattern.compile("(?:人民币|RMB|CNY|¥|￥)\\s*" + NUM),
        Pattern.compile(NUM + "\\s*元")
    );
    private static final Pattern DATE_FULL = Pattern.compile(
        "(20\\d{2})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})"
    );
    private static final Pattern DATE_COMPACT = Pattern.compile(
        "(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)"
    );
    private static final Pattern DATE_SHORT = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final String ORG =
        "[\\u4e00-\\u9fa5A-Za-z0-9（）()·]{2,40}?(?:有限责任公司|股份有限公司|有限公司|公司|工厂|厂|店|中心|经营部|商行|合作社)";
    private static final Pattern PAYER_LABELED = Pattern.compile(
        "(?:付款人|付款方|付款单位|付款户名|付方|对方户名|对方账户名称?|对方名称|对方单位|汇款人|汇款单位|来账户名|对方)(?:名称|户名|全称)?\\s*[:：为]?\\s*([^\\s,，。;；:：]{2,40})"
    );
    // “某某公司向您尾号 8888 的账户转入……”
    private static final Pattern PAYER_ORG = Pattern.compile("(" + ORG + ")\\s*(?:向|给|于|已|转入|汇入|支付|付款)");
    private static final Pattern REFERENCE = Pattern.compile(
        "(?:交易流水号?|银行流水号?|流水号|凭证号码?|业务编号|交易参考号|参考号|回单编号|回单号|交易序号)\\s*[:：]?\\s*([A-Za-z0-9-]{6,40})"
    );
    private static final Pattern UNICODE_SPACE = Pattern.compile(
        "[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
    );

    static String normalize(String text) {
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= '！' && c <= '～' ? (char) (c - 0xfee0) : c);
        }
        return UNICODE_SPACE.matcher(sb).replaceAll(" ").replace("\r\n", "\n").replace('\r', '\n');
    }

    private static BigDecimal findAmount(String text) {
        String t = NOT_AMOUNT.matcher(text).replaceAll(" ");
        for (Pattern p : AMOUNT) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                var v = new BigDecimal(m.group(1).replace(",", ""));
                if (v.signum() > 0) return v;
            }
        }
        return null;
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate findDate(String text, LocalDate today) {
        Matcher m = DATE_FULL.matcher(text);
        if (m.find()) return date(m.group(1), m.group(2), m.group(3));
        m = DATE_COMPACT.matcher(text);
        if (m.find()) return date(m.group(1), m.group(2), m.group(3));
        m = DATE_SHORT.matcher(text);
        if (m.find()) {
            // 短信里常不带年份：按今年算，算出来在将来就是去年的
            LocalDate candidate = date(String.valueOf(today.getYear()), m.group(1), m.group(2));
            if (candidate == null) return null;
            return candidate.isAfter(today.plusDays(1)) ? candidate.minusYears(1) : candidate;
        }
        return null;
    }

    private static String findPayer(String text) {
        Matcher labeled = PAYER_LABELED.matcher(text);
        if (labeled.find()) return labeled
            .group(1)
            .replaceAll("[（(]?尾号.*$", "")
            .replaceAll("的?(?:转账|汇款|来款|付款)$", "");
        Matcher org = PAYER_ORG.matcher(text);
        return org.find() ? org.group(1) : null;
    }

    /** today 只用于给不带年份的日期补年份。 */
    public static Parsed parse(String input, LocalDate today) {
        String text = normalize(input == null ? "" : input);
        if (text.isBlank()) return new Parsed(null, null, null, null);
        Matcher ref = REFERENCE.matcher(text);
        String payer = findPayer(text);
        return new Parsed(
            findAmount(text),
            findDate(text, today),
            payer == null || payer.isEmpty() ? null : payer,
            ref.find() ? ref.group(1) : null
        );
    }

    /**
     * 按付款方（或整段文字）找客户：客户名称或开票抬头出现在文字里。customers 的每一项要有 name、invoice_title。
     * 系统里的客户常是简称（如“中野”），付款方是全称，所以用“全称包含简称”来对；多个都对上时取名字最长的。
     */
    public static Map<String, Object> matchCustomer(String text, List<Map<String, Object>> customers) {
        String t = normalize(text == null ? "" : text).replaceAll("\\s+", "");
        if (t.isEmpty()) return null;
        Map<String, Object> best = null;
        int bestLength = 0;
        for (var c : customers)
            for (Object key : new Object[] { c.get("name"), c.get("invoice_title") }) {
                String k = key == null ? "" : key.toString().replaceAll("\\s+", "");
                if (k.length() >= 2 && t.contains(k) && k.length() > bestLength) {
                    best = c;
                    bestLength = k.length();
                }
            }
        return best;
    }
}
