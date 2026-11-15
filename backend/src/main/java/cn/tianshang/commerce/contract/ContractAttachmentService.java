package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.common.FileStore;
import java.nio.file.Path;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 合同附件：合同扫描件、盖章报价单、送货签收单等确认凭据。
 *
 * <p>文件按服务端生成的 ID 存放在附件目录，不使用客户端文件名作路径；类型按扩展名白名单并校验文件头。删除为软删除，文件保留以备审计。
 */
@Service
public class ContractAttachmentService {

    public static final long MAX_BYTES = 20L * 1024 * 1024;

    static final Set<String> ALLOWED = Set.of("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx", "ofd");

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
    private final FileStore store;
    private final Ownership ownership;

    public ContractAttachmentService(Database db, ContractLedger ledger, FileStore store, Ownership ownership) {
        this.db = db;
        this.ledger = ledger;
        this.store = store;
        this.ownership = ownership;
    }

    public List<Map<String, Object>> list(String customerId, String contractKey) {
        ownership.checkContract(customerId, contractKey);
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
        ownership.checkContract(customerId, contractKey);
        boolean exists =
            ledger
                .contracted()
                .stream()
                .anyMatch(f -> f.customerId().equals(customerId) && f.contractKey().equals(contractKey)) ||
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE customer_id=? AND contract_no=?",
                Integer.class,
                customerId,
                contractKey
            ) >
            0;
        if (!exists) throw new ApiException("未找到该合同");
        if (category == null || !CATEGORIES.containsKey(category)) throw new ApiException("附件类别不正确");
        if (note != null && note.length() > 500) throw new ApiException("备注不能超过 500 字");
        String id = db.id();
        var saved = store.save(
            id,
            file,
            ALLOWED,
            MAX_BYTES,
            "仅支持 PDF、图片（JPG/PNG）、Word、Excel 和 OFD 文件",
            sha ->
                db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM contract_attachment WHERE customer_id=? AND contract_no=? AND sha256=? AND deleted_at IS NULL",
                    Integer.class,
                    customerId,
                    contractKey,
                    sha
                ) >
                0
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
            saved.fileName(),
            saved.extension(),
            saved.size(),
            saved.sha256(),
            saved.relativePath(),
            note == null || note.isBlank() ? null : note.trim(),
            actor()
        );
        db.audit(
            "ATTACHMENT_UPLOADED",
            id,
            "合同 " +
                contractKey +
                "，" +
                CATEGORIES.get(category) +
                "：" +
                saved.fileName() +
                "（" +
                saved.size() +
                " 字节）"
        );
        return Map.of("id", id);
    }

    public record Stored(String fileName, String contentType, long size, Path path) {}

    public Stored file(String id) {
        var row = db.one("SELECT * FROM contract_attachment WHERE id=? AND deleted_at IS NULL", id);
        ownership.checkContract(row.get("customer_id").toString(), row.get("contract_no").toString());
        Path path = store.existing(row.get("storage_path").toString());
        db.audit("ATTACHMENT_DOWNLOADED", id, db.text(row, "file_name"));
        return new Stored(
            row.get("file_name").toString(),
            FileStore.contentType(row.get("extension").toString()),
            ((Number) row.get("size_bytes")).longValue(),
            path
        );
    }

    /** 软删除：仅管理员或上传人可删，文件保留。 */
    @Transactional
    public void delete(String id) {
        var row = db.one("SELECT * FROM contract_attachment WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        ownership.checkContract(row.get("customer_id").toString(), row.get("contract_no").toString());
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

    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
