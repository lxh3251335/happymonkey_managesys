package cn.tianshang.commerce.auth;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final Database db;
    private final PasswordEncoder encoder;

    public AuthController(Database db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken(), "header", csrf.getHeaderName());
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(Authentication auth) {
        return db.one("SELECT id,username,display_name,role FROM app_user WHERE username=?", auth.getName());
    }

    public record PasswordChange(
        @NotBlank String oldPassword,
        @Size(min = 12, max = 100) @NotBlank String newPassword
    ) {}

    @PostMapping("/auth/password")
    @Transactional
    public void password(@Valid @RequestBody PasswordChange input, Authentication auth) {
        var row = db.one("SELECT * FROM app_user WHERE username=?", auth.getName());
        if (!encoder.matches(input.oldPassword(), row.get("password_hash").toString())) throw new ApiException(
            "原密码错误"
        );
        db.jdbc.update(
            "UPDATE app_user SET password_hash=? WHERE username=?",
            encoder.encode(input.newPassword()),
            auth.getName()
        );
        db.audit("PASSWORD_CHANGED", row.get("id").toString(), "用户修改密码");
    }
}
