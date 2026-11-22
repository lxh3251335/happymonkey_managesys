package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.FileResponses;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService service;
    private final ContractAttachmentService attachments;
    private final ContractRecordService records;
    private final ContractExecutionService execution;
    private final ContractNumbers numbers;

    public ContractController(
        ContractService service,
        ContractAttachmentService attachments,
        ContractRecordService records,
        ContractExecutionService execution,
        ContractNumbers numbers
    ) {
        this.service = service;
        this.attachments = attachments;
        this.records = records;
        this.execution = execution;
        this.numbers = numbers;
    }

    /** 建议的下一个合同号（按该客户的编号规律）。 */
    @GetMapping("/next-number")
    public ContractNumbers.Suggestion nextNumber(@RequestParam(required = false) String customerId) {
        return numbers.suggest(customerId);
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String customerId, @RequestParam String contractKey) {
        return records.detail(customerId, contractKey);
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody ContractRecordService.CreateInput input) {
        return records.create(input);
    }

    @PostMapping("/from-quote/{quoteId}")
    public Map<String, Object> fromQuote(
        @PathVariable String quoteId,
        @Valid @RequestBody ContractRecordService.FromQuoteInput input
    ) {
        return records.fromQuote(quoteId, input);
    }

    @PostMapping("/replace-quote/{quoteId}")
    public Map<String, Object> replaceQuote(
        @PathVariable String quoteId,
        @Valid @RequestBody ContractRecordService.ReplaceQuoteInput input
    ) {
        return records.replaceQuote(quoteId, input);
    }

    @PutMapping("/info")
    public Map<String, Object> update(
        @RequestParam String customerId,
        @RequestParam String contractKey,
        @Valid @RequestBody ContractRecordService.UpdateInput input
    ) {
        return records.update(customerId, contractKey, input);
    }

    @GetMapping("/changes/pending")
    public List<Map<String, Object>> pendingChanges() {
        return records.pendingChanges();
    }

    @PostMapping("/changes/{id}/approve")
    public void approve(@PathVariable String id, @Valid @RequestBody ContractRecordService.ReviewInput input) {
        records.approve(id, input);
    }

    @PostMapping("/changes/{id}/reject")
    public void reject(@PathVariable String id, @Valid @RequestBody ContractRecordService.ReviewInput input) {
        records.reject(id, input);
    }

    @PostMapping("/changes/{id}/withdraw")
    public void withdraw(@PathVariable String id) {
        records.withdraw(id);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(
        @RequestParam(required = false) String customerId,
        @RequestParam(required = false) String contractKey
    ) {
        return execution.invoices(customerId, contractKey);
    }

    @PostMapping("/invoices")
    public Map<String, Object> invoice(@Valid @RequestBody ContractExecutionService.InvoiceInput input) {
        return execution.invoice(input);
    }

    @PostMapping("/invoices/{id}/delete")
    public void deleteInvoice(@PathVariable String id, @Valid @RequestBody ContractExecutionService.DeleteInput input) {
        execution.deleteInvoice(id, input);
    }

    @GetMapping("/costs")
    public List<Map<String, Object>> costs(@RequestParam String customerId, @RequestParam String contractKey) {
        return execution.costs(customerId, contractKey);
    }

    @PostMapping("/costs")
    public Map<String, Object> cost(@Valid @RequestBody ContractExecutionService.CostInput input) {
        return execution.cost(input);
    }

    @PostMapping("/costs/{id}/delete")
    public void deleteCost(@PathVariable String id, @Valid @RequestBody ContractExecutionService.DeleteInput input) {
        execution.deleteCost(id, input);
    }

    @GetMapping
    public List<Map<String, Object>> contracts() {
        return service.contracts();
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@Valid @RequestBody ContractService.ConfirmInput input) {
        return service.confirm(input);
    }

    @PostMapping("/due-dates")
    public Map<String, Object> dueDates(@Valid @RequestBody ContractService.DueDatesInput input) {
        return service.dueDates(input);
    }

    @GetMapping("/receipts")
    public List<Map<String, Object>> receipts() {
        return service.receipts();
    }

    @PostMapping("/receipts")
    public Map<String, Object> receipt(@Valid @RequestBody ContractService.ReceiptInput input) {
        return service.receipt(input);
    }

    @GetMapping("/attachments")
    public List<Map<String, Object>> attachments(@RequestParam String customerId, @RequestParam String contractKey) {
        return attachments.list(customerId, contractKey);
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
        @RequestParam String customerId,
        @RequestParam String contractKey,
        @RequestParam String category,
        @RequestParam(required = false) String note,
        @RequestParam MultipartFile file
    ) {
        return attachments.upload(customerId, contractKey, category, note, file);
    }

    /** 下载附件；PDF 和图片可 inline 预览，其余一律作为附件下载。 */
    @GetMapping("/attachments/{id}/file")
    public ResponseEntity<FileSystemResource> download(
        @PathVariable String id,
        @RequestParam(defaultValue = "false") boolean inline
    ) {
        var f = attachments.file(id);
        return FileResponses.of(f.fileName(), f.contentType(), f.size(), f.path(), inline);
    }

    @DeleteMapping("/attachments/{id}")
    public void deleteAttachment(@PathVariable String id) {
        attachments.delete(id);
    }
}
