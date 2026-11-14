package cn.tianshang.commerce.auth;

import cn.tianshang.commerce.common.Database;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrap implements CommandLineRunner {

    private final Database db;
    private final PasswordEncoder encoder;
    private final String password;
    private final Environment environment;

    public UserBootstrap(
        Database db,
        PasswordEncoder encoder,
        @Value("${app.admin-password}") String password,
        Environment environment
    ) {
        this.db = db;
        this.encoder = encoder;
        this.password = password;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) > 0) return;
        if (password.length() < 12) throw new IllegalStateException("首次启动必须配置至少12位 ADMIN_PASSWORD");
        if (environment.matchesProfiles("prod") && password.equals("DevAdmin2026!")) throw new IllegalStateException(
            "生产环境不得使用开发密码"
        );
        db.jdbc.update(
            "INSERT INTO app_user(id,username,password_hash,display_name,role) VALUES(?,?,?,?,?)",
            db.id(),
            "admin",
            encoder.encode(password),
            "系统管理员",
            "ADMIN"
        );
    }
}
