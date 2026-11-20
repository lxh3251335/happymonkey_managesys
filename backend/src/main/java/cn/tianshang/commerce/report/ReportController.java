package cn.tianshang.commerce.report;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> dashboard(
        @RequestParam(required = false) String brand,
        @RequestParam(required = false) String group
    ) {
        return service.dashboard(brand, group);
    }
}
