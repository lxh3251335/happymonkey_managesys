package cn.tianshang.commerce.finance;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 回款流水查询与历史门店流水冲正；新回款按合同登记（/api/contracts/receipts）。 */
@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashService service;

    public CashController(CashService service) {
        this.service = service;
    }

    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger() {
        return service.ledger();
    }

    @PostMapping("/legacy-refund")
    public Map<String, String> refundLegacy(@Valid @RequestBody CashService.RefundInput input) {
        return service.refundLegacy(input);
    }
}
