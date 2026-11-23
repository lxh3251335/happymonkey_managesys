package cn.tianshang.commerce.excel;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/excel")
public class ExcelController {

    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final ExcelService service;
    private final WorkbookService workbook;

    public ExcelController(ExcelService service, WorkbookService workbook) {
        this.service = service;
        this.workbook = workbook;
    }

    /** 各数据表的顺序、说明和当前角色能否导入。 */
    @GetMapping("/workbook/datasets")
    public java.util.List<WorkbookService.DatasetInfo> datasets() {
        return workbook.datasets();
    }

    /** 完整模板：所有数据表 + 总说明。 */
    @GetMapping("/workbook/template")
    public ResponseEntity<byte[]> workbookTemplate() {
        return xlsx("基础资料-完整导入模板.xlsx", workbook.template());
    }

    @PostMapping(value = "/workbook/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkbookService.WorkbookReport workbookPreview(@RequestParam MultipartFile file) {
        return workbook.preview(file);
    }

    @PostMapping(value = "/workbook/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkbookService.WorkbookReport workbookImport(@RequestParam MultipartFile file) {
        return workbook.importFile(file);
    }

    /** 导出数据；template=true 时只导出带说明的空模板。 */
    @GetMapping("/{dataset}")
    public ResponseEntity<byte[]> export(
        @PathVariable String dataset,
        @RequestParam(defaultValue = "false") boolean template
    ) {
        var d = ExcelService.Dataset.of(dataset);
        String name =
            d.title +
            (template ? "-导入模板" : "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)) +
            ".xlsx";
        return xlsx(name, service.export(d, template));
    }

    private static ResponseEntity<byte[]> xlsx(String name, byte[] body) {
        return ResponseEntity.ok()
            .contentType(XLSX)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString()
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(body);
    }

    @PostMapping(value = "/{dataset}/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExcelService.Report preview(@PathVariable String dataset, @RequestParam MultipartFile file) {
        return service.preview(ExcelService.Dataset.of(dataset), file);
    }

    @PostMapping(value = "/{dataset}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExcelService.Report importFile(@PathVariable String dataset, @RequestParam MultipartFile file) {
        return service.importFile(ExcelService.Dataset.of(dataset), file);
    }
}
