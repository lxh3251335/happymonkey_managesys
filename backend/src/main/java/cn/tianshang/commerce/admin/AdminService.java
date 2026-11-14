package cn.tianshang.commerce.admin;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminService {

    private final Database db;
    private final PasswordEncoder encoder;

    public AdminService(Database db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    public List<Map<String, Object>> users() {
        return db.jdbc.queryForList(
            "SELECT id,username,display_name,role,enabled,created_at FROM app_user ORDER BY created_at"
        );
    }

    public record UserInput(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{3,40}") String username,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(min = 12, max = 100) String password,
        @NotNull @Pattern(regexp = "ADMIN|SALES|FINANCE|VIEWER") String role
    ) {}

    @Transactional
    public void createUser(@Valid UserInput u) {
        String id = db.id();
        db.jdbc.update(
            "INSERT INTO app_user(id,username,password_hash,display_name,role) VALUES(?,?,?,?,?)",
            id,
            u.username(),
            encoder.encode(u.password()),
            u.displayName(),
            u.role()
        );
        db.audit("USER_CREATED", id, u.username() + " / " + u.role());
    }

    public List<Map<String, Object>> audit() {
        return db.jdbc.queryForList("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 300");
    }

    public List<Map<String, Object>> imports() {
        return db.jdbc.queryForList("SELECT * FROM import_batch ORDER BY created_at DESC");
    }
}
