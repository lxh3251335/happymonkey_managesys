package cn.tianshang.commerce.bot;

import cn.tianshang.commerce.common.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机器人账号绑定：聊天软件里的人和系统账号一一对应，机器人按绑定账号的角色办事。
 *
 * <p>绑定方式：登录网页生成一次性绑定码（10 分钟有效），在和机器人的私聊里发“绑定 12345678”。
 * 这样能证明聊天软件里的这个人确实掌握那个系统账号。
 */
@Service
public class BotBindingService {

    public static final String WECOM = "WECOM";
    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final int MAX_WRONG_CODES = 5;

    public record BoundUser(String userId, String username, String displayName, String role) {}

    private record Lock(int wrong, Instant until) {}

    private final Database db;
    private final Ownership ownership;
    private final SecureRandom random = new SecureRandom();
    /** 连续输错绑定码的人暂时不让再试，防止逐个猜。 */
    private final Map<String, Lock> wrongCodes = new ConcurrentHashMap<>();

    public BotBindingService(Database db, Ownership ownership) {
        this.db = db;
        this.ownership = ownership;
    }

    // ===================== 网页上：当前登录用户 =====================

    /** 当前用户的绑定情况。 */
    public Map<String, Object> mine() {
        var rows = db.jdbc.queryForList(
            "SELECT created_at,last_seen_at FROM bot_binding WHERE channel=? AND user_id=?",
            WECOM,
            ownership.userId()
        );
        var out = new LinkedHashMap<String, Object>();
        out.put("bound", !rows.isEmpty());
        if (!rows.isEmpty()) out.putAll(rows.get(0));
        return out;
    }

    /** 生成绑定码；同一个人以前没用掉的码作废。 */
    @Transactional
    public Map<String, Object> newCode() {
        String userId = ownership.userId();
        if (userId == null) throw new ApiException("请先登录");
        db.jdbc.update("DELETE FROM bot_bind_code WHERE user_id=? OR expires_at<?", userId, LocalDateTime.now());
        String code;
        do code = String.format("%08d", random.nextInt(100_000_000)); while (
            db.jdbc.queryForObject("SELECT COUNT(*) FROM bot_bind_code WHERE code=?", Integer.class, code) > 0
        );
        db.jdbc.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES(?,?,?)",
            code,
            userId,
            LocalDateTime.now().plus(CODE_TTL)
        );
        return Map.of("code", code, "minutes", CODE_TTL.toMinutes());
    }

    @Transactional
    public void unbindMine() {
        String userId = ownership.userId();
        if (db.jdbc.update("DELETE FROM bot_binding WHERE channel=? AND user_id=?", WECOM, userId) > 0) db.audit(
            "BOT_UNBOUND",
            userId,
            "解除自己的企业微信绑定"
        );
    }

    // ===================== 网页上：管理员 =====================

    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list() {
        return db.jdbc.queryForList(
            """
            SELECT b.id,b.channel,b.created_at,b.last_seen_at,u.username,u.display_name,u.role,u.enabled
            FROM bot_binding b JOIN app_user u ON u.id=b.user_id ORDER BY b.created_at
            """
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void unbind(String id) {
        var row = db.one(
            "SELECT b.user_id,u.username FROM bot_binding b JOIN app_user u ON u.id=b.user_id WHERE b.id=?",
            id
        );
        db.jdbc.update("DELETE FROM bot_binding WHERE id=?", id);
        db.audit("BOT_UNBOUND", row.get("user_id").toString(), "管理员解除 " + row.get("username") + " 的企业微信绑定");
    }

    // ===================== 机器人侧 =====================

    /** 这个人绑定的系统账号；没绑定或账号已停用时为空。 */
    public Optional<BoundUser> resolve(String channel, String externalId) {
        var rows = db.jdbc.queryForList(
            """
            SELECT b.id AS binding_id,u.id,u.username,u.display_name,u.role
            FROM bot_binding b JOIN app_user u ON u.id=b.user_id
            WHERE b.channel=? AND b.external_id=? AND u.enabled=TRUE
            """,
            channel,
            externalId
        );
        if (rows.isEmpty()) return Optional.empty();
        var r = rows.get(0);
        db.jdbc.update("UPDATE bot_binding SET last_seen_at=? WHERE id=?", LocalDateTime.now(), r.get("binding_id"));
        return Optional.of(
            new BoundUser(
                r.get("id").toString(),
                r.get("username").toString(),
                r.get("display_name").toString(),
                r.get("role").toString()
            )
        );
    }

    /** 用绑定码把聊天软件里的这个人绑到系统账号上。绑定码用一次就作废；这个账号或这个人原来的绑定被替换。 */
    @Transactional
    public BoundUser bind(String channel, String externalId, String code) {
        String who = channel + "|" + externalId;
        var lock = wrongCodes.get(who);
        if (lock != null && lock.wrong() >= MAX_WRONG_CODES) {
            if (Instant.now().isBefore(lock.until())) throw new ApiException("绑定码连续输错太多次，请 10 分钟后再试");
            wrongCodes.remove(who);
        }
        var rows = db.jdbc.queryForList(
            """
            SELECT c.user_id,u.username,u.display_name,u.role FROM bot_bind_code c JOIN app_user u ON u.id=c.user_id
            WHERE c.code=? AND c.expires_at>=? AND u.enabled=TRUE
            """,
            code,
            LocalDateTime.now()
        );
        if (rows.isEmpty()) {
            wrongCodes.merge(who, new Lock(1, Instant.now().plus(CODE_TTL)), (a, b) ->
                new Lock(a.wrong() + 1, b.until())
            );
            throw new ApiException("绑定码不对或已过期。请在系统的“系统设置 → 账户安全”里重新生成");
        }
        wrongCodes.remove(who);
        var r = rows.get(0);
        String userId = r.get("user_id").toString();
        db.jdbc.update("DELETE FROM bot_bind_code WHERE code=?", code);
        db.jdbc.update(
            "DELETE FROM bot_binding WHERE channel=? AND (external_id=? OR user_id=?)",
            channel,
            externalId,
            userId
        );
        db.jdbc.update(
            "INSERT INTO bot_binding(id,channel,external_id,user_id) VALUES(?,?,?,?)",
            db.id(),
            channel,
            externalId,
            userId
        );
        db.jdbc.update(
            "INSERT INTO audit_log(id,actor,action,entity_id,detail) VALUES(?,?,?,?,?)",
            db.id(),
            r.get("username"),
            "BOT_BOUND",
            userId,
            "绑定企业微信机器人"
        );
        return new BoundUser(
            userId,
            r.get("username").toString(),
            r.get("display_name").toString(),
            r.get("role").toString()
        );
    }

    /** 绑定码被发到了群里（别人都看得到）：立即作废。 */
    @Transactional
    public void revokeCode(String code) {
        db.jdbc.update("DELETE FROM bot_bind_code WHERE code=?", code);
    }

    /** 这个人自己在聊天里要求解绑。 */
    @Transactional
    public void unbindExternal(String channel, String externalId) {
        db.jdbc.update("DELETE FROM bot_binding WHERE channel=? AND external_id=?", channel, externalId);
    }
}
