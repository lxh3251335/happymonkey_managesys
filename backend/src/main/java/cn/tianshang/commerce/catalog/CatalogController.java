package cn.tianshang.commerce.catalog;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/project-types")
    public List<Map<String, Object>> types() {
        return service.types();
    }

    @PostMapping("/project-types")
    public Map<String, String> createType(@Valid @RequestBody CatalogService.TypeInput input) {
        return Map.of("id", service.saveType(null, input));
    }

    @PutMapping("/project-types/{id}")
    public void updateType(@PathVariable String id, @Valid @RequestBody CatalogService.TypeInput input) {
        service.saveType(id, input);
    }

    @GetMapping("/project-types/suggest")
    public Map<String, Object> suggest(@RequestParam String brand) {
        return service.suggest(brand);
    }

    @GetMapping("/project-types/{id}/prefill")
    public Map<String, Object> prefill(
        @PathVariable String id,
        @RequestParam(required = false) String supplierId,
        @RequestParam(required = false) String customerId
    ) {
        return service.prefill(id, supplierId, customerId);
    }

    @GetMapping("/supplier-templates")
    public List<Map<String, Object>> supplierTemplates() {
        return service.supplierTemplates();
    }

    @PostMapping("/supplier-templates")
    public Map<String, String> saveSupplierTemplate(@Valid @RequestBody CatalogService.SupplierTemplateInput input) {
        return Map.of("id", service.saveSupplierTemplate(input));
    }
}
