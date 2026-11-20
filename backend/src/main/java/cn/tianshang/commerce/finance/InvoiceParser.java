package cn.tianshang.commerce.finance;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从电子发票 PDF 的文字里找出发票号码、开票日期、价税合计、税率、购买方、销售方和备注。
 * 纯规则；认不出的字段留空，由登记的人补。扫描件没有文字层，全部留空。
 */
public final class InvoiceParser {

    private InvoiceParser() {}

    public record Parsed(
        String invoiceNo,
        LocalDate issuedOn,
        BigDecimal amount,
        BigDecimal taxRate,
        String invoiceType,
        String buyerName,
        String buyerTaxNo,
        String sellerName,
        String remark
    ) {
        public boolean empty() {
            return invoiceNo == null && issuedOn == null && amount == null && buyerName == null;
        }
    }

    private static final String MONEY = "(\\d{1,3}(?:,\\d{3})+\\.\\d{2}|\\d+\\.\\d{2})";

    public static Parsed parse(String raw) {
        String text = normalize(raw);
        var names = all(text, "名\\s*称\\s*[:：]\\s*([^\\s:：]{2,60})");
        var taxNos = all(text, "纳税人识别号\\s*[:：]?\\s*([0-9A-Z]{15,20})");
        return new Parsed(
            first(text, "发票号码\\s*[:：]?\\s*(\\d{8,20})"),
            date(text),
            amount(text),
            taxRate(text),
            text.contains("专用发票") ? "SPECIAL" : text.contains("普通发票") ? "NORMAL" : null,
            names.isEmpty() ? null : names.get(0),
            taxNos.isEmpty() ? null : taxNos.get(0),
            names.size() > 1 ? names.get(1) : null,
            remark(text)
        );
    }

    /** 全角数字、字母、符号转半角；发票上的“￥”统一成“¥”。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        var sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c >= '！' && c <= '～' && c != '：' && c != '（' && c != '）') sb.append((char) (c - 0xfee0));
            else if (c == '　' || c == ' ') sb.append(' ');
            else if (c == '￥') sb.append('¥');
            else sb.append(c);
        }
        return sb.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static LocalDate date(String text) {
        Matcher m = Pattern.compile(
            "开票日期\\s*[:：]?\\s*(\\d{4})\\s*[年\\-/.]\\s*(\\d{1,2})\\s*[月\\-/.]\\s*(\\d{1,2})"
        ).matcher(text);
        if (!m.find()) return null;
        try {
            return LocalDate.of(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
            );
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** 价税合计（小写）；找不到这个标记时取发票上最大的“¥”金额（价税合计总是最大的那个）。 */
    private static BigDecimal amount(String text) {
        String v = first(text, "[（(]\\s*小写\\s*[）)]\\s*¥?\\s*" + MONEY);
        if (v == null) v = first(text, "价税合计[^\\d¥\\n]{0,40}¥\\s*" + MONEY);
        if (v != null) return money(v);
        BigDecimal max = null;
        for (String x : all(text, "¥\\s*" + MONEY)) {
            var n = money(x);
            if (max == null || n.compareTo(max) > 0) max = n;
        }
        return max;
    }

    /** 明细行税率都相同才采用；多种税率的发票留空。 */
    private static BigDecimal taxRate(String text) {
        var rates = new HashSet<>(all(text, "(?<![\\d.])(\\d{1,2}(?:\\.\\d)?)\\s*%"));
        if (rates.size() != 1) return null;
        return new BigDecimal(rates.iterator().next()).movePointLeft(2);
    }

    private static String remark(String text) {
        Matcher m = Pattern.compile(
            "备\\s*注\\s*[:：]?\\s*([\\s\\S]{0,300}?)(?=开\\s*票\\s*人|收\\s*款\\s*人|复\\s*核|$)"
        ).matcher(text);
        if (!m.find()) return null;
        String r = m.group(1).replaceAll("\\s+", " ").trim();
        return r.isEmpty() ? null : r;
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v.replace(",", ""));
    }

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static List<String> all(String text, String regex) {
        var out = new ArrayList<String>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }
}
