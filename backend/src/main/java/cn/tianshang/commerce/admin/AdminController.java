package cn.tianshang.commerce.admin;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return service.users();
    }

    @PostMapping("/users")
    public void create(@Valid @RequestBody AdminService.UserInput input) {
        service.createUser(input);
    }

    @PutMapping("/users/{id}")
    public void update(@PathVariable String id, @Valid @RequestBody AdminService.UserUpdate input) {
        service.updateUser(id, input);
    }

    @PostMapping("/users/{id}/password")
    public void reset(@PathVariable String id, @Valid @RequestBody AdminService.PasswordReset input) {
        service.resetPassword(id, input);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return service.audit();
    }

    @GetMapping("/imports")
    public List<Map<String, Object>> imports() {
        return service.imports();
    }
}
