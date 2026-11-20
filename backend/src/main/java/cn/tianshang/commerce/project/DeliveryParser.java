package cn.tianshang.commerce.project;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物流信息粘贴识别：从一段随手粘贴的文字里拆出收货人、电话、地址、快递公司和运单号。纯规则，认不出的留给人填，不猜。
 *
 * <p>与浏览器里的 {@code frontend/src/parsing/delivery.ts} 是同一套规则的两份实现（网页边输入边识别，机器人在服务端识别），
 * 两边共用 {@code shared/parsing/delivery.json} 里的用例。改规则时先改用例，再同时改两处。
 */
public final class DeliveryParser {

    private DeliveryParser() {}

    public record Parsed(
        String consignee,
        String phone,
        String address,
        String courier,
        String trackingNo,
        /** 快递公司是按运单号开头推断的（文字里没写），需要人核对。 */
        boolean courierGuessed
    ) {
        public boolean empty() {
            return consignee == null && phone == null && address == null && courier == null && trackingNo == null;
        }
    }

    /** 批量运单里的一行。hint 是去掉运单号和快递公司后剩下的文字，用来对门店。 */
    public record TrackingLine(String line, String courier, boolean courierGuessed, String trackingNo, String hint) {}

    private record Alias(Pattern pattern, String name) {}

    private static final int I = Pattern.CASE_INSENSITIVE;

    /** 文字里的叫法 → 系统里的快递公司名称。 */
    private static final List<Alias> COURIER_ALIASES = List.of(
        new Alias(Pattern.compile("顺丰|SF\\s*Express", I), "顺丰速运"),
        new Alias(Pattern.compile("京东"), "京东物流"),
        new Alias(Pattern.compile("德邦"), "德邦快递"),
        new Alias(Pattern.compile("中通"), "中通快递"),
        new Alias(Pattern.compile("圆通"), "圆通速递"),
        new Alias(Pattern.compile("韵达"), "韵达快递"),
        new Alias(Pattern.compile("申通"), "申通快递"),
        new Alias(Pattern.compile("EMS|邮政", I), "EMS"),
        new Alias(Pattern.compile("跨越"), "跨越速运"),
        new Alias(Pattern.compile("安能"), "安能物流"),
        new Alias(Pattern.compile("极兔"), "极兔速递"),
        new Alias(Pattern.compile("专车"), "专车配送"),
        new Alias(Pattern.compile("自提"), "客户自提")
    );

    /** 运单号开头 → 快递公司。字母前缀可靠；纯数字的只收常见且不易混淆的几种。 */
    private static final List<Alias> TRACKING_PREFIX = List.of(
        new Alias(Pattern.compile("^SF\\d{10,}$", I), "顺丰速运"),
        new Alias(Pattern.compile("^JD[A-Z]{0,2}\\d{9,}", I), "京东物流"),
        new Alias(Pattern.compile("^DPK?\\d{9,}$", I), "德邦快递"),
        new Alias(Pattern.compile("^YT\\d{10,}$", I), "圆通速递"),
        new Alias(Pattern.compile("^JT\\d{10,}$", I), "极兔速递"),
        new Alias(Pattern.compile("^KY\\d{10,}$", I), "跨越速运"),
        new Alias(Pattern.compile("^7[358]\\d{12}$"), "中通快递"),
        new Alias(Pattern.compile("^4[36]\\d{11,13}$"), "韵达快递"),
        new Alias(Pattern.compile("^77\\d{13}$"), "申通快递")
    );

