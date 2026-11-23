package cn.tianshang.commerce.check;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data-check")
public class DataCheckController {

    private static final MediaType XLSX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final DataCheckService service;

    public DataCheckController(DataCheckService service) {
        this.service = service;
    }

    @GetMapping
    public DataCheckService.Report run() {
        return service.run();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
            .contentType(XLSX)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("数据检查结果.xlsx", StandardCharsets.UTF_8).build().toString()
            )
            .body(service.export());
    }
}
