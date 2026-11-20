package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.FileStore;
import java.nio.file.Path;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 产品图纸：PDF、图片、DWG/DXF。上传、下载、删除都记审计；删除为软删除，文件保留。 */
@Service
public class DrawingService {

    public static final long MAX_BYTES = 30L * 1024 * 1024;
    static final Set<String> ALLOWED = Set.of("pdf", "png", "jpg", "jpeg", "dwg", "dxf");

    private final Database db;
    private final FileStore store;

    public DrawingService(Database db, FileStore store) {
        this.db = db;
        this.store = store;
    }

    public List<Map<String, Object>> list(String productId) {
        return db.jdbc.queryForList(
            """
            SELECT id,product_id,file_name,extension,size_bytes,sha256,note,uploaded_by,created_at
            FROM product_drawing WHERE product_id=? AND deleted_at IS NULL ORDER BY created_at DESC
            """,
            productId
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public Map<String, Object> upload(String productId, String note, MultipartFile file) {
        var product = db.one("SELECT name,specification FROM product WHERE id=?", productId);
        if (note != null && note.length() > 500) throw new ApiException("备注不能超过 500 字");
        String id = db.id();
        var saved = store.save(
            id,
            file,
            ALLOWED,
            MAX_BYTES,
            "图纸仅支持 PDF、PNG/JPG、DWG、DXF 文件",
            sha ->
                db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM product_drawing WHERE product_id=? AND sha256=? AND deleted_at IS NULL",
                    Integer.class,
                    productId,
                    sha
                ) >
                0
        );
        db.jdbc.update(
            """
            INSERT INTO product_drawing(id,product_id,file_name,extension,size_bytes,sha256,storage_path,note,uploaded_by)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,
            id,
            productId,
            saved.fileName(),
            saved.extension(),
            saved.size(),
            saved.sha256(),
            saved.relativePath(),
            note == null || note.isBlank() ? null : note.trim(),
            actor()
        );
        db.audit(
            "DRAWING_UPLOADED",
            id,
            product.get("name") + "/" + product.get("specification") + "：" + saved.fileName()
        );
        return Map.of("id", id);
    }

    public record Stored(String fileName, String contentType, long size, Path path) {}

    public Stored file(String id) {
        var row = db.one("SELECT * FROM product_drawing WHERE id=? AND deleted_at IS NULL", id);
        Path path = store.existing(row.get("storage_path").toString());
        db.audit("DRAWING_DOWNLOADED", id, db.text(row, "file_name"));
        return new Stored(
            row.get("file_name").toString(),
            FileStore.contentType(row.get("extension").toString()),
            ((Number) row.get("size_bytes")).longValue(),
            path
        );
    }

    /** 软删除：管理员或上传人。 */
    @Transactional
    public void delete(String id) {
        var row = db.one("SELECT * FROM product_drawing WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth
            .getAuthorities()
            .stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !actor().equals(row.get("uploaded_by"))) throw new AccessDeniedException(
            "仅管理员或上传人可删除图纸"
        );
        db.jdbc.update("UPDATE product_drawing SET deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?", actor(), id);
        db.audit("DRAWING_DELETED", id, db.text(row, "file_name"));
    }

    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
