package cn.tianshang.commerce.business;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessService service;

    public BusinessController(BusinessService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping("/groups")
    public Map<String, String> createGroup(@Valid @RequestBody BusinessService.GroupInput input) {
        return Map.of("id", service.saveGroup(null, input));
    }

    @PutMapping("/groups/{id}")
    public void updateGroup(@PathVariable String id, @Valid @RequestBody BusinessService.GroupInput input) {
        service.saveGroup(id, input);
    }

    @PostMapping("/lines")
    public Map<String, String> createLine(@Valid @RequestBody BusinessService.LineInput input) {
        return Map.of("id", service.saveLine(null, input));
    }

    @PutMapping("/lines/{id}")
    public void updateLine(@PathVariable String id, @Valid @RequestBody BusinessService.LineInput input) {
        service.saveLine(id, input);
    }
}
