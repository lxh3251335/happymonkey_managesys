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
        @NotNull @Pattern(regexp = "ADMIN|MANAGER|SALES|FINANCE|VIEWER") String role
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

    public record UserUpdate(
        @NotBlank @Size(max = 100) String displayName,
        @NotNull @Pattern(regexp = "ADMIN|MANAGER|SALES|FINANCE|VIEWER") String role,
        boolean enabled
    ) {}

    /** 修改显示名称、角色、启用状态。不能停用或降级自己，且系统至少保留一个启用的管理员。 */
    @Transactional
    public void updateUser(String id, @Valid UserUpdate u) {
        var row = db.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", id);
        boolean self = row.get("username").equals(currentUser());
        boolean losesAdmin = row.get("role").equals("ADMIN") && (!u.role().equals("ADMIN") || !u.enabled());
        if (self && losesAdmin) throw new ApiException("不能停用或降级当前登录的管理员账户");
        if (
            losesAdmin &&
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE AND id<>?",
                Integer.class,
                id
            ) ==
            0
        ) throw new ApiException("系统至少需要保留一个启用的管理员");
        db.jdbc.update(
            "UPDATE app_user SET display_name=?,role=?,enabled=? WHERE id=?",
            u.displayName().trim(),
            u.role(),
            u.enabled(),
            id
        );
        db.audit(
            "USER_UPDATED",
            id,
            row.get("username") + "：" + row.get("role") + "→" + u.role() + (u.enabled() ? "，启用" : "，停用")
        );
    }

    public record PasswordReset(@NotBlank @Size(min = 12, max = 100) String password) {}

    @Transactional
    public void resetPassword(String id, @Valid PasswordReset p) {
        var row = db.one("SELECT username FROM app_user WHERE id=?", id);
        db.jdbc.update("UPDATE app_user SET password_hash=? WHERE id=?", encoder.encode(p.password()), id);
        db.audit("USER_PASSWORD_RESET", id, "管理员重置 " + row.get("username") + " 的密码");
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    public List<Map<String, Object>> audit() {
        return db.jdbc.queryForList("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 300");
    }

    public List<Map<String, Object>> imports() {
        return db.jdbc.queryForList("SELECT * FROM import_batch ORDER BY created_at DESC");
    }
}
