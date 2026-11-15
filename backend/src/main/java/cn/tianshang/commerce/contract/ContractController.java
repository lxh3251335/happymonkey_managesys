package cn.tianshang.commerce.contract;

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

    public ContractController(
        ContractService service,
        ContractAttachmentService attachments,
        ContractRecordService records,
        ContractExecutionService execution
    ) {
        this.service = service;
        this.attachments = attachments;
        this.records = records;
        this.execution = execution;
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
        boolean preview = inline && (f.contentType().startsWith("image/") || f.contentType().equals("application/pdf"));
        var disposition = (preview ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(
            f.fileName(),
            StandardCharsets.UTF_8
        ).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(f.contentType()))
            .contentLength(f.size())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            // Chrome 不渲染带 sandbox 的 PDF，内联预览 PDF 时改用不允许脚本和外部资源的策略
            .header(
                "Content-Security-Policy",
                preview && f.contentType().equals("application/pdf")
                    ? "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; object-src 'self'; frame-ancestors 'self'"
                    : "sandbox"
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new FileSystemResource(f.path()));
    }

    @DeleteMapping("/attachments/{id}")
    public void deleteAttachment(@PathVariable String id) {
        attachments.delete(id);
    }
}
