package cn.tianshang.commerce.wecom;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.bot.BotMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信“智能机器人”长连接上的一次会话：只管收发的内容（协议），不管网络。
 *
 * <p>协议要点（企业微信开发者文档“智能机器人长连接”，并对照官方 aibot-node-sdk 1.0.3）：
 * <ul>
 *   <li>所有帧都是 JSON：{@code {cmd, headers:{req_id}, body}}；对方的应答没有 cmd，带 errcode / errmsg；</li>
 *   <li>连上后先发 {@code aibot_subscribe}（bot_id + secret）认证；之后每 30 秒发一次 {@code ping}；</li>
 *   <li>用户消息是 {@code aibot_msg_callback}，回复用 {@code aibot_respond_msg} 并原样带回对方的 req_id；</li>
 *   <li>事件是 {@code aibot_event_callback}：进入会话（5 秒内可回欢迎语）、被新连接顶下线等。</li>
 * </ul>
 */
final class WecomSession {

    private static final Logger log = LoggerFactory.getLogger(WecomSession.class);
    /** 单条回复的上限是 20480 字节，留一点余量。 */
    static final int MAX_REPLY_BYTES = 20000;
    /** 连续这么多次心跳没有应答就认为连接已断。 */
    static final int MAX_MISSED_PONGS = 2;
    private static final String UNREADABLE =
        "图片、视频我暂时读不了。到账凭证请把银行短信或回单上的文字发给我，发票请发 PDF 文件。";

    enum State {
        SUBSCRIBING,
        READY,
        AUTH_FAILED,
        KICKED,
    }

    interface Out {
        void send(String json);
    }

    interface Handler {
        String onMessage(BotMessage message);

        String onWelcome(String externalId);
    }

    interface Media {
        WecomMedia.Downloaded fetch(String url, String aesKey) throws Exception;
    }

