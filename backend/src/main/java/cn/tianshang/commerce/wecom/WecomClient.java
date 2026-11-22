package cn.tianshang.commerce.wecom;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.bot.BotMessage;
import cn.tianshang.commerce.bot.BotService;
import cn.tianshang.commerce.common.Database;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 企业微信机器人的运行管理：系统启动后按保存的配置建立长连接；管理员改了配置或点“重新连接”时重建。
 * 没有配置或没有启用时什么都不做。
 */
@Service
public class WecomClient {

    private static final Logger log = LoggerFactory.getLogger(WecomClient.class);

    private final Database db;
    private final BotService bot;
    private final URI uri;
    private final WecomMedia media = new WecomMedia();
    private WecomConnection connection;

    public WecomClient(
        Database db,
        BotService bot,
        @Value("${app.wecom.url:wss://openws.work.weixin.qq.com}") String url
    ) {
        this.db = db;
        this.bot = bot;
        this.uri = URI.create(url);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            reload();
        } catch (RuntimeException e) {
            // 机器人起不来不能影响系统本身
            log.warn("企业微信机器人没有启动", e);
        }
    }

    /** 按当前保存的配置重建连接。 */
    public synchronized void reload() {
        stop();
        var rows = db.jdbc.queryForList(
            "SELECT bot_id,secret,enabled FROM bot_config WHERE channel=?",
            BotBindingService.WECOM
        );
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("enabled"))) return;
        String botId = db.text(rows.get(0), "bot_id"),
            secret = db.text(rows.get(0), "secret");
        if (botId.isBlank() || secret.isBlank()) return;
        connection = new WecomConnection(
            uri,
            botId,
            secret,
            new WecomSession.Handler() {
                @Override
                public String onMessage(BotMessage message) {
                    return bot.handle(message);
                }

                @Override
                public String onWelcome(String externalId) {
                    return bot.welcome(BotBindingService.WECOM, externalId);
                }
            },
            media::fetch,
            Duration.ofSeconds(30)
        );
        connection.start();
        log.info("企业微信机器人：开始连接");
    }

    @PreDestroy
    public synchronized void stop() {
        if (connection != null) connection.close();
        connection = null;
    }

    private static LocalDateTime local(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneId.systemDefault());
    }

    /** 主动给企业微信里的某人发消息；机器人没连上时返回 false。 */
    public synchronized boolean push(String userId, String markdown) {
        return connection != null && connection.push(userId, markdown);
    }

    /** 连接状态：DISABLED 未启用；CONNECTING / CONNECTED / RETRYING / AUTH_FAILED / KICKED。 */
    public synchronized Map<String, Object> status() {
        var out = new LinkedHashMap<String, Object>();
        if (connection == null) {
            out.put("state", "DISABLED");
            return out;
        }
        var s = connection.status();
        out.put("state", s.state());
        out.put("detail", s.detail());
        out.put("since", local(s.since()));
        out.put("lastMessageAt", local(s.lastMessageAt()));
        return out;
    }
}
