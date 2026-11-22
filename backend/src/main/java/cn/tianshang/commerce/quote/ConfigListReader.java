package cn.tianshang.commerce.quote;

import cn.tianshang.commerce.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/**
 * 读客户发来的配置清单（Excel，.xlsx 或 .xls）：找到带“名称”“数量”的表头行，逐行取出产品名称、规格、型号、数量、单位和备注。
 *
 * <p>客户的表格式各不相同，所以按表头文字认列，不要求固定模板：表头可以不在第一行（上面常有标题、门店信息），
 * 合并单元格按左上角的值读，“合计”“小计”行跳过。认不出的列忽略。
 */
public final class ConfigListReader {

    private ConfigListReader() {}

    public record Line(
        int row,
        String name,
        String specification,
        String model,
        BigDecimal quantity,
        String unit,
        String note
    ) {}

    public record Result(String sheet, List<Line> lines) {}

    private enum Role {
        NAME,
        SPEC,
        MODEL,
        QTY,
        UNIT,
        NOTE,
    }

    /** 表头文字 → 列的含义。按顺序匹配，先匹配到的为准。 */
    private static final List<Map.Entry<Pattern, Role>> HEADERS = List.of(
        Map.entry(Pattern.compile("规格型号|规格|尺寸|参数"), Role.SPEC),
        Map.entry(Pattern.compile("型号"), Role.MODEL),
        Map.entry(Pattern.compile("数量|台数|套数|个数|^数$|用量"), Role.QTY),
        Map.entry(Pattern.compile("单位"), Role.UNIT),
        Map.entry(Pattern.compile("备注|说明"), Role.NOTE),
        Map.entry(Pattern.compile("名称|品名|产品|设备|物料|货物|项目"), Role.NAME)
    );
    private static final Pattern SKIP_HEADER = Pattern.compile("^(序号|编号|no\\.?)$");
    private static final Pattern SUMMARY = Pattern.compile("^(合计|小计|总计|共计|总价|总额)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final int HEADER_SCAN_ROWS = 20;

    public static Result read(InputStream in) {
        try (Workbook wb = WorkbookFactory.create(in)) {
            var formatter = new DataFormatter(Locale.CHINA);
            var evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) continue;
                var cells = new Cells(sheet, formatter, evaluator);
                for (
                    int r = sheet.getFirstRowNum();
                    r <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SCAN_ROWS);
                    r++
                ) {
                    var columns = header(cells, r);
                    if (columns.containsValue(Role.NAME) && columns.containsValue(Role.QTY)) return new Result(
                        sheet.getSheetName(),
                        lines(cells, sheet, r, columns)
                    );
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw new ApiException("这个文件打不开。请确认是 Excel 文件（.xlsx 或 .xls），没有加密");
        }
        throw new ApiException("没有找到配置清单的表头：表格里要有“名称”（或品名、产品）和“数量”两列");
    }

    /** 这一行若是表头，返回 列号 → 含义。 */
    private static Map<Integer, Role> header(Cells cells, int r) {
        var out = new LinkedHashMap<Integer, Role>();
        Row row = cells.sheet.getRow(r);
        if (row == null) return out;
        for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
            String text = cells.text(r, c).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (text.isEmpty() || text.length() > 12 || SKIP_HEADER.matcher(text).matches()) continue;
            for (var h : HEADERS) {
                if (h.getKey().matcher(text).find()) {
                    // 同一含义只取第一列（如“产品名称”和“产品类别”都含“产品”）
                    if (!out.containsValue(h.getValue())) out.put(c, h.getValue());
                    break;
                }
            }
        }
        return out;
    }

    private static List<Line> lines(Cells cells, Sheet sheet, int headerRow, Map<Integer, Role> columns) {
        var out = new ArrayList<Line>();
        int blanks = 0;
        for (int r = headerRow + 1; r <= sheet.getLastRowNum() && blanks < 20; r++) {
            var v = new EnumMap<Role, String>(Role.class);
            for (var e : columns.entrySet()) v.put(e.getValue(), cells.text(r, e.getKey()).trim());
            String name = v.getOrDefault(Role.NAME, "");
            if (name.isEmpty()) {
                blanks++;
                continue;
            }
            blanks = 0;
            if (SUMMARY.matcher(name.replaceAll("\\s+", "")).find()) continue;
            // 合并单元格往下延伸出来的空行：名称是上面借来的，这一行自己什么都没写
            boolean ownValue = v
                .entrySet()
                .stream()
                .anyMatch(e -> e.getKey() != Role.NAME && !e.getValue().isEmpty());
            if (!ownValue && cells.continued(r, nameColumn(columns))) continue;
            out.add(
                new Line(
                    r + 1,
                    name,
                    v.getOrDefault(Role.SPEC, ""),
                    v.getOrDefault(Role.MODEL, ""),
                    quantity(v.get(Role.QTY)),
                    v.getOrDefault(Role.UNIT, ""),
                    v.getOrDefault(Role.NOTE, "")
                )
            );
        }
        if (out.isEmpty()) throw new ApiException("表头下面没有读到产品行");
        return out;
    }

    private static int nameColumn(Map<Integer, Role> columns) {
        for (var e : columns.entrySet()) if (e.getValue() == Role.NAME) return e.getKey();
        return -1;
    }

    /** “2”“2台”“2.0” → 2；没有数字时为空，由人填写。 */
    static BigDecimal quantity(String text) {
        if (text == null) return null;
        Matcher m = NUMBER.matcher(text.replace(",", ""));
        if (!m.find()) return null;
        var q = new BigDecimal(m.group(1)).stripTrailingZeros();
        return q.signum() > 0 ? (q.scale() < 0 ? q.setScale(0) : q) : null;
    }

    /** 按格子读文字；合并单元格取左上角的值，公式取计算结果。 */
    private static final class Cells {

        final Sheet sheet;
        final DataFormatter formatter;
        final FormulaEvaluator evaluator;
        final List<CellRangeAddress> merged;

        Cells(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
            this.sheet = sheet;
            this.formatter = formatter;
            this.evaluator = evaluator;
            this.merged = sheet.getMergedRegions();
        }

        /** 这个格子在合并单元格里、但不是左上角那一格。 */
        boolean continued(int r, int c) {
            for (var m : merged) if (m.isInRange(r, c)) return m.getFirstRow() != r || m.getFirstColumn() != c;
            return false;
        }

        String text(int r, int c) {
            for (var m : merged)
                if (m.isInRange(r, c)) {
                    r = m.getFirstRow();
                    c = m.getFirstColumn();
                    break;
                }
            Row row = sheet.getRow(r);
            Cell cell = row == null ? null : row.getCell(c);
            if (cell == null) return "";
            try {
                return formatter.formatCellValue(cell, evaluator);
            } catch (RuntimeException e) {
                return formatter.formatCellValue(cell);
            }
        }
    }
}
