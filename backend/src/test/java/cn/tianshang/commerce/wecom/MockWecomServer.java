package cn.tianshang.commerce.wecom;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 测试用的假“企业微信服务端”：只实现 WebSocket 里用得到的那一小部分（握手、文本帧、分片、关闭），
 * 让长连接的代码在真实的网络套接字上跑一遍。只监听本机回环地址。
 */
final class MockWecomServer implements Closeable {

    /** 一条已经握手完成的连接。 */
    final class Peer {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        String next() throws InterruptedException {
            String s = received.poll(10, TimeUnit.SECONDS);
            if (s == null) throw new AssertionError("10 秒内没有收到客户端发来的内容");
            return s;
        }

        /** 等到一条包含 marker 的帧（跳过心跳等其他帧）。 */
        String nextContaining(String marker) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                String s = next();
                if (s.contains(marker)) return s;
            }
            throw new AssertionError("没有等到包含 " + marker + " 的帧");
        }

        void send(String text) throws IOException {
            frame(0x81, text.getBytes(StandardCharsets.UTF_8));
        }

        /** 分成两片发送，验证客户端会把分片拼起来。 */
        void sendFragmented(String text) throws IOException {
            byte[] all = text.getBytes(StandardCharsets.UTF_8);
            int cut = all.length / 2;
            frame(0x01, java.util.Arrays.copyOfRange(all, 0, cut));
            frame(0x80, java.util.Arrays.copyOfRange(all, cut, all.length));
        }

        private synchronized void frame(int head, byte[] payload) throws IOException {
            var b = new ByteArrayOutputStream();
            b.write(head);
            if (payload.length < 126) b.write(payload.length);
            else {
                b.write(126);
                b.write(payload.length >> 8);
                b.write(payload.length & 0xff);
            }
            b.write(payload);
            out.write(b.toByteArray());
            out.flush();
        }

        /** 不打招呼直接断开，模拟网络中断。 */
        void drop() throws IOException {
            socket.close();
        }

        private void readLoop() {
            try {
                var message = new ByteArrayOutputStream();
                while (true) {
                    int b0 = in.read(),
                        b1 = in.read();
                    if (b0 < 0 || b1 < 0) return;
                    int opcode = b0 & 0x0f;
                    long length = b1 & 0x7f;
                    if (length == 126) length = (in.read() << 8) | in.read();
                    else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) length = (length << 8) | in.read();
                    }
                    byte[] mask = in.readNBytes(4);
                    byte[] payload = in.readNBytes((int) length);
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                    if (opcode == 8) return;
                    if (opcode == 9) {
                        frame(0x8a, payload);
                        continue;
                    }
                    if (opcode == 0 || opcode == 1) {
                        message.write(payload);
                        if ((b0 & 0x80) != 0) {
                            received.add(message.toString(StandardCharsets.UTF_8));
                            message.reset();
                        }
                    }
                }
            } catch (IOException ignored) {
                // 连接被关闭
            }
        }
    }

    private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
    private final BlockingQueue<Peer> peers = new LinkedBlockingQueue<>();

    MockWecomServer() throws IOException {
        Thread t = new Thread(this::acceptLoop, "mock-wecom");
        t.setDaemon(true);
        t.start();
    }

    String url() {
        return "ws://127.0.0.1:" + server.getLocalPort();
    }

    /** 等下一条客户端连接。 */
    Peer accept() throws InterruptedException {
        Peer p = peers.poll(15, TimeUnit.SECONDS);
        if (p == null) throw new AssertionError("15 秒内没有客户端连进来");
        return p;
    }

    /** 在给定时间内有没有新的连接进来。 */
    boolean anyWithin(long millis) throws InterruptedException {
        return peers.poll(millis, TimeUnit.MILLISECONDS) != null;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                var peer = new Peer(socket);
                String key = null;
                var line = new StringBuilder();
                // 逐字节读请求头，直到空行
                while (true) {
                    int c = peer.in.read();
                    if (c < 0) break;
                    if (c == '\n') {
                        String l = line.toString().trim();
                        line.setLength(0);
                        if (l.isEmpty()) break;
                        if (l.toLowerCase().startsWith("sec-websocket-key:")) key = l.substring(18).trim();
                    } else line.append((char) c);
                }
                if (key == null) {
                    socket.close();
                    continue;
                }
                String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)
                    )
                );
                peer.out.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
                        accept +
                        "\r\n\r\n"
                    ).getBytes(StandardCharsets.US_ASCII)
                );
                peer.out.flush();
                Thread t = new Thread(peer::readLoop, "mock-wecom-peer");
                t.setDaemon(true);
                t.start();
                peers.add(peer);
            } catch (Exception e) {
                if (server.isClosed()) return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