    private static final Pattern ADDRESS_HINT = Pattern.compile(
        "[省市区县镇乡村路街道巷弄号栋幢座楼层室]|单元|大厦|广场|园区|工业园|小区|仓库"
    );
    private static final Pattern ADDRESS_TAIL = Pattern.compile("[省市区县镇乡]$");
    private static final Pattern NAME_TITLE = Pattern.compile("(先生|女士|小姐|老师|师傅|店长|经理|主管|老板|总)$");
    private static final Pattern NAME_PLAIN = Pattern.compile("^[一-龥·]{2,4}$");
    private static final Pattern NAME_TITLED = Pattern.compile("^[一-龥]{2,6}$");
    private static final Pattern SEP = Pattern.compile("[\\s,，;；、|]+");
    private static final Pattern TOKEN_EDGE = Pattern.compile("^[:：\\-—]+|[:：\\-—]+$");
    private static final Pattern TRACKING = Pattern.compile("[A-Za-z]{1,4}\\d{9,}[A-Za-z0-9]*|\\d{12,20}");
    private static final Pattern MOBILE_ONLY = Pattern.compile("^1[3-9]\\d{9}$");
    // 手机号中间可能有空格或短横：138-0000-1111、138 0000 1111
    private static final Pattern MOBILE = Pattern.compile("(?<![\\dA-Za-z])1[3-9]\\d(?:[ -]?\\d{4}){2}(?![\\dA-Za-z])");
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)0\\d{2,3}-?\\d{7,8}(?:-\\d{1,6})?(?!\\d)");
    private static final Pattern PHONE_LABEL = Pattern.compile(
        "(联系电话|收货电话|手机号码?|电话|手机|联系方式)\\s*[:：]?"
    );
    private static final Pattern COURIER_LABEL = Pattern.compile("(快递公司|物流公司|快递|物流|承运)\\s*[:：]");
    private static final Pattern TRACKING_LABEL = Pattern.compile(
        "(运单号码?|快递单号|物流单号|单号|快递|物流)\\s*[:：]?"
    );
    private static final Pattern HINT_SEP = Pattern.compile("[\\s,，;；:：|]+");
    private static final Pattern ADDRESS_END = Pattern.compile("[，,;；。.]+$");
    private static final Pattern BRACKETS = Pattern.compile("[（(][^）)]*[）)]");
    private static final Pattern CODE_PREFIX = Pattern.compile("^[a-z]+-?\\d+");
    private static final Pattern UNICODE_SPACE = Pattern.compile(
        "[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"
    );

    /** 全角数字、字母、冒号等转半角，各种空格统一成普通空格，统一换行。 */
    static String normalize(String text) {
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= '！' && c <= '～' ? (char) (c - 0xfee0) : c);
        }
        return UNICODE_SPACE.matcher(sb).replaceAll(" ").replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 把第一处 found 换成空格。 */
    private static String cut(String text, String found) {
        int at = text.indexOf(found);
        return at < 0 ? text : text.substring(0, at) + " " + text.substring(at + found.length());
    }

    /** 取出带标签的字段（如“收货人：张三”），并从原文中去掉这一段。返回 {值或 null, 剩下的文字}。 */
    private static String[] takeLabeled(String text, String labels, String value) {
        Matcher m = Pattern.compile("(?:" + labels + ")\\s*[:：]?\\s*(" + value + ")").matcher(text);
        if (!m.find()) return new String[] { null, text };
        return new String[] { m.group(1).trim(), cut(text, m.group()) };
    }

    public static String courierOfTracking(String no) {
        for (var p : TRACKING_PREFIX) if (p.pattern().matcher(no).find()) return p.name();
        return null;
    }

    /** 运单号：字母开头接数字，或 12 位以上纯数字；排除手机号。 */
    private static String findTracking(String text) {
        Matcher m = TRACKING.matcher(text);
        while (m.find()) if (!MOBILE_ONLY.matcher(m.group()).matches()) return m.group();
        return null;
    }

    /** 返回 {电话或 null, 剩下的文字}。 */
    private static String[] findPhone(String text) {
        Matcher mobile = MOBILE.matcher(text);
        if (mobile.find()) return new String[] { mobile.group().replaceAll("[ -]", ""), cut(text, mobile.group()) };
        Matcher landline = LANDLINE.matcher(text);
        if (landline.find()) return new String[] { landline.group(), cut(text, landline.group()) };
        return new String[] { null, text };
    }

    private static Pattern withSuffix(Pattern alias) {
        return Pattern.compile("(" + alias.pattern() + ")(快递|速运|速递|物流|配送)?", alias.flags());
    }

    /** 识别一段文字。couriers 是系统里已有的快递公司名称，文字里写了全称时直接采用。 */
    public static Parsed parse(String input, List<String> couriers) {
        String text = normalize(input == null ? "" : input);
        if (text.isBlank()) return new Parsed(null, null, null, null, null, false);
        String consignee = null,
            phone,
            address = null,
            courier = null,
            trackingNo = null;
        boolean guessed = false;

        // 1. 带标签的字段
        String[] r = takeLabeled(text, "运单号码?|快递单号|物流单号|快递号|单号", "[A-Za-z0-9]{8,24}");
        if (r[0] != null) trackingNo = r[0].toUpperCase(Locale.ROOT);
        r = takeLabeled(r[1], "收货人|收件人|收货联系人|联系人|姓名", "[^\\s,，;；:：\\d]{1,12}");
        if (r[0] != null) consignee = r[0];
        r = takeLabeled(r[1], "收货地址|收件地址|详细地址|地址", "[^\\n]+");
        if (r[0] != null) address = r[0];
        // “电话：”等标签本身没有信息，去掉后按号码规则找
        text = PHONE_LABEL.matcher(r[1]).replaceAll(" ");

        // 2. 电话
        r = findPhone(text);
        phone = r[0];
        text = r[1];
        if (address != null) {
            // 地址行里夹着的电话也要拿掉
            String[] inAddress = findPhone(address);
            if (inAddress[0] != null) {
                if (phone == null) phone = inAddress[0];
                address = inAddress[1].trim();
            }
        }

        // 3. 运单号与快递公司
        if (trackingNo == null) {
            String t = findTracking(text);
            if (t != null) {
                trackingNo = t.toUpperCase(Locale.ROOT);
                text = cut(text, t);
            }
        }
        String named = null;
        for (String c : couriers)
            if (text.contains(c)) {
                named = c;
                break;
            }
        if (named != null) {
            courier = named;
            text = cut(text, named);
        } else for (var alias : COURIER_ALIASES)
            if (alias.pattern().matcher(text).find()) {
                courier = alias.name();
                text = withSuffix(alias.pattern()).matcher(text).replaceFirst(" ");
                break;
            }
        if (courier == null && trackingNo != null) {
            courier = courierOfTracking(trackingNo);
            guessed = courier != null;
        }
        text = COURIER_LABEL.matcher(text).replaceAll(" ");

        // 4. 剩下的词：像地址的拼成地址，第一个像人名的是收货人
        var addressParts = new ArrayList<String>();
        String guessedName = null;
        for (String raw : SEP.split(text)) {
            String t = TOKEN_EDGE.matcher(raw).replaceAll("");
            if (t.isEmpty()) continue;
            // “四川省”“成都市”这样的短词也是地址的一段
            boolean isAddress = ADDRESS_HINT.matcher(t).find() && (t.length() >= 4 || ADDRESS_TAIL.matcher(t).find());
            boolean isName =
                !isAddress &&
                (NAME_PLAIN.matcher(t).find() || (NAME_TITLE.matcher(t).find() && NAME_TITLED.matcher(t).find()));
            if (isName && consignee == null && guessedName == null) guessedName = t;
            else if (isAddress || (!addressParts.isEmpty() && !isName)) addressParts.add(t);
        }
        if (address == null && !addressParts.isEmpty()) address = String.join("", addressParts);
        // 没有标签的人名只在同时认出电话或地址时才采用，免得把“明天发”这类话当成收货人
        if (guessedName != null && (phone != null || address != null)) consignee = guessedName;

        if (address != null) address = ADDRESS_END.matcher(address.replaceAll("\\s+", "")).replaceAll("");
        return new Parsed(
            blankToNull(consignee),
            blankToNull(phone),
            blankToNull(address),
            blankToNull(courier),
            blankToNull(trackingNo),
            guessed
        );
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** 批量运单：每行一个门店 + 运单号（可带快递公司），空行跳过。 */
    public static List<TrackingLine> trackingLines(String input, List<String> couriers) {
        var out = new ArrayList<TrackingLine>();
        for (String raw : normalize(input == null ? "" : input).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Parsed p = parse(line, couriers);
            String hint = line;
            for (String x : new String[] { p.trackingNo(), p.courier() })
                if (x != null) hint = Pattern.compile(Pattern.quote(x), I).matcher(hint).replaceFirst(" ");
            for (var alias : COURIER_ALIASES) hint = withSuffix(alias.pattern()).matcher(hint).replaceFirst(" ");
            hint = TRACKING_LABEL.matcher(hint).replaceAll(" ");
            hint = HINT_SEP.matcher(hint).replaceAll(" ").trim();
            out.add(new TrackingLine(line, p.courier(), p.courierGuessed(), p.trackingNo(), hint));
        }
        return out;
    }

    /** 门店名称里用于比对的部分：去掉空格和括号备注，不分大小写。 */
    private static String storeKey(String name) {
        return BRACKETS.matcher(name).replaceAll("").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String lower(Object value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 按一行里的文字找门店：先看项目编号、合同号，再看名称互相包含。stores 的每一项要有 name、code、contract_no。
     * 返回候选：0 个 = 没找到，多个 = 需要人选，不擅自挑一个。
     */
    public static List<Map<String, Object>> matchStores(String hint, List<Map<String, Object>> stores) {
        String h = storeKey(hint == null ? "" : hint);
        if (h.isEmpty()) return List.of();
        var byCode = stores
            .stream()
            .filter(s -> {
                String code = lower(s.get("code")),
                    contract = lower(s.get("contract_no"));
                return (!code.isEmpty() && h.contains(code)) || (!contract.isEmpty() && h.contains(contract));
            })
            .toList();
        if (!byCode.isEmpty()) return byCode;
        var exact = stores
            .stream()
            .filter(s -> storeKey(String.valueOf(s.get("name"))).equals(h))
            .toList();
        if (!exact.isEmpty()) return exact;
        // 名称常带编号前缀（如“LK-260127 小象成都盛泰站”），粘贴的文字往往只有后半段
        var contains = stores
            .stream()
            .filter(s -> {
                String k = storeKey(String.valueOf(s.get("name")));
                String bare = CODE_PREFIX.matcher(k).replaceFirst("");
                return k.contains(h) || h.contains(k) || (bare.length() >= 3 && h.contains(bare));
            })
            .toList();
        if (contains.size() <= 1) return contains;
        // 多个门店都包含这段文字时，取名称正好以它结尾的那一个（“盛泰站”不该同时配上“盛泰站二期”）
        var tight = contains
            .stream()
            .filter(s -> storeKey(String.valueOf(s.get("name"))).endsWith(h))
            .toList();
        return tight.size() == 1 ? tight : contains;
    }
}
