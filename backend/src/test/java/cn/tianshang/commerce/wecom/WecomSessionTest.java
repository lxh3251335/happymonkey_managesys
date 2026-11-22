package cn.tianshang.commerce.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.tianshang.commerce.bot.BotMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 企业微信长连接的协议：认证、心跳、消息与事件的收发格式，以及收到的文件怎么解密。不涉及网络。 */
class WecomSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> sent = new ArrayList<>();
    private final List<BotMessage> handled = new ArrayList<>();
    private final WecomSession session = new WecomSession(
        "bot-1",
        "secret-1",
        json -> {
            try {
                sent.add(MAPPER.readTree(json));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        },
        new WecomSession.Handler() {
            @Override
            public String onMessage(BotMessage message) {
                handled.add(message);
                return "收到：" + (message.text() == null ? message.fileName() : message.text());
            }

            @Override
            public String onWelcome(String externalId) {
                return "你好 " + externalId;
            }
        },
        (url, key) -> new WecomMedia.Downloaded("发票.pdf", (url + "|" + key).getBytes(StandardCharsets.UTF_8)),
        Runnable::run
    );

    private JsonNode last() {
        return sent.get(sent.size() - 1);
    }

    private void ready() {
        session.open();
        session.receive(
            "{\"headers\":{\"req_id\":\"" +
                last().path("headers").path("req_id").asText() +
                "\"},\"errcode\":0,\"errmsg\":\"ok\"}"
        );
    }

    @Test
    void subscribesWithBotIdAndSecretAndBecomesReadyOnAck() {
        session.open();
        assertThat(last().path("cmd").asText()).isEqualTo("aibot_subscribe");
        assertThat(last().path("headers").path("req_id").asText()).startsWith("aibot_subscribe_");
        assertThat(last().path("body").path("bot_id").asText()).isEqualTo("bot-1");
        assertThat(last().path("body").path("secret").asText()).isEqualTo("secret-1");
        assertThat(session.state()).isEqualTo(WecomSession.State.SUBSCRIBING);
        // 认证通过之前不发心跳
        assertThat(session.heartbeat()).isTrue();
        assertThat(sent).hasSize(1);

        ready();
        assertThat(session.state()).isEqualTo(WecomSession.State.READY);
    }

    @Test
    void rejectedSubscribeIsReportedAsAuthFailure() {
        session.open();
        session.receive(
            "{\"headers\":{\"req_id\":\"aibot_subscribe_1_a\"},\"errcode\":40001,\"errmsg\":\"invalid secret\"}"
        );
        assertThat(session.state()).isEqualTo(WecomSession.State.AUTH_FAILED);
        assertThat(session.failure()).contains("40001").contains("Bot ID 和 Secret");
    }

    @Test
    void heartbeatGivesUpAfterTwoUnansweredPings() {
        ready();
        assertThat(session.heartbeat()).isTrue();
        assertThat(last().path("cmd").asText()).isEqualTo("ping");
        assertThat(last().has("body")).isFalse();
        // 有应答就一直正常
        session.receive(
            "{\"headers\":{\"req_id\":\"" +
                last().path("headers").path("req_id").asText() +
                "\"},\"errcode\":0,\"errmsg\":\"ok\"}"
        );
        assertThat(session.heartbeat()).isTrue();
        assertThat(session.heartbeat()).isTrue();
        // 连着两次没有应答：第三次判定连接已断
        assertThat(session.heartbeat()).isFalse();
    }

    @Test
    void textMessageIsAnsweredOnTheSameRequestIdAndDuplicatesAreIgnored() {
        ready();
        String frame = """
            {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-1"},
             "body":{"msgid":"M1","aibotid":"bot-1","chattype":"single","from":{"userid":"zhangsan"},
                     "msgtype":"text","text":{"content":"应收 中野"}}}""";
        session.receive(frame);
        assertThat(handled).hasSize(1);
        assertThat(handled.get(0).externalId()).isEqualTo("zhangsan");
        assertThat(handled.get(0).chatId()).isEqualTo("zhangsan");
        assertThat(handled.get(0).group()).isFalse();
        assertThat(handled.get(0).text()).isEqualTo("应收 中野");
        assertThat(last().path("cmd").asText()).isEqualTo("aibot_respond_msg");
        assertThat(last().path("headers").path("req_id").asText()).isEqualTo("REQ-1");
        assertThat(last().path("body").path("msgtype").asText()).isEqualTo("stream");
        assertThat(last().path("body").path("stream").path("finish").asBoolean()).isTrue();
        assertThat(last().path("body").path("stream").path("content").asText()).isEqualTo("收到：应收 中野");
        assertThat(session.lastMessageAt()).isNotNull();

        int before = sent.size();
        session.receive(frame);
        assertThat(handled).hasSize(1);
        assertThat(sent).hasSize(before);
    }

    @Test
    void groupMessagesCarryTheGroupChatAndQuotedText() {
        ready();
        session.receive(
            """
            {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-2"},
             "body":{"msgid":"M2","chatid":"GROUP-9","chattype":"group","from":{"userid":"lisi"},
                     "msgtype":"text","text":{"content":"@机器人 登记"},
                     "quote":{"msgtype":"text","text":{"content":"盛泰站 SF1234567890123"}}}}"""
        );
        assertThat(handled.get(0).group()).isTrue();
        assertThat(handled.get(0).chatId()).isEqualTo("GROUP-9");
        assertThat(handled.get(0).text()).isEqualTo("@机器人 登记\n盛泰站 SF1234567890123");
    }

    @Test
    void filesAreDownloadedAndImagesAreDeclined() {
        ready();
        session.receive(
            """
            {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-3"},
             "body":{"msgid":"M3","chattype":"single","from":{"userid":"wangwu"},
                     "msgtype":"file","file":{"url":"https://example.invalid/f","aeskey":"KEY"}}}"""
        );
        assertThat(handled.get(0).fileName()).isEqualTo("发票.pdf");
        assertThat(new String(handled.get(0).file(), StandardCharsets.UTF_8)).isEqualTo(
            "https://example.invalid/f|KEY"
        );

        session.receive(
            """
            {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-4"},
             "body":{"msgid":"M4","chattype":"single","from":{"userid":"wangwu"},
                     "msgtype":"image","image":{"url":"https://example.invalid/i","aeskey":"KEY"}}}"""
        );
        assertThat(handled).hasSize(1);
        assertThat(last().path("headers").path("req_id").asText()).isEqualTo("REQ-4");
        assertThat(last().path("body").path("stream").path("content").asText()).contains("暂时读不了");
    }

    @Test
    void enterChatGetsAWelcomeAndBeingKickedIsRecorded() {
        ready();
        session.receive(
            """
            {"cmd":"aibot_event_callback","headers":{"req_id":"EV-1"},
             "body":{"msgid":"E1","from":{"userid":"zhaoliu"},"msgtype":"event","event":{"eventtype":"enter_chat"}}}"""
        );
        assertThat(last().path("cmd").asText()).isEqualTo("aibot_respond_welcome_msg");
        assertThat(last().path("headers").path("req_id").asText()).isEqualTo("EV-1");
        assertThat(last().path("body").path("text").path("content").asText()).isEqualTo("你好 zhaoliu");

        session.receive(
            """
            {"cmd":"aibot_event_callback","headers":{"req_id":"EV-2"},
             "body":{"msgtype":"event","event":{"eventtype":"disconnected_event"}}}"""
        );
        assertThat(session.state()).isEqualTo(WecomSession.State.KICKED);
        assertThat(session.failure()).contains("别处建立了新连接");
    }

    @Test
    void proactivePushIsASingleChatMarkdownMessageAfterSubscribe() {
        assertThat(session.push("zhangsan", "今日待办")).isFalse();
        assertThat(sent).isEmpty();
        ready();
        assertThat(session.push("zhangsan", "**今日待办**")).isTrue();
        assertThat(last().path("cmd").asText()).isEqualTo("aibot_send_msg");
        assertThat(last().path("headers").path("req_id").asText()).startsWith("aibot_send_msg_");
        assertThat(last().path("body").path("chatid").asText()).isEqualTo("zhangsan");
        assertThat(last().path("body").path("chat_type").asInt()).isEqualTo(1);
        assertThat(last().path("body").path("msgtype").asText()).isEqualTo("markdown");
        assertThat(last().path("body").path("markdown").path("content").asText()).isEqualTo("**今日待办**");
    }

    @Test
    void longRepliesAreCutToTheByteLimit() {
        String cut = WecomSession.limit("应收".repeat(8000));
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(WecomSession.MAX_REPLY_BYTES);
        assertThat(cut).endsWith("请到网页上查看");
        assertThat(WecomSession.limit("短")).isEqualTo("短");
    }

    /** 按企业微信的方式加密：AES-256-CBC，IV 取密钥前 16 字节，按 32 字节分块做 PKCS#7 填充。 */
    private static byte[] encrypt(byte[] plain, byte[] key) throws Exception {
        int pad = 32 - (plain.length % 32);
        byte[] padded = Arrays.copyOf(plain, plain.length + pad);
        Arrays.fill(padded, plain.length, padded.length, (byte) pad);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(Arrays.copyOfRange(key, 0, 16))
        );
        return cipher.doFinal(padded);
    }

    @Test
    void receivedFilesAreDecryptedWithThePerMessageKey() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 3);
        // 企业微信给的密钥常常不带结尾的“=”
        String aesKey = Base64.getEncoder().withoutPadding().encodeToString(key);
        for (int size : new int[] { 1, 31, 32, 33, 1000 }) {
            byte[] plain = new byte[size];
            new Random(size).nextBytes(plain);
            assertThat(WecomMedia.decrypt(encrypt(plain, key), aesKey)).isEqualTo(plain);
        }
        byte[] other = key.clone();
        other[0] ^= 1;
        assertThatThrownBy(() ->
            WecomMedia.decrypt(encrypt(new byte[100], key), Base64.getEncoder().encodeToString(other))
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WecomMedia.decrypt(new byte[10], aesKey)).isInstanceOf(IllegalArgumentException.class);

        assertThat(WecomMedia.fileName("attachment; filename*=UTF-8''%E5%8F%91%E7%A5%A8.pdf")).isEqualTo("发票.pdf");
        assertThat(WecomMedia.fileName("attachment; filename=\"invoice.pdf\"")).isEqualTo("invoice.pdf");
        assertThat(WecomMedia.fileName("")).isEmpty();
    }
}
