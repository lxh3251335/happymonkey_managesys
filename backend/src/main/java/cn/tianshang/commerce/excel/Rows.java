package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.excel.ExcelIo.Row;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** 各类导入共用的小工具：按列分组、行号区间、空值与数值比较、Bean 校验结果转中文。 */
final class Rows {

    private Rows() {}

    static LinkedHashMap<String, List<Row>> group(List<Row> rows, Function<Row, String> key) {
        var out = new LinkedHashMap<String, List<Row>>();
        for (var r : rows) out.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        return out;
    }

    static String span(List<Row> rows) {
        int a = rows.get(0).number(),
            b = rows.get(rows.size() - 1).number();
        return a == b ? "第 " + a + " 行" : "第 " + a + "–" + b + " 行";
    }

    static String at(Row r) {
        return "第 " + r.number() + " 行";
    }

    static <T> Map<String, T> index(List<T> rows, Function<T, String> key) {
        var out = new HashMap<String, T>();
        for (var r : rows) out.putIfAbsent(key.apply(r), r);
        return out;
    }

    static boolean eq(Object a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return new BigDecimal(a.toString()).compareTo(b) == 0;
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String text(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? "" : v.toString();
    }

    static BigDecimal decimal(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    /** 列在表中时取表中值，否则保留原值（新增时为空）。 */
    static String value(ExcelIo.Sheet sheet, Row row, String key, Map<String, Object> current, String column) {
        if (sheet.present().contains(key)) return row.get(key);
        return current == null ? "" : text(current, column);
    }

    /** “是/否”列：列在表中且填了值时按值，否则保留原值（新增时为默认值）。 */
    static boolean flag(
        ExcelIo.Sheet sheet,
        Row row,
        String key,
        Map<String, Object> current,
        String column,
        boolean dflt
    ) {
        if (sheet.present().contains(key) && !row.get(key).isEmpty()) return ExcelIo.yes(row.get(key));
        return current == null ? dflt : Boolean.TRUE.equals(current.get(column));
    }

    static int sort(ExcelIo.Sheet sheet, Row row, String key, Map<String, Object> current, List<String> errors) {
        if (sheet.present().contains(key) && !row.get(key).isEmpty()) {
            try {
                return new BigDecimal(row.get(key).trim()).intValue();
            } catch (NumberFormatException e) {
                errors.add("排序必须是整数");
                return 0;
            }
        }
        Object v = current == null ? null : current.get("sort");
        return v == null ? 0 : ((Number) v).intValue();
    }

    static <T> void validate(Validator validator, T input, List<String> errors, Map<String, String> names) {
        for (var v : validator.validate(input)) {
            String field = v.getPropertyPath().toString();
            errors.add(names.getOrDefault(field, field) + "：" + v.getMessage());
        }
    }
}
