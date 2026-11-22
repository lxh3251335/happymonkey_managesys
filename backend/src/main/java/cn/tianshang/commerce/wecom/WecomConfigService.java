package cn.tianshang.commerce.wecom;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.common.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** 企业微信机器人的连接配置（Bot ID、Secret、是否启用）。只有系统管理员能看和改；Secret 只能写入，不会再显示。 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class WecomConfigService {

    private final Database db;
    private final WecomClient client;

    public WecomConfigService(Database db, WecomClient client) {
        this.db = db;
        this.client = client;
    }

    public Map<String, Object> get() {
        var rows = db.jdbc.queryForList(
            "SELECT bot_id,secret,enabled,push_enabled,push_time,updated_by,updated_at FROM bot_config WHERE channel=?",
            BotBindingService.WECOM
        );
        var out = new LinkedHashMap<String, Object>();
        var row = rows.isEmpty() ? Map.<String, Object>of() : rows.get(0);
        out.put("botId", db.text(row, "bot_id"));
        out.put("secretSet", !db.text(row, "secret").isBlank());
        out.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
        out.put("pushEnabled", Boolean.TRUE.equals(row.get("push_enabled")));
        out.put("pushTime", row.get("push_time") == null ? "09:00" : row.get("push_time"));
        out.put("updatedBy", row.get("updated_by"));
        out.put("updatedAt", row.get("updated_at"));
        out.put("status", client.status());
        return out;
    }

    public record ConfigInput(
        @Size(max = 120) @Pattern(
            regexp = "^[0-9A-Za-z_\\-]*$",
            message = "Bot ID 只包含字母、数字、下划线和短横线"
        ) String botId,
        /** 留空表示不修改已保存的 Secret。 */
        @Size(max = 200) String secret,
        boolean enabled,
        /** 每天推送今日待办。 */
        boolean pushEnabled,
        @Pattern(regexp = "^$|([01]\\d|2[0-3]):[0-5]\\d", message = "推送时间格式为 HH:mm") String pushTime
    ) {}

    /** 保存并立即按新配置重连。 */
    public Map<String, Object> save(ConfigInput in) {
        var rows = db.jdbc.queryForList("SELECT secret FROM bot_config WHERE channel=?", BotBindingService.WECOM);
        String botId = in.botId() == null ? "" : in.botId().trim();
        String secret =
            in.secret() == null || in.secret().isBlank()
                ? rows.isEmpty()
                    ? ""
                    : db.text(rows.get(0), "secret")
                : in.secret().trim();
        String pushTime = in.pushTime() == null || in.pushTime().isBlank() ? "09:00" : in.pushTime();
        if (in.enabled() && (botId.isEmpty() || secret.isEmpty())) throw new ApiException(
            "启用前请先填写 Bot ID 和 Secret"
        );
        if (rows.isEmpty()) db.jdbc.update(
            "INSERT INTO bot_config(channel,bot_id,secret,enabled,push_enabled,push_time,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            BotBindingService.WECOM,
            botId,
            secret,
            in.enabled(),
            in.pushEnabled(),
            pushTime,
            Ownership.username(),
            LocalDateTime.now()
        );
        else db.jdbc.update(
            "UPDATE bot_config SET bot_id=?,secret=?,enabled=?,push_enabled=?,push_time=?,updated_by=?,updated_at=? WHERE channel=?",
            botId,
            secret,
            in.enabled(),
            in.pushEnabled(),
            pushTime,
            Ownership.username(),
            LocalDateTime.now(),
            BotBindingService.WECOM
        );
        // 日志里不写 Secret
        db.audit(
            "BOT_CONFIG_UPDATED",
            BotBindingService.WECOM,
            (in.enabled() ? "启用" : "停用") +
                "企业微信机器人，Bot ID " +
                (botId.isEmpty() ? "未填" : botId) +
                (in.secret() == null || in.secret().isBlank() ? "" : "，更换了 Secret") +
                (in.pushEnabled() ? "，每天 " + pushTime + " 推送今日待办" : "，不推送今日待办")
        );
        client.reload();
        return get();
    }

    public Map<String, Object> reconnect() {
        client.reload();
        return get();
    }
}
