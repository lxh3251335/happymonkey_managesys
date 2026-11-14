package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.ApiException;

/** 纳税人识别号：去空格转大写；18 位统一社会信用代码按 GB 32100-2015 校验位核对，旧版 15/17/20 位只校验字符。 */
public final class TaxNumbers {

    private static final String CHARS = "0123456789ABCDEFGHJKLMNPQRTUWXY";
    private static final int[] WEIGHTS = { 1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28 };

    private TaxNumbers() {}

    /** 返回规范化后的税号；空值返回 null；格式不对抛出业务异常。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.replaceAll("\\s", "").toUpperCase();
        if (!v.matches("[0-9A-Z]{15,20}")) throw new ApiException("纳税人识别号应为 15–20 位数字或大写字母");
        if (v.length() == 18 && !validCreditCode(v)) throw new ApiException("统一社会信用代码校验位不正确，请核对");
        return v;
    }

    static boolean validCreditCode(String code) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int c = CHARS.indexOf(code.charAt(i));
            if (c < 0) return false;
            sum += c * WEIGHTS[i];
        }
        int check = 31 - (sum % 31);
        if (check == 31) check = 0;
        return CHARS.charAt(check) == code.charAt(17);
    }
}
