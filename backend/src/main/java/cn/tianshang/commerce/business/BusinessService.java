package cn.tianshang.commerce.business;

import cn.tianshang.commerce.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务字典：客户集团 → 业务线。项目与项目类型的 brand 保存业务线名称，业务线改名时同步更新。
 *
 * <p>录入时可填业务线名称或简称（如“小象”），统一换成业务线名称；“未指定”为保留值。
 */
@Service
public class BusinessService {

    public static final String UNASSIGNED = "未指定";

    private final Database db;

    public BusinessService(Database db) {
        this.db = db;
    }

    public List<Map<String, Object>> list() {
        var groups = db.jdbc.queryForList("SELECT * FROM business_group ORDER BY sort,name");
        var lines = db.jdbc.queryForList(
            """
            SELECT l.*,
              (SELECT COUNT(*) FROM project p WHERE p.brand=l.name) AS projects,
              (SELECT COUNT(*) FROM project_type t WHERE t.brand=l.name) AS project_types
            FROM business_line l ORDER BY l.sort,l.name
            """
        );
        for (var g : groups)
            g.put(
                "lines",
                lines
                    .stream()
                    .filter(l -> l.get("group_id").equals(g.get("id")))
                    .toList()
            );
        return groups;
    }

    public record GroupInput(@NotBlank @Size(max = 60) String name, int sort, boolean active) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String saveGroup(String id, @Valid GroupInput g) {
        String name = g.name().trim();
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO business_group(id,name,sort,active) VALUES(?,?,?,?)",
                id,
                name,
                g.sort(),
                g.active()
            );
            db.audit("BUSINESS_GROUP_CREATED", id, name);
        } else {
            var old = db.one("SELECT * FROM business_group WHERE id=? FOR UPDATE", id);
            db.jdbc.update(
                "UPDATE business_group SET name=?,sort=?,active=? WHERE id=?",
                name,
                g.sort(),
                g.active(),
                id
            );
            db.audit(
                "BUSINESS_GROUP_UPDATED",
                id,
                db.text(old, "name") + " → " + name + (g.active() ? "" : "（停用）")
            );
        }
        return id;
    }

    public record LineInput(
        @NotBlank String groupId,
        @NotBlank @Size(max = 30) String name,
        @Size(max = 30) String shortName,
        int sort,
        boolean active,
        @Size(max = 500) String note
    ) {}

    /** 新增或修改业务线；改名时同步更新所有项目与项目类型。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String saveLine(String id, @Valid LineInput l) {
        db.one("SELECT id FROM business_group WHERE id=?", l.groupId());
        String name = l.name().trim();
        String shortName = l.shortName() == null || l.shortName().isBlank() ? null : l.shortName().trim();
        if (name.equals(UNASSIGNED) || UNASSIGNED.equals(shortName)) throw new ApiException(
            "“未指定”是保留值，不能作为业务线"
        );
        // 名称、简称在所有业务线的名称和简称之间都不能重复，避免录入时匹配到两条
        for (String v : shortName == null ? List.of(name) : List.of(name, shortName)) {
            int n = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_line WHERE (name=? OR short_name=?) AND id<>?",
                Integer.class,
                v,
                v,
                id == null ? "" : id
            );
            if (n > 0) throw new ApiException("“" + v + "”已被其他业务线使用");
        }
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO business_line(id,group_id,name,short_name,sort,active,note) VALUES(?,?,?,?,?,?,?)",
                id,
                l.groupId(),
                name,
                shortName,
                l.sort(),
                l.active(),
                l.note()
            );
            db.audit("BUSINESS_LINE_CREATED", id, name);
            return id;
        }
        var old = db.one("SELECT * FROM business_line WHERE id=? FOR UPDATE", id);
        String oldName = db.text(old, "name");
        db.jdbc.update(
            "UPDATE business_line SET group_id=?,name=?,short_name=?,sort=?,active=?,note=? WHERE id=?",
            l.groupId(),
            name,
            shortName,
            l.sort(),
            l.active(),
            l.note(),
            id
        );
        int projects = 0,
            types = 0;
        if (!oldName.equals(name)) {
            projects = db.jdbc.update("UPDATE project SET brand=? WHERE brand=?", name, oldName);
            types = db.jdbc.update("UPDATE project_type SET brand=? WHERE brand=?", name, oldName);
        }
        db.audit(
            "BUSINESS_LINE_UPDATED",
            id,
            oldName +
                " → " +
                name +
                (l.active() ? "" : "（停用）") +
                (projects + types > 0 ? "；同步更新 " + projects + " 个项目、" + types + " 个项目类型" : "")
        );
        return id;
    }

    /**
     * 把录入的业务（名称或简称）换成业务线名称。停用的业务线只允许保留原值，不能新选。
     *
     * @param current 记录当前保存的值（新增时为 null）
     */
    public String resolve(String input, String current) {
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) throw new ApiException("请选择业务线");
        if (v.equals(UNASSIGNED)) return UNASSIGNED;
        var rows = db.jdbc.queryForList("SELECT name,active FROM business_line WHERE name=? OR short_name=?", v, v);
        if (rows.isEmpty()) throw new ApiException("业务线“" + v + "”不存在，请先在系统管理 → 业务字典中添加");
        String name = rows.get(0).get("name").toString();
        if (!Boolean.TRUE.equals(rows.get(0).get("active")) && !name.equals(current)) throw new ApiException(
            "业务线“" + name + "”已停用"
        );
        return name;
    }

    /** 历史导入用：能匹配就用业务线名称，匹配不到记为“未指定”。 */
    public String resolveOrUnassigned(String input) {
        try {
            return resolve(input, null);
        } catch (ApiException e) {
            return UNASSIGNED;
        }
    }

    /** 名称或简称换成业务线名称；匹配不到原样返回（用于筛选参数）。 */
    public String canonical(String input) {
        if (input == null || input.isBlank()) return input;
        var rows = db.jdbc.queryForList(
            "SELECT name FROM business_line WHERE name=? OR short_name=?",
            String.class,
            input.trim(),
            input.trim()
        );
        return rows.isEmpty() ? input.trim() : rows.get(0);
    }

    /** 业务线名称 → 集团名称。 */
    public Map<String, String> groupOfLine() {
        var out = new HashMap<String, String>();
        for (var r : db.jdbc.queryForList(
            "SELECT l.name,g.name AS group_name FROM business_line l JOIN business_group g ON g.id=l.group_id"
        ))
            out.put(r.get("name").toString(), r.get("group_name").toString());
        return out;
    }

    public List<String> activeLineNames() {
        return db.jdbc.queryForList(
            """
            SELECT l.name FROM business_line l JOIN business_group g ON g.id=l.group_id
            WHERE l.active=TRUE ORDER BY g.sort,g.name,l.sort,l.name
            """,
            String.class
        );
    }
}
