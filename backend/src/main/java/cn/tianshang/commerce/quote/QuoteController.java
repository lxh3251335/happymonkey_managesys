package cn.tianshang.commerce.quote;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
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
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public Map<String, String> create(@Valid @RequestBody QuoteInput input) {
        return Map.of("id", service.create(input));
    }

    public record Transition(LocalDate dueDate) {}

    @PostMapping("/{id}/{action:issue|accept}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES')")
    public void transition(@PathVariable String id, @PathVariable String action, @RequestBody Transition input) {
        service.transition(id, action, input.dueDate());
    }
}
