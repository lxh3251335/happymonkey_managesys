package cn.tianshang.commerce.supplier;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/offers")
public class SupplierController {

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> offers() {
        return service.offers();
    }

    @PostMapping
    public Map<String, String> create(@Valid @RequestBody SupplierService.OfferInput input) {
        return service.create(input);
    }

    @GetMapping("/compare")
    public List<Map<String, Object>> compare(@RequestParam String productId, @RequestParam BigDecimal quantity) {
        return service.compare(productId, quantity);
    }
}
