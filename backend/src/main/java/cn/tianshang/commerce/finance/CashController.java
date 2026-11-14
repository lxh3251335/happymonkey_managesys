package cn.tianshang.commerce.finance;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashService service;

    public CashController(CashService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> entries() {
        return service.entries();
    }

    @GetMapping("/balances")
    public List<Map<String, Object>> balances() {
        return service.balances();
    }

    @PostMapping
    public Map<String, String> create(@Valid @RequestBody CashService.CashInput input) {
        return service.create(input);
    }
}
