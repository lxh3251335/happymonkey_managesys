package cn.tianshang.commerce.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件存储：合同附件、产品图纸共用。
 *
 * <p>文件按服务端生成的 ID 存在附件目录（年/月/ID），不使用客户端文件名作路径；扩展名白名单并校验文件头；
 * 写临时文件后原子移动；数据库事务回滚时删除已落盘的文件。
 */
@Component
public class FileStore {

    /** 扩展名 → 下载时的 Content-Type。 */
    public static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("pdf", "application/pdf"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("png", "image/png"),
        Map.entry("doc", "application/msword"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("ofd", "application/ofd"),
        Map.entry("dwg", "image/vnd.dwg"),
        Map.entry("dxf", "image/vnd.dxf")
    );

    public record Saved(String fileName, String extension, long size, String sha256, String relativePath) {}

    private final Path root;

    public FileStore(@Value("${app.attachment-dir:./data/attachments}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * 校验并保存上传文件。duplicate 收到文件的 SHA-256，返回 true 表示重复（拒绝保存）。
     *
     * @param allowed 允许的扩展名
     * @param typeHint 类型不符时的提示，如“仅支持 PDF、图片”
     */
    public Saved save(
        String id,
        MultipartFile file,
        Set<String> allowed,
        long maxBytes,
        String typeHint,
        Predicate<String> duplicate
    ) {
        if (file == null || file.isEmpty()) throw new ApiException("请选择文件");
        if (file.getSize() > maxBytes) throw new ApiException("单个文件不能超过 " + (maxBytes / 1024 / 1024) + " MB");
        String name = cleanName(file.getOriginalFilename());
        String ext = extension(name);
        if (!allowed.contains(ext)) throw new ApiException(typeHint);

        var today = LocalDate.now();
        String relative = today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/" + id;
        Path target = resolve(relative);
        String sha;
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), id, ".part");
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] head = new byte[16];
                int headLen;
                try (
                    InputStream in = new DigestInputStream(file.getInputStream(), digest);
                    OutputStream out = Files.newOutputStream(temp)
                ) {
                    headLen = in.readNBytes(head, 0, head.length);
                    out.write(head, 0, headLen);
                    in.transferTo(out);
                }
                if (!signatureMatches(ext, Arrays.copyOf(head, headLen))) throw new ApiException(
                    "文件内容与扩展名不符，请确认文件未损坏"
                );
                sha = HexFormat.of().formatHex(digest.digest());
                if (duplicate.test(sha)) throw new ApiException("该文件已上传过");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("文件保存失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (
            TransactionSynchronizationManager.isSynchronizationActive()
        ) TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) try {
                        Files.deleteIfExists(target);
                    } catch (IOException ignored) {
                        // 清理失败不影响业务结果
                    }
                }
            }
        );
        return new Saved(name, ext, file.getSize(), sha, relative);
    }

    /** 已保存文件的绝对路径；越界或文件缺失时报错。 */
    public Path existing(String relative) {
        Path p = resolve(relative);
        if (!Files.isRegularFile(p)) throw new ApiException("文件缺失，请联系管理员");
        return p;
    }

    private Path resolve(String relative) {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IllegalStateException("文件路径越界");
        return p;
    }

    public static String contentType(String ext) {
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    public static String cleanName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").trim();
        if (n.isEmpty() || n.startsWith(".")) throw new ApiException("文件名不正确");
        if (n.length() > 200) {
            String ext = extension(n);
            n = n.substring(0, 190) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 文件头校验，防止把可执行内容改扩展名上传。 */
    static boolean signatureMatches(String ext, byte[] h) {
        return switch (ext) {
            case "pdf" -> starts(h, 0x25, 0x50, 0x44, 0x46); // %PDF
            case "png" -> starts(h, 0x89, 0x50, 0x4E, 0x47);
            case "jpg", "jpeg" -> starts(h, 0xFF, 0xD8, 0xFF);
            case "docx", "xlsx", "ofd" -> starts(h, 0x50, 0x4B, 0x03, 0x04); // ZIP
            case "doc", "xls" -> starts(h, 0xD0, 0xCF, 0x11, 0xE0); // OLE2
            case "dwg" -> starts(h, 0x41, 0x43, 0x31, 0x30); // AC10xx
            case "dxf" -> {
                // 文本 DXF 以组码 0（或注释 999）开头；二进制 DXF 以 “AutoCAD Binary DXF” 开头
                String t = new String(h, StandardCharsets.ISO_8859_1).stripLeading();
                yield t.startsWith("0") || t.startsWith("999") || t.startsWith("AutoCAD");
            }
            default -> false;
        };
    }

    private static boolean starts(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((h[i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
