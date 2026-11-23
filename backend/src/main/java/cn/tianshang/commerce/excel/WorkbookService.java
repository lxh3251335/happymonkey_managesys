package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.CostRedaction;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.excel.ExcelService.Dataset;
import cn.tianshang.commerce.excel.ExcelService.Report;
import java.util.*;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.multipart.MultipartFile;

/**
 * 整本导入：一个 Excel 里按表名放多张数据表（表名 = 数据类型名称，如“客户资料”“标准产品库”），
 * 按资料的依赖顺序逐表“校验 → 写入”，后面的表能看到前面表刚导入的资料（比如同一本里先建供应商再建产品）。
 * 预览时同样逐表写入，最后整体回滚；正式导入时任何一张表有错误则整本不写入。
 */
@Service
public class WorkbookService {

    public record SheetReport(String dataset, String title, String status, Report report, String message) {}

    /** status：OK 已检查无误 / ERROR 有错误 / SKIPPED 前面的表有错误未检查 / EMPTY 表里没有数据行 / IGNORED 未识别的表 */
    public record WorkbookReport(List<SheetReport> sheets, int created, int updated, int unchanged, int errors) {}

    public record DatasetInfo(String dataset, String title, String about, boolean canImport) {}

    private final Database db;
    private final ExcelService excel;

    public WorkbookService(Database db, ExcelService excel) {
        this.db = db;
        this.excel = excel;
    }

    /** 各数据表的顺序、说明和当前角色能否导入（“数据导入”页用）。 */
    public List<DatasetInfo> datasets() {
        return Arrays.stream(Dataset.values())
            .map(d -> new DatasetInfo(d.path, d.title, d.about, excel.canImport(d)))
            .toList();
    }

    /** 完整模板：所有数据表（空表）+ 一张总的填写说明，按导入顺序排列。 */
    public byte[] template() {
        var sheets = new ArrayList<ExcelIo.SheetSpec>();
        var notes = new ArrayList<String>();
        notes.add("基础资料完整导入模板");
        notes.add(
            "1. 各表按导入顺序排列，后面的表依赖前面的资料；可以一次填多张表后用“整本导入”，也可以把单张表另存后在对应页面单独导入。"
        );
        notes.add("2. 不需要导入的表留空即可；每张表第一行是表头，列名带 * 的为必填。");
        notes.add("3. 整本导入时按表的顺序逐张检查并写入，任何一张表有错误则整本不写入。");
        notes.add(
            "4. 导入顺序：" +
                String.join(
                    " → ",
                    Arrays.stream(Dataset.values())
                        .map(d -> d.title)
                        .toList()
                )
        );
        for (var d : Dataset.values()) {
            var columns = excel
                .columns(d)
                .stream()
                .filter(c -> Roles.canSeeCost() || !CostRedaction.SENSITIVE.contains(c.key()))
                .toList();
            sheets.add(new ExcelIo.SheetSpec(d.title, columns, List.of()));
        }
        db.audit("EXCEL_EXPORT", "workbook", "完整导入模板");
        return ExcelIo.write(sheets, notes, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public WorkbookReport preview(MultipartFile file) {
        var report = run(file);
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return report;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public WorkbookReport importFile(MultipartFile file) {
        var report = run(file);
        if (report.errors() > 0) throw new ApiException(
            "有 " + report.errors() + " 处错误，整本未导入。请先在预览中修正"
        );
        if (report.created() + report.updated() == 0) throw new ApiException("没有需要写入的资料");
        db.audit(
            "EXCEL_IMPORT",
            "workbook",
            "整本导入：新增 " + report.created() + "，更新 " + report.updated() + "，无变化 " + report.unchanged()
        );
        return report;
    }

    private WorkbookReport run(MultipartFile file) {
        try (Workbook wb = ExcelIo.open(file)) {
            var byTitle = new HashMap<String, org.apache.poi.ss.usermodel.Sheet>();
            var unknown = new ArrayList<String>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                String name = sheet.getSheetName().trim();
                if (name.equals("填写说明") || name.equals("说明")) continue;
                var d = Arrays.stream(Dataset.values())
                    .filter(x -> x.title.equals(name) || x.path.equals(name))
                    .findFirst();
                if (d.isPresent()) byTitle.put(d.get().title, sheet);
                else unknown.add(name);
            }
            if (byTitle.isEmpty()) throw new ApiException(
                "没有识别出任何数据表：表名应与模板一致（如“客户资料”“标准产品库”）"
            );
            var out = new ArrayList<SheetReport>();
            int c = 0,
                u = 0,
                s = 0,
                e = 0;
            boolean blocked = false;
            for (var d : Dataset.values()) {
                var sheet = byTitle.get(d.title);
                if (sheet == null) continue;
                if (blocked) {
                    out.add(new SheetReport(d.path, d.title, "SKIPPED", null, "前面的表有错误，这张表未检查"));
                    continue;
                }
                try {
                    var parsed = ExcelIo.readSheet(sheet, excel.columns(d));
                    if (parsed.rows().isEmpty()) {
                        out.add(new SheetReport(d.path, d.title, "EMPTY", null, "没有数据行，跳过"));
                        continue;
                    }
                    excel.requireImportRole(d);
                    var ops = excel.plan(d, parsed);
                    var report = ExcelService.report(d, ops);
                    c += report.created();
                    u += report.updated();
                    s += report.unchanged();
                    e += report.errors();
                    if (report.errors() > 0) {
                        blocked = true;
                        out.add(new SheetReport(d.path, d.title, "ERROR", report, "有 " + report.errors() + " 处错误"));
                        continue;
                    }
                    // 先写入，后面的表才能引用这张表里新建的资料
                    ExcelService.apply(ops);
                    out.add(new SheetReport(d.path, d.title, "OK", report, null));
                } catch (ApiException ex) {
                    blocked = true;
                    e++;
                    out.add(new SheetReport(d.path, d.title, "ERROR", null, ex.getMessage()));
                }
            }
            for (String name : unknown) out.add(new SheetReport(null, name, "IGNORED", null, "未识别的表，已忽略"));
            return new WorkbookReport(out, c, u, s, e);
        } catch (java.io.IOException ex) {
            throw new ApiException("无法读取 Excel 文件");
        }
    }
}
