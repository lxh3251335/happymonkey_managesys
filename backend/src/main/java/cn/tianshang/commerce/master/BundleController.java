package cn.tianshang.commerce.master;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 标准组合与标准品的电气件清单。 */
@RestController
@RequestMapping("/api")
public class BundleController {

    private final BundleService bundles;
    private final ProductComponentService components;

    public BundleController(BundleService bundles, ProductComponentService components) {
        this.bundles = bundles;
        this.components = components;
    }

    @GetMapping("/bundles")
    public List<Map<String, Object>> bundles() {
        return bundles.list();
    }

    @PostMapping("/bundles")
    public Map<String, String> create(@Valid @RequestBody BundleService.BundleInput input) {
        return Map.of("id", bundles.save(null, input));
    }

    @PutMapping("/bundles/{id}")
    public void update(@PathVariable String id, @Valid @RequestBody BundleService.BundleInput input) {
        bundles.save(id, input);
    }

    @GetMapping("/products/{id}/components")
    public List<Map<String, Object>> components(@PathVariable String id) {
        return components.list(id);
    }

    @PutMapping("/products/{id}/components")
    public void saveComponents(
        @PathVariable String id,
        @Valid @RequestBody ProductComponentService.ComponentsInput input
    ) {
        components.save(id, input);
    }
}
