package cn.tianshang.commerce.finance;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 回款单：一笔到账分到多份合同。 */
@RestController
@RequestMapping("/api/receipt-batches")
public class ReceiptBatchController {

    private final ReceiptBatchService service;

    public ReceiptBatchController(ReceiptBatchService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam String customerId, @RequestParam BigDecimal amount) {
        return service.suggest(customerId, amount);
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody ReceiptBatchService.BatchInput input) {
        return service.create(input);
    }

    @PostMapping("/{id}/allocate")
    public Map<String, Object> allocate(
        @PathVariable String id,
        @Valid @RequestBody ReceiptBatchService.AllocateInput input
    ) {
        return service.allocate(id, input);
    }
}
