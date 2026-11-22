package cn.tianshang.commerce.wecom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 到企业微信的一条长连接：由本机主动连出去（不需要公网地址），断了自动重连。
 *
 * <p>认证被拒绝、或被别处的新连接顶下线时不再重连：前者重试没有意义（而且认证请求有频率保护），
 * 后者重连只会和另一处互相顶。这两种情况在系统管理里显示原因，由管理员处理后点“重新连接”。
 */
final class WecomConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WecomConnection.class);
    private static final long MAX_BACKOFF_MS = 30_000;

    record Status(String state, String detail, Instant since, Instant lastMessageAt) {}

    private final URI uri;
    private final String botId;
    private final String secret;
    private final WecomSession.Handler handler;
    private final WecomSession.Media media;
    private final Duration heartbeat;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService net = Executors.newSingleThreadScheduledExecutor(daemon("wecom-net"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("wecom-bot"));
    private final Object sendLock = new Object();

    /** 每建立一次连接加一；旧连接上迟到的回调据此忽略。 */
    private int generation;
    private int attempts;
    private boolean closed;
    private WebSocket socket;
    private WecomSession session;
    private String state = "CONNECTING";
    private String detail;
    private Instant since = Instant.now();

    WecomConnection(
        URI uri,
        String botId,
        String secret,
        WecomSession.Handler handler,
        WecomSession.Media media,
        Duration heartbeat
    ) {
        this.uri = uri;
        this.botId = botId;
        this.secret = secret;
        this.handler = handler;
        this.media = media;
        this.heartbeat = heartbeat;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    void start() {
        net.execute(this::connect);
        net.scheduleWithFixedDelay(this::beat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized Status status() {
        return new Status(state, detail, since, session == null ? null : session.lastMessageAt());
    }

    private synchronized void set(String state, String detail) {
        if (!state.equals(this.state)) since = Instant.now();
        this.state = state;
        this.detail = detail;
    }

    private void connect() {
        final int gen;
        final WecomSession s;
        synchronized (this) {
            if (closed) return;
            gen = ++generation;
            socket = null;
            s = new WecomSession(botId, secret, json -> send(gen, json), handler, media, worker);
            session = s;
            if (attempts == 0) set("CONNECTING", null);
        }
        http
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(uri, new Listener(gen, s))
            .whenComplete((ws, error) -> {
                if (error != null) lost(gen, "连不上企业微信，请检查这台电脑的网络");
            });
    }

    private void beat() {
        final int gen;
        final WecomSession s;
        final WebSocket ws;
        synchronized (this) {
            gen = generation;
            s = session;
            ws = socket;
        }
        if (s == null || ws == null) return;
        try {
            if (!s.heartbeat()) {
                ws.abort();
                lost(gen, "企业微信没有应答心跳");
            }
        } catch (RuntimeException e) {
            log.warn("企业微信：心跳出错", e);
        }
    }

    private void send(int gen, String json) {
        final WebSocket ws;
        synchronized (this) {
            ws = gen == generation ? socket : null;
        }
        if (ws == null) return;
        try {
            // WebSocket 不允许同时发两帧：排队，一帧发完再发下一帧
            synchronized (sendLock) {
                ws.sendText(json, true).get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("企业微信：发送失败（{}）", e.getClass().getSimpleName());
            ws.abort();
            lost(gen, "和企业微信的连接断了");
        }
    }

    /** 连接断了：按 1、2、4…30 秒的间隔重连。 */
    private void lost(int gen, String reason) {
        synchronized (this) {
            if (closed || gen != generation) return;
            generation++; // 这一条连接作废，后面再有回调都忽略
            socket = null;
            var s = session == null ? null : session.state();
            if (s == WecomSession.State.AUTH_FAILED || s == WecomSession.State.KICKED) return;
            long delay = Math.min(1000L << Math.min(attempts, 5), MAX_BACKOFF_MS);
            attempts++;
            set("RETRYING", reason);
            net.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** 每收到一帧之后看一眼会话状态。 */
    private void afterFrame(int gen, WecomSession s, WebSocket ws) {
        synchronized (this) {
            if (closed || gen != generation) return;
            switch (s.state()) {
                case READY -> {
                    attempts = 0;
                    set("CONNECTED", null);
                }
                case AUTH_FAILED -> set("AUTH_FAILED", s.failure());
                case KICKED -> set("KICKED", s.failure());
                default -> {
                    return;
                }
            }
            if (s.state() == WecomSession.State.READY) return;
            generation++;
            socket = null;
        }
        ws.abort();
    }

    @Override
    public void close() {
        final WebSocket ws;
        synchronized (this) {
            closed = true;
            generation++;
            ws = socket;
            socket = null;
        }
        if (ws != null) ws.abort();
        net.shutdownNow();
        worker.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {

        private final int gen;
        private final WecomSession s;
        private final StringBuilder buffer = new StringBuilder();

        Listener(int gen, WecomSession s) {
            this.gen = gen;
            this.s = s;
        }

        @Override
        public void onOpen(WebSocket ws) {
            synchronized (WecomConnection.this) {
                if (closed || gen != generation) {
                    ws.abort();
                    return;
                }
                socket = ws;
            }
            ws.request(1);
            // 不在回调线程里发送
            net.execute(s::open);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                try {
                    s.receive(json);
                } catch (RuntimeException e) {
                    log.warn("企业微信：处理收到的内容出错", e);
                }
                afterFrame(gen, s, ws);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            lost(gen, "企业微信关闭了连接");
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            lost(gen, "和企业微信的连接断了");
        }
    }
}
