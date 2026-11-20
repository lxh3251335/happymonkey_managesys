package cn.tianshang.commerce.finance;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 发票登记：识别发票 PDF，一张发票分到多份合同。 */
@RestController
@RequestMapping("/api/invoice-entries")
public class InvoiceEntryController {

    private final InvoiceEntryService service;

    public InvoiceEntryController(InvoiceEntryService service) {
        this.service = service;
    }

    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestParam("file") MultipartFile file) {
        return service.parse(file);
    }

    @GetMapping("/suggest")
    public Map<String, Object> suggest(
        @RequestParam String customerId,
        @RequestParam BigDecimal amount,
        @RequestParam(required = false) String hint
    ) {
        return service.suggest(customerId, amount, hint);
    }

    /** data 为 JSON（EntryInput），file 为发票原件（可选）。 */
    @PostMapping(consumes = "multipart/form-data")
    public Map<String, Object> create(
        @Valid @RequestPart("data") InvoiceEntryService.EntryInput input,
        @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        return service.create(input, file);
    }
}
