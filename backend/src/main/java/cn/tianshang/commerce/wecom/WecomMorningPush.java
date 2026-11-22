package cn.tianshang.commerce.wecom;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.bot.BotService;
import cn.tianshang.commerce.common.Database;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天早上把“今日待办”推给绑定了企业微信的人。管理员在“系统管理 → 企业微信机器人”里开关、设时间。
 *
 * <p>每人按自己绑定的系统账号计算，今天没有待办的不推。一天只推一次；到点时机器人没连上就跳过当天，不补推。
 */
@Component
public class WecomMorningPush {

    private static final Logger log = LoggerFactory.getLogger(WecomMorningPush.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final Database db;
    private final BotService bot;
    private final WecomClient client;
    private LocalDate lastPushed;

    public WecomMorningPush(Database db, BotService bot, WecomClient client) {
        this.db = db;
        this.bot = bot;
        this.client = client;
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        try {
            run(LocalDate.now(), LocalTime.now().format(HHMM));
        } catch (RuntimeException e) {
            log.warn("企业微信：推送今日待办出错", e);
        }
    }

    /** 到了设定的时间就推一次。返回推送成功的人数；没到点、已推过或没启用时返回 -1。 */
    public synchronized int run(LocalDate today, String now) {
        var rows = db.jdbc.queryForList(
            "SELECT enabled,push_enabled,push_time FROM bot_config WHERE channel=?",
            BotBindingService.WECOM
        );
        if (rows.isEmpty()) return -1;
        var c = rows.get(0);
        if (!Boolean.TRUE.equals(c.get("enabled")) || !Boolean.TRUE.equals(c.get("push_enabled"))) return -1;
        if (!now.equals(db.text(c, "push_time")) || today.equals(lastPushed)) return -1;
        lastPushed = today;
        int sent = 0;
        for (String externalId : db.jdbc.queryForList(
            "SELECT b.external_id FROM bot_binding b JOIN app_user u ON u.id=b.user_id WHERE b.channel=? AND u.enabled=TRUE",
            String.class,
            BotBindingService.WECOM
        )) {
            String text = bot.morningDigest(BotBindingService.WECOM, externalId);
            if (text != null && client.push(externalId, text)) sent++;
        }
        log.info("企业微信：今日待办已推送给 {} 人", sent);
        return sent;
    }
}
