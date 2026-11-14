package cn.tianshang.commerce.contract;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> contracts() {
        return service.contracts();
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@Valid @RequestBody ContractService.ConfirmInput input) {
        return service.confirm(input);
    }

    @GetMapping("/receipts")
    public List<Map<String, Object>> receipts() {
        return service.receipts();
    }

    @PostMapping("/receipts")
    public Map<String, Object> receipt(@Valid @RequestBody ContractService.ReceiptInput input) {
        return service.receipt(input);
    }
}