    private final String botId;
    private final String secret;
    private final Out out;
    private final Handler handler;
    private final Media media;
    private final Executor worker;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    /** 企业微信可能重发同一条消息，按 msgid 去重。 */
    private final Set<String> seen = Collections.newSetFromMap(
        new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 500;
            }
        }
    );

    private volatile State state = State.SUBSCRIBING;
    private volatile String failure;
    private volatile Instant lastMessageAt;
    private int missedPongs;

    WecomSession(String botId, String secret, Out out, Handler handler, Media media, Executor worker) {
        this.botId = botId;
        this.secret = secret;
        this.out = out;
        this.handler = handler;
        this.media = media;
        this.worker = worker;
    }

    State state() {
        return state;
    }

    String failure() {
        return failure;
    }

    Instant lastMessageAt() {
        return lastMessageAt;
    }

    /** 网络连上之后：认证。 */
    void open() {
        ObjectNode body = mapper.createObjectNode();
        body.put("bot_id", botId);
        body.put("secret", secret);
        send("aibot_subscribe", reqId("aibot_subscribe"), body);
    }

    /** 定时调用。返回 false 表示对方已经连续几次没有应答，应当断开重连。 */
    synchronized boolean heartbeat() {
        if (state != State.READY) return true;
        if (missedPongs >= MAX_MISSED_PONGS) return false;
        missedPongs++;
        send("ping", reqId("ping"), null);
        return true;
    }

    /** 收到一帧。 */
    void receive(String json) {
        JsonNode frame;
        try {
            frame = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("企业微信发来的内容不是 JSON，已忽略");
            return;
        }
        String cmd = frame.path("cmd").asText("");
        String reqId = frame.path("headers").path("req_id").asText("");
        if (cmd.isEmpty()) {
            ack(reqId, frame.path("errcode").asInt(0), frame.path("errmsg").asText(""));
            return;
        }
        JsonNode body = frame.path("body");
        switch (cmd) {
            case "aibot_msg_callback" -> worker.execute(() -> message(reqId, body));
            case "aibot_event_callback" -> event(reqId, body);
            default -> log.debug("企业微信：未处理的指令 {}", cmd);
        }
    }

    private synchronized void ack(String reqId, int errcode, String errmsg) {
        if (reqId.startsWith("aibot_subscribe")) {
            if (errcode == 0) {
                state = State.READY;
                failure = null;
                missedPongs = 0;
            } else {
                state = State.AUTH_FAILED;
                failure = "企业微信拒绝了认证（" + errcode + " " + errmsg + "），请核对 Bot ID 和 Secret";
            }
        } else if (reqId.startsWith("ping")) {
            if (errcode == 0) missedPongs = 0;
        } else if (errcode != 0) log.warn("企业微信没有接受这条回复：{} {}", errcode, errmsg);
    }

    private void event(String reqId, JsonNode body) {
        String type = body.path("event").path("eventtype").asText("");
        switch (type) {
            case "enter_chat" -> worker.execute(() -> {
                try {
                    String text = handler.onWelcome(body.path("from").path("userid").asText(""));
                    ObjectNode reply = mapper.createObjectNode();
                    reply.put("msgtype", "text");
                    reply.putObject("text").put("content", limit(text));
                    send("aibot_respond_welcome_msg", reqId, reply);
                } catch (RuntimeException e) {
                    log.warn("企业微信：欢迎语发送失败", e);
                }
            });
            case "disconnected_event" -> {
                state = State.KICKED;
                failure = "这个机器人在别处建立了新连接，本机已下线。确认只有一处在运行后，点“重新连接”";
            }
            default -> log.debug("企业微信：未处理的事件 {}", type);
        }
    }

    private void message(String reqId, JsonNode body) {
        String msgId = body.path("msgid").asText("");
        synchronized (seen) {
            if (!msgId.isEmpty() && !seen.add(msgId)) return;
        }
        lastMessageAt = Instant.now();
        String reply;
        try {
            reply = handle(body);
        } catch (Exception e) {
            log.warn("企业微信：处理消息失败", e);
            reply = "处理时出了问题，没有登记任何内容。请到网页上操作，或稍后再试。";
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("msgtype", "stream");
        ObjectNode stream = out.putObject("stream");
        stream.put("id", reqId("stream"));
        stream.put("finish", true);
        stream.put("content", limit(reply));
        send("aibot_respond_msg", reqId, out);
    }

    private String handle(JsonNode body) throws Exception {
        String from = body.path("from").path("userid").asText("");
        if (from.isEmpty()) return "没有识别出发消息的人。";
        boolean group = "group".equals(body.path("chattype").asText());
        String chatId = group ? body.path("chatid").asText(from) : from;
        String type = body.path("msgtype").asText("");
        String text = null,
            fileName = null;
        byte[] file = null;
        switch (type) {
            case "text" -> text = body.path("text").path("content").asText("");
            case "voice" -> text = body.path("voice").path("content").asText("");
            case "mixed" -> {
                var parts = new ArrayList<String>();
                for (JsonNode item : body.path("mixed").path("msg_item"))
                    if ("text".equals(item.path("msgtype").asText())) parts.add(
                        item.path("text").path("content").asText("")
                    );
                text = String.join("\n", parts).strip();
                if (text.isEmpty()) return UNREADABLE;
            }
            case "file" -> {
                var got = media.fetch(
                    body.path("file").path("url").asText(),
                    body.path("file").path("aeskey").asText("")
                );
                fileName = got.name();
                file = got.bytes();
            }
            default -> {
                return UNREADABLE;
            }
        }
        // 引用了别人的一段文字再 @ 机器人：把被引用的内容一起交给机器人
        String quoted = body.path("quote").path("text").path("content").asText("");
        if (text != null && !quoted.isBlank()) text = text.strip() + "\n" + quoted;
        return handler.onMessage(new BotMessage(BotBindingService.WECOM, from, chatId, group, text, fileName, file));
    }

    private void send(String cmd, String reqId, JsonNode body) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("cmd", cmd);
        frame.putObject("headers").put("req_id", reqId);
        if (body != null) frame.set("body", body);
        out.send(frame.toString());
    }

    private String reqId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + String.format("%08x", random.nextInt());
    }

    /** 回复不能超过 20480 字节：超出时截断并说明。 */
    static String limit(String text) {
        String t = text == null || text.isBlank() ? "（没有内容）" : text;
        if (t.getBytes(StandardCharsets.UTF_8).length <= MAX_REPLY_BYTES) return t;
        String tail = "\n……内容太长，后面的没有显示，请到网页上查看";
        int budget = MAX_REPLY_BYTES - tail.getBytes(StandardCharsets.UTF_8).length;
        var sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > budget) break;
            sb.appendCodePoint(cp);
            used += size;
            i += Character.charCount(cp);
        }
        return sb + tail;
    }
}
