package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.common.ApiException;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

/** Excel 读写：第一张表为数据表（首行表头），附“填写说明”表。 */
public final class ExcelIo {

    public static final int MAX_ROWS = 5000;
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> HEADER_ALIASES = Map.of(
        "品牌",
        "业务线",
        "接单日期",
        "下单日期",
        "团队人工",
        "人工成本"
    );

    /** 列定义。text=true 的列设置为文本格式，避免长数字（税号、账号）被 Excel 改成科学计数。 */
    public record Column(
        String key,
        String header,
        int width,
        boolean mandatory,
        boolean text,
        String hint,
        List<String> options
    ) {
        public static Column of(String key, String header, int width) {
            return new Column(key, header, width, false, true, "", List.of());
        }

        public Column required() {
            return new Column(key, header, width, true, text, hint, options);
        }

        public Column number() {
            return new Column(key, header, width, mandatory, false, hint, options);
        }

        public Column note(String n) {
            return new Column(key, header, width, mandatory, text, n, options);
        }

        public Column options(String... o) {
            return new Column(key, header, width, mandatory, text, hint, List.of(o));
        }
    }

    /** 解析结果：表中出现的列 key、各数据行（行号从 2 开始，与 Excel 行号一致）。 */
    public record Sheet(Set<String> present, List<Row> rows) {}

    public record Row(int number, Map<String, String> values) {
        public String get(String key) {
            return values.getOrDefault(key, "");
        }
    }

    private ExcelIo() {}

    /** 一张数据表的定义：表名、列、导出行（模板时为空）。 */
    public record SheetSpec(String title, List<Column> columns, List<Map<String, Object>> rows) {}

    public static byte[] write(String title, List<Column> columns, List<Map<String, Object>> rows, List<String> notes) {
        return write(List.of(new SheetSpec(title, columns, rows)), notes, false);
    }

    /**
     * 写整本：每个 SheetSpec 一张数据表，再加一张“填写说明”（所有表的列说明）。
     * helpFirst=true 时说明表放在最前（完整模板），否则放在最后（单表导出）。
     */
    public static byte[] write(List<SheetSpec> sheets, List<String> notes, boolean helpFirst) {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var headerStyle = wb.createCellStyle();
            var bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            var requiredStyle = wb.createCellStyle();
            requiredStyle.cloneStyleFrom(headerStyle);
            var red = wb.createFont();
            red.setBold(true);
            red.setColor(IndexedColors.DARK_RED.getIndex());
            requiredStyle.setFont(red);
            var textStyle = wb.createCellStyle();
            textStyle.setDataFormat(wb.createDataFormat().getFormat("@"));
            var numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(wb.createDataFormat().getFormat("0.######"));
            var titleStyle = wb.createCellStyle();
            var big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 13);
            titleStyle.setFont(big);

