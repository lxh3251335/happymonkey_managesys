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

    /** 按品名归入产品线；规则依历史资料中的品名整理。 */
    public static String productLine(String name) {
        String n = name == null ? "" : name;
        if (n.contains("物联网电控箱")) return "物联网电控箱";
        if (n.contains("软件")) return "软件及平台";
        if (n.contains("配电柜")) return "配电柜";
        if (n.contains("配电箱") || n.contains("电箱")) return "配电箱";
        return "其他";
    }
}
