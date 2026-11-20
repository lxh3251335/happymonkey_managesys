package cn.tianshang.commerce.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.bot.BotMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 长连接在真实的网络套接字上跑一遍：对面是本机的假服务端。 */
class WecomConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final WecomSession.Handler ECHO = new WecomSession.Handler() {
        @Override
        public String onMessage(BotMessage message) {
            return "收到：" + message.text();
        }

        @Override
        public String onWelcome(String externalId) {
            return "你好";
        }
    };

    private static WecomConnection connect(MockWecomServer server) {
        var c = new WecomConnection(
            URI.create(server.url()),
            "bot-1",
            "secret-1",
            ECHO,
            (url, key) -> new WecomMedia.Downloaded("x", new byte[0]),
            Duration.ofMillis(200)
        );
        c.start();
        return c;
    }

    private static void await(WecomConnection c, String state) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (state.equals(c.status().state())) return;
            Thread.sleep(50);
        }
        throw new AssertionError("状态一直是 " + c.status().state() + "，没有变成 " + state);
    }

    private static String ack(String frame) throws Exception {
        return (
            "{\"headers\":{\"req_id\":\"" +
            MAPPER.readTree(frame).path("headers").path("req_id").asText() +
            "\"},\"errcode\":0,\"errmsg\":\"ok\"}"
        );
    }

    /** 接受一条连接并让它通过认证。 */
    private static MockWecomServer.Peer subscribed(MockWecomServer server) throws Exception {
        var peer = server.accept();
        String subscribe = peer.next();
        JsonNode frame = MAPPER.readTree(subscribe);
        assertThat(frame.path("cmd").asText()).isEqualTo("aibot_subscribe");
        assertThat(frame.path("body").path("bot_id").asText()).isEqualTo("bot-1");
        assertThat(frame.path("body").path("secret").asText()).isEqualTo("secret-1");
        peer.send(ack(subscribe));
        return peer;
    }

    @Test
    void subscribesRepliesKeepsAliveAndReconnectsAfterADrop() throws Exception {
        try (var server = new MockWecomServer(); var c = connect(server)) {
            var peer = subscribed(server);
            await(c, "CONNECTED");

            // 消息分两片到达，回复带回同一个 req_id
            peer.sendFragmented(
                """
                {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-9"},
                 "body":{"msgid":"M9","chattype":"single","from":{"userid":"zhangsan"},
                         "msgtype":"text","text":{"content":"你好，机器人"}}}"""
            );
            JsonNode reply = MAPPER.readTree(peer.nextContaining("aibot_respond_msg"));
            assertThat(reply.path("headers").path("req_id").asText()).isEqualTo("REQ-9");
            assertThat(reply.path("body").path("stream").path("content").asText()).isEqualTo("收到：你好，机器人");
            assertThat(c.status().lastMessageAt()).isNotNull();

            // 定时心跳
            String ping = peer.nextContaining("\"ping\"");
            peer.send(ack(ping));

            // 网络中断：自动重连并重新认证
            peer.drop();
            var again = subscribed(server);
            await(c, "CONNECTED");
            again.send(
                """
                {"cmd":"aibot_msg_callback","headers":{"req_id":"REQ-10"},
                 "body":{"msgid":"M10","chattype":"single","from":{"userid":"zhangsan"},
                         "msgtype":"text","text":{"content":"还在吗"}}}"""
            );
            assertThat(again.nextContaining("aibot_respond_msg")).contains("REQ-10").contains("收到：还在吗");

            // 被别处的新连接顶下线：记下原因，不再重连（否则两处会互相顶）
            again.send(
                """
                {"cmd":"aibot_event_callback","headers":{"req_id":"EV-9"},
                 "body":{"msgtype":"event","event":{"eventtype":"disconnected_event"}}}"""
            );
            await(c, "KICKED");
            assertThat(c.status().detail()).contains("别处建立了新连接");
            assertThat(server.anyWithin(1500)).isFalse();
        }
    }

    @Test
    void unansweredHeartbeatsTriggerAReconnect() throws Exception {
        try (var server = new MockWecomServer(); var c = connect(server)) {
            subscribed(server);
            await(c, "CONNECTED");
            // 这条连接从此不应答心跳：客户端应当主动断开并重新连进来
            subscribed(server);
            await(c, "CONNECTED");
        }
    }

    @Test
    void rejectedCredentialsAreNotRetried() throws Exception {
        try (var server = new MockWecomServer(); var c = connect(server)) {
            var peer = server.accept();
            String subscribe = peer.next();
            peer.send(
                "{\"headers\":{\"req_id\":\"" +
                    MAPPER.readTree(subscribe).path("headers").path("req_id").asText() +
                    "\"},\"errcode\":40001,\"errmsg\":\"invalid secret\"}"
            );
            await(c, "AUTH_FAILED");
            assertThat(c.status().detail()).contains("Bot ID 和 Secret");
            assertThat(server.anyWithin(1500)).isFalse();
        }
    }

    @Test
    void unreachableServerKeepsRetryingWithoutCrashing() throws Exception {
        String url;
        try (var server = new MockWecomServer()) {
            url = server.url();
        }
        // 端口已经关了：连不上
        try (
            var c = new WecomConnection(
                URI.create(url),
                "bot-1",
                "secret-1",
                ECHO,
                (u, k) -> new WecomMedia.Downloaded("x", new byte[0]),
                Duration.ofMillis(200)
            )
        ) {
            c.start();
            await(c, "RETRYING");
            assertThat(c.status().detail()).contains("连不上企业微信");
        }
    }
}
