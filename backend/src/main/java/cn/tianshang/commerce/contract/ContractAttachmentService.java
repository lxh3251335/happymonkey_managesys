package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 合同附件：合同扫描件、盖章报价单、送货签收单等确认凭据。
 *
 * <p>文件按服务端生成的 ID 存放在附件目录，不使用客户端文件名作路径；类型按扩展名白名单并校验文件头。删除为软删除，文件保留以备审计。
 */
@Service
public class ContractAttachmentService {

    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 扩展名 → 下载时的 Content-Type。 */
    static final Map<String, String> TYPES = Map.of(
        "pdf",
        "application/pdf",
        "jpg",
        "image/jpeg",
        "jpeg",
        "image/jpeg",
        "png",
        "image/png",
        "doc",
        "application/msword",
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls",
        "application/vnd.ms-excel",
        "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ofd",
        "application/ofd"
    );

    static final Map<String, String> CATEGORIES = Map.of(
        "CONTRACT",
        "合同",
        "STAMPED_QUOTE",
        "盖章报价单",
        "DELIVERY",
        "送货签收",
        "INVOICE",
        "发票",
        "OTHER",
        "其他"
    );

    private final Database db;
    private final ContractLedger ledger;
    private final Path root;

    public ContractAttachmentService(
        Database db,
        ContractLedger ledger,
        @Value("${app.attachment-dir:./data/attachments}") String dir
    ) {
        this.db = db;
        this.ledger = ledger;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public List<Map<String, Object>> list(String customerId, String contractKey) {
        return db.jdbc.queryForList(
            """
            SELECT id,customer_id,contract_no,category,file_name,extension,size_bytes,sha256,note,uploaded_by,created_at
            FROM contract_attachment WHERE customer_id=? AND contract_no=? AND deleted_at IS NULL ORDER BY created_at DESC
            """,
            customerId,
            contractKey
        );
    }

    /** 各合同有效附件数，键为 customerId::contractKey。 */
    public Map<String, Integer> counts() {
        var out = new HashMap<String, Integer>();
        for (var r : db.jdbc.queryForList(
            "SELECT customer_id,contract_no,COUNT(*) AS n FROM contract_attachment WHERE deleted_at IS NULL GROUP BY customer_id,contract_no"
        ))
            out.put(r.get("customer_id") + "::" + r.get("contract_no"), ((Number) r.get("n")).intValue());
        return out;
    }

    @PreAuthorize("hasAnyRole('ADMIN','SALES','FINANCE')")
    @Transactional
    public Map<String, Object> upload(
        String customerId,
        String contractKey,
        String category,
        String note,
        MultipartFile file
    ) {
        if (customerId == null || contractKey == null) throw new ApiException("请指定合同");
        boolean exists = ledger
            .contracted()
            .stream()
            .anyMatch(f -> f.customerId().equals(customerId) && f.contractKey().equals(contractKey));
        if (!exists) throw new ApiException("未找到该合同的门店订单");
        if (category == null || !CATEGORIES.containsKey(category)) throw new ApiException("附件类别不正确");
        if (note != null && note.length() > 500) throw new ApiException("备注不能超过 500 字");
        if (file == null || file.isEmpty()) throw new ApiException("请选择文件");
        if (file.getSize() > MAX_BYTES) throw new ApiException("单个附件不能超过 20 MB");
        String name = cleanName(file.getOriginalFilename());
        String ext = extension(name);
        if (!TYPES.containsKey(ext)) throw new ApiException("仅支持 PDF、图片（JPG/PNG）、Word、Excel 和 OFD 文件");

        String id = db.id();
        var today = LocalDate.now();
        String relative = today.getYear() + "/" + String.format("%02d", today.getMonthValue()) + "/" + id;
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IllegalStateException("附件路径越界");
        String sha;
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), id, ".part");
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] head = new byte[8];
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
                if (
                    db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM contract_attachment WHERE customer_id=? AND contract_no=? AND sha256=? AND deleted_at IS NULL",
                        Integer.class,
                        customerId,
                        contractKey,
                        sha
                    ) >
                    0
                ) throw new ApiException("该文件已上传到此合同");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("附件保存失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        // 数据库回滚时删除已落盘的文件，避免孤儿文件
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
        db.jdbc.update(
            """
            INSERT INTO contract_attachment(id,customer_id,contract_no,category,file_name,extension,size_bytes,sha256,storage_path,note,uploaded_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            customerId,
            contractKey,
            category,
            name,
            ext,
            file.getSize(),
            sha,
            relative,
            note == null || note.isBlank() ? null : note.trim(),
            actor()
        );
        db.audit(
            "ATTACHMENT_UPLOADED",
            id,
            "合同 " + contractKey + "，" + CATEGORIES.get(category) + "：" + name + "（" + file.getSize() + " 字节）"
        );
        return Map.of("id", id);
    }

    public record Stored(String fileName, String contentType, long size, Path path) {}

    public Stored file(String id) {
        var row = db.one("SELECT * FROM contract_attachment WHERE id=? AND deleted_at IS NULL", id);
        Path path = root.resolve(row.get("storage_path").toString()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new ApiException("附件文件缺失，请联系管理员");
        db.audit("ATTACHMENT_DOWNLOADED", id, db.text(row, "file_name"));
        return new Stored(
            row.get("file_name").toString(),
            TYPES.getOrDefault(row.get("extension").toString(), "application/octet-stream"),
            ((Number) row.get("size_bytes")).longValue(),
            path
        );
    }

    /** 软删除：仅管理员或上传人可删，文件保留。 */
    @Transactional
    public void delete(String id) {
        var row = db.one("SELECT * FROM contract_attachment WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        String actor = actor();
        boolean admin = SecurityContextHolder.getContext()
            .getAuthentication()
            .getAuthorities()
            .stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (
            !admin && !actor.equals(row.get("uploaded_by"))
        ) throw new org.springframework.security.access.AccessDeniedException("仅管理员或上传人可删除附件");
        db.jdbc.update(
            "UPDATE contract_attachment SET deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?",
            actor,
            id
        );
        db.audit("ATTACHMENT_DELETED", id, db.text(row, "contract_no") + "：" + db.text(row, "file_name"));
    }

    static String cleanName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").trim();
        if (n.isEmpty() || n.startsWith(".")) throw new ApiException("文件名不正确");
        if (n.length() > 200) {
            String ext = extension(n);
            n = n.substring(0, 190) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n;
    }

    static String extension(String name) {
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
            default -> false;
        };
    }

    private static boolean starts(byte[] h, int... sig) {
        if (h.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((h[i] & 0xFF) != sig[i]) return false;
        return true;
    }

    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