            if (helpFirst) wb.createSheet("填写说明");
            for (var spec : sheets) {
                var sheet = wb.createSheet(spec.title());
                var columns = spec.columns();
                var head = sheet.createRow(0);
                for (int c = 0; c < columns.size(); c++) {
                    var col = columns.get(c);
                    var cell = head.createCell(c);
                    cell.setCellValue(col.header() + (col.mandatory() ? "*" : ""));
                    cell.setCellStyle(col.mandatory() ? requiredStyle : headerStyle);
                    sheet.setColumnWidth(c, Math.max(8, col.width()) * 256);
                    sheet.setDefaultColumnStyle(c, col.text() ? textStyle : numberStyle);
                }
                sheet.createFreezePane(0, 1);
                int r = 1;
                for (var row : spec.rows()) {
                    var x = sheet.createRow(r++);
                    for (int c = 0; c < columns.size(); c++) {
                        var col = columns.get(c);
                        Object v = row.get(col.key());
                        var cell = x.createCell(c);
                        if (v == null) {
                            cell.setCellStyle(col.text() ? textStyle : numberStyle);
                            continue;
                        }
                        if (!col.text() && v instanceof Number n) {
                            cell.setCellValue(new BigDecimal(n.toString()).doubleValue());
                            cell.setCellStyle(numberStyle);
                        } else {
                            cell.setCellValue(
                                v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v.toString()
                            );
                            cell.setCellStyle(textStyle);
                        }
                    }
                }
                // 下拉选项
                var helper = sheet.getDataValidationHelper();
                for (int c = 0; c < columns.size(); c++) {
                    var opts = columns.get(c).options();
                    // Excel 下拉列表的选项文本合计不能超过 255 个字符；超过时只在填写说明中列出
                    if (opts.isEmpty() || String.join(",", opts).length() > 250) continue;
                    var v = helper.createValidation(
                        helper.createExplicitListConstraint(opts.toArray(String[]::new)),
                        new CellRangeAddressList(1, MAX_ROWS, c, c)
                    );
                    v.setShowErrorBox(true);
                    sheet.addValidationData(v);
                }
            }
            // 填写说明
            var help = helpFirst ? wb.getSheet("填写说明") : wb.createSheet("填写说明");
            help.setColumnWidth(0, 22 * 256);
            help.setColumnWidth(1, 80 * 256);
            int h = 0;
            for (String n : notes) help.createRow(h++).createCell(0).setCellValue(n);
            for (var spec : sheets) {
                h++;
                if (sheets.size() > 1) {
                    var t = help.createRow(h++);
                    t.createCell(0).setCellValue("【" + spec.title() + "】");
                    t.getCell(0).setCellStyle(titleStyle);
                }
                var hh = help.createRow(h++);
                hh.createCell(0).setCellValue("列名");
                hh.createCell(1).setCellValue("说明");
                hh.getCell(0).setCellStyle(headerStyle);
                hh.getCell(1).setCellStyle(headerStyle);
                for (var col : spec.columns()) {
                    var hr = help.createRow(h++);
                    hr.createCell(0).setCellValue(col.header() + (col.mandatory() ? "（必填）" : ""));
                    String desc = col.hint();
                    if (!col.options().isEmpty()) desc =
                        (desc.isEmpty() ? "" : desc + "；") + "可选：" + String.join(" / ", col.options());
                    hr.createCell(1).setCellValue(desc);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读取第一张表。按表头文字（去掉 * 与空格）匹配列；未识别的列忽略。 */
    public static Sheet read(MultipartFile file, List<Column> columns) {
        try (Workbook wb = open(file)) {
            return readSheet(wb.getSheetAt(0), columns);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw new ApiException("无法读取 Excel 文件，请确认文件未损坏且为 .xlsx 格式");
        }
    }

    /** 打开上传的工作簿（整本导入时按表名逐张读取）。调用方负责关闭。 */
    public static Workbook open(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("请选择 Excel 文件");
        if (file.getSize() > MAX_BYTES) throw new ApiException("Excel 文件不能超过 10 MB");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) throw new ApiException(
            "请上传 .xlsx 格式的 Excel 文件（旧版 .xls 请另存为 .xlsx）"
        );
        try (InputStream in = file.getInputStream()) {
            return WorkbookFactory.create(in);
        } catch (IOException | RuntimeException e) {
            throw new ApiException("无法读取 Excel 文件，请确认文件未损坏且为 .xlsx 格式");
        }
    }

    /** 读一张表：首行表头，之后每行一条；全空的行跳过。 */
    public static Sheet readSheet(org.apache.poi.ss.usermodel.Sheet sheet, List<Column> columns) {
        var head = sheet.getRow(sheet.getFirstRowNum());
        if (head == null) throw new ApiException("表“" + sheet.getSheetName() + "”没有表头");
        var byHeader = new HashMap<String, Column>();
        for (var col : columns) byHeader.put(normalizeHeader(col.header()), col);
        // 旧版表头别名：早期导出文件的列名（品牌、接单日期、团队人工）仍可导入
        for (var e : HEADER_ALIASES.entrySet())
            if (byHeader.containsKey(e.getValue())) byHeader.putIfAbsent(e.getKey(), byHeader.get(e.getValue()));
        var colIndex = new LinkedHashMap<Integer, Column>();
        for (var cell : head) {
            var col = byHeader.get(normalizeHeader(cellText(cell, true)));
            if (col != null) colIndex.put(cell.getColumnIndex(), col);
        }
        var present = new HashSet<String>();
        colIndex.values().forEach(c -> present.add(c.key()));
        for (var col : columns)
            if (col.mandatory() && !present.contains(col.key())) throw new ApiException(
                "缺少必填列：" + col.header() + "（请使用导出的文件或模板）"
            );
        var rows = new ArrayList<Row>();
        for (int r = head.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            var values = new HashMap<String, String>();
            boolean any = false;
            for (var e : colIndex.entrySet()) {
                var cell = row.getCell(e.getKey());
                var col = e.getValue();
                if (
                    cell != null &&
                    col.text() &&
                    cell.getCellType() == CellType.NUMERIC &&
                    Math.abs(cell.getNumericCellValue()) >= 1e15
                ) {
                    throw new ApiException(
                        "第 " +
                            (r + 1) +
                            " 行“" +
                            col.header() +
                            "”是超长数字，Excel 已丢失精度。请将该列设为文本格式后重新填写"
                    );
                }
                String v = cell == null ? "" : cellText(cell, col.text()).trim();
                if (!v.isEmpty()) any = true;
                values.put(col.key(), v);
            }
            if (!any) continue;
            rows.add(new Row(r + 1, values));
            if (rows.size() > MAX_ROWS) throw new ApiException("一次最多导入 " + MAX_ROWS + " 行");
        }
        return new Sheet(present, rows);
    }

    static String normalizeHeader(String h) {
        return h == null ? "" : h.replace("*", "").replace("（必填）", "").replaceAll("\\s", "");
    }

    private static String cellText(Cell cell, boolean text) {
        var type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "是" : "否";
            default -> "";
        };
    }

    /** “是/否”列：是、Y、1、true、√ 为真；否、空为假。 */
    public static boolean yes(String v) {
        String x = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return List.of("是", "y", "yes", "1", "true", "√", "启用").contains(x);
    }

    /** 日期：支持 2026-01-05、2026/1/5、2026.1.5、2026年1月5日。空值返回 null。 */
    public static java.time.LocalDate date(String v, String header) {
        if (v == null || v.isBlank()) return null;
        var m = java.util.regex.Pattern.compile(
            "(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})\\s*日?"
        ).matcher(v.trim());
        if (m.matches()) try {
            return java.time.LocalDate.of(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
            );
        } catch (java.time.DateTimeException ignored) {}
        throw new ApiException("“" + header + "”不是有效日期：" + v);
    }

    public static BigDecimal decimal(String v, String header) {
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.replace(",", "").replace("¥", "").replace("￥", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException("“" + header + "”不是数字：" + v);
        }
    }
}
