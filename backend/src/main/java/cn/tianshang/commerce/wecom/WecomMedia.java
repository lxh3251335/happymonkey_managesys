package cn.tianshang.commerce.wecom;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 企业微信机器人收到的文件：消息里只有一个 5 分钟内有效的下载地址和这条消息专用的密钥，下载到的内容是加密的。
 *
 * <p>解密方式（与企业微信官方 Node 开发包一致）：AES-256-CBC，密钥是 aeskey 做 Base64 解码后的 32 字节，
 * 初始向量取密钥的前 16 字节，按 32 字节分块做 PKCS#7 填充。
 */
public class WecomMedia {

    /** 与网页上传发票的上限一致。 */
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Pattern NAME_UTF8 = Pattern.compile("filename\\*=UTF-8''([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PLAIN = Pattern.compile("filename=\"?([^\";\\s]+)\"?", Pattern.CASE_INSENSITIVE);

    public record Downloaded(String name, byte[] bytes) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public Downloaded fetch(String url, String aesKey) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("文件地址不是 https");
        var response = http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() != 200) throw new IllegalStateException(
            "下载文件失败：HTTP " + response.statusCode()
        );
        byte[] body;
        try (var in = response.body()) {
            body = in.readNBytes(MAX_BYTES + 64);
        }
        if (body.length > MAX_BYTES + 32) throw new IllegalStateException("文件超过 20 MB");
        String name = fileName(response.headers().firstValue("Content-Disposition").orElse(""));
        return new Downloaded(name, aesKey == null || aesKey.isBlank() ? body : decrypt(body, aesKey));
    }

    static String fileName(String disposition) {
        Matcher m = NAME_UTF8.matcher(disposition);
        if (!m.find()) {
            m = NAME_PLAIN.matcher(disposition);
            if (!m.find()) return "";
        }
        try {
            return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return m.group(1);
        }
    }

    static byte[] decrypt(byte[] encrypted, String aesKey) throws Exception {
        byte[] key = Base64.getDecoder().decode(padBase64(aesKey));
        if (key.length != 32) throw new IllegalArgumentException("文件密钥长度不对");
        if (encrypted.length == 0 || encrypted.length % 16 != 0) throw new IllegalArgumentException("文件内容不完整");
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            new SecretKeySpec(key, "AES"),
            new IvParameterSpec(Arrays.copyOfRange(key, 0, 16))
        );
        byte[] plain = cipher.doFinal(encrypted);
        int pad = plain[plain.length - 1] & 0xff;
        if (pad < 1 || pad > 32 || pad > plain.length) throw new IllegalArgumentException("文件解密失败：填充不正确");
        for (int i = plain.length - pad; i < plain.length; i++) if (
            (plain[i] & 0xff) != pad
        ) throw new IllegalArgumentException("文件解密失败：填充不正确");
        return Arrays.copyOfRange(plain, 0, plain.length - pad);
    }

    /** 企业微信的密钥有时不带结尾的“=”。 */
    private static String padBase64(String s) {
        String t = s.trim();
        return t + "=".repeat((4 - (t.length() % 4)) % 4);
    }
}
