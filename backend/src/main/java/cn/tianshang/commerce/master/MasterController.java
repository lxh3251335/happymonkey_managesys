package cn.tianshang.commerce.master;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MasterController {

    private final MasterService service;

    public MasterController(MasterService service) {
        this.service = service;
    }

    @GetMapping("/partners")
    public List<Map<String, Object>> partners() {
        return service.partners();
    }

    @PostMapping("/partners")
    public Map<String, String> partner(@Valid @RequestBody MasterService.PartnerInput input) {
        return service.partner(input);
    }

    @PutMapping("/partners/{id}")
    public void updatePartner(@PathVariable String id, @Valid @RequestBody MasterService.PartnerInput input) {
        service.updatePartner(id, input);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products() {
        return service.products();
    }

    @PostMapping("/products")
    public Map<String, String> product(@Valid @RequestBody MasterService.ProductInput input) {
        return service.product(input);
    }

    @PutMapping("/products/{id}")
    public void updateProduct(@PathVariable String id, @Valid @RequestBody MasterService.ProductInput input) {
        service.updateProduct(id, input);
    }

    public record MainstreamInput(boolean mainstream) {}

    @PutMapping("/products/{id}/mainstream")
    public void mainstream(@PathVariable String id, @RequestBody MainstreamInput input) {
        service.setMainstream(id, input.mainstream());
    }
}
