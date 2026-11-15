package cn.tianshang.commerce.project;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping("/projects")
    public Map<String, String> create(@Valid @RequestBody ProjectService.ProjectInput input) {
        return Map.of("id", service.create(input));
    }

    @PutMapping("/projects/{id}")
    public void update(@PathVariable String id, @Valid @RequestBody ProjectService.ProjectInput input) {
        service.update(id, input);
    }

    @PutMapping("/projects/{id}/delivery")
    public void delivery(@PathVariable String id, @Valid @RequestBody ProjectService.DeliveryInput input) {
        service.delivery(id, input);
    }

    @PostMapping("/projects/owner")
    public Map<String, Object> assignOwner(@Valid @RequestBody ProjectService.AssignInput input) {
        return service.assignOwner(input);
    }

    @GetMapping("/projects/couriers")
    public List<String> couriers() {
        return ProjectService.COURIERS;
    }

    @GetMapping("/owners")
    public List<Map<String, Object>> owners() {
        return service.owners();
    }
}
