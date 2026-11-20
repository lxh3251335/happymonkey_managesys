package cn.tianshang.commerce.catalog;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer-templates")
public class CustomerTemplateController {

    private final CustomerTemplateService service;

    public CustomerTemplateController(CustomerTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public Map<String, String> save(@Valid @RequestBody CustomerTemplateService.Input input) {
        return Map.of("id", service.save(input));
    }

    @GetMapping("/from-history")
    public Map<String, Object> fromHistory(@RequestParam String customerId, @RequestParam String projectTypeId) {
        return service.fromHistory(customerId, projectTypeId);
    }
}
