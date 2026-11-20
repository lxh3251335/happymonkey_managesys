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

    public ExcelController(ExcelService service) {
        this.service = service;
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
        return ResponseEntity.ok()
            .contentType(XLSX)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString()
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(service.export(d, template));
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
