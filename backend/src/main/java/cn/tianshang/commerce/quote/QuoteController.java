package cn.tianshang.commerce.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService service;
    private final QuoteTemplateService templates;

    public QuoteController(QuoteService service, QuoteTemplateService templates) {
        this.service = service;
        this.templates = templates;
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return templates.list();
    }

    @PostMapping("/templates")
    public Map<String, String> createTemplate(@Valid @RequestBody QuoteTemplateService.TemplateInput input) {
        return Map.of("id", templates.create(input));
    }

    @PutMapping("/templates/{id}")
    public void updateTemplate(@PathVariable String id, @Valid @RequestBody QuoteTemplateService.TemplateInput input) {
        templates.update(id, input);
    }

    @PostMapping("/templates/{id}/default")
    public void defaultTemplate(@PathVariable String id) {
        templates.makeDefault(id);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/reference")
    public Map<String, Object> reference(@RequestParam String projectId) {
        return service.reference(projectId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/customer-copy")
    public Map<String, Object> customerCopy(@PathVariable String id) {
        return service.customerCopy(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    public Map<String, String> create(@Valid @RequestBody QuoteInput input) {
        return Map.of("id", service.create(input));
    }

    public record LostInput(@NotBlank @Size(max = 300) String reason) {}

    @PostMapping("/{id}/lose")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    public void lose(@PathVariable String id, @Valid @RequestBody LostInput input) {
        service.lose(id, input.reason());
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    public void reopen(@PathVariable String id) {
        service.reopen(id);
    }

    @PutMapping("/{id}/costs")
    public Map<String, Integer> correctCosts(
        @PathVariable String id,
        @Valid @RequestBody QuoteService.CostCorrection input
    ) {
        return Map.of("changed", service.correctCosts(id, input));
    }

    public record Transition(LocalDate dueDate) {}

    @PostMapping("/{id}/{action:issue|accept}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    public void transition(@PathVariable String id, @PathVariable String action, @RequestBody Transition input) {
        service.transition(id, action, input.dueDate());
    }
}
