package cn.tianshang.commerce.common;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Database {

    public final JdbcTemplate jdbc;

    public Database(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String id() {
        return UUID.randomUUID().toString();
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new ApiException("记录不存在");
        return rows.get(0);
    }

    public String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    public void audit(String action, String id, String detail) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        jdbc.update(
            "INSERT INTO audit_log(id,actor,action,entity_id,detail) VALUES(?,?,?,?,?)",
            id(),
            auth == null ? "system" : auth.getName(),
            action,
            id,
            detail
        );
    }
}
