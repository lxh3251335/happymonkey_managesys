package cn.tianshang.commerce.contract;

/** 合同、发货与产品线的口径规则。 */
public final class Contracts {

    public static final String SHIPPED = "已发货";
    public static final String IN_PRODUCTION = "制作中";

    private Contracts() {}

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 统一发货状态写法：“在制作”与“制作中”视为同一状态。 */
    public static String shipping(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        if (v.equals("在制作")) return IN_PRODUCTION;
        return v;
    }

    public static final String CABINET = "电控柜";
    public static final String IOT = "物联网电箱";
    public static final String SOFTWARE = "软件及平台";
    public static final String OTHER = "其他";

    /** 产品线展示顺序；电控柜与物联网电箱为两条主要业务线。 */
    public static final java.util.List<String> LINES = java.util.List.of(CABINET, IOT, SOFTWARE, OTHER);

    /**
     * 按品名归入产品线：品名含“物联网”的（物联网电控箱、水位物联网配电箱等）归物联网电箱；
     * 各类配电箱、配电柜、库房电箱归电控柜。
     */
    public static String productLine(String name) {
        String n = name == null ? "" : name;
        if (n.contains("物联网")) return IOT;
        if (n.contains("软件")) return SOFTWARE;
        if (n.contains("配电柜") || n.contains("电控柜") || n.contains("配电箱") || n.contains("电箱")) return CABINET;
        return OTHER;
    }
}
