package cn.tianshang.commerce.project;

import cn.tianshang.commerce.common.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 门店附件：客户给的配置清单、我们按清单做的设计图。挂在门店上，所以换报价版本、生成合同之后都还能看到。
 * 能看这个门店的人都能看；业务员、管理层、管理员可以上传；只有管理员或上传人能删（软删除，文件保留）。
 */
@Service
public class ProjectAttachmentService {

    static final long MAX_BYTES = 30L * 1024 * 1024;
    static final Set<String> ALLOWED = Set.of("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx", "dwg", "dxf");
    public static final Map<String, String> CATEGORIES = Map.of(
        "CONFIG_LIST",
        "客户配置清单",
        "DESIGN",
        "设计图",
        "OTHER",
        "其他"
    );

    public record Stored(String fileName, String contentType, long size, Path path) {}

    private final Database db;
    private final FileStore store;
    private final Ownership ownership;

    public ProjectAttachmentService(Database db, FileStore store, Ownership ownership) {
        this.db = db;
        this.store = store;
        this.ownership = ownership;
    }

    public List<Map<String, Object>> list(String projectId) {
        ownership.checkProject(projectId);
        return db.jdbc.queryForList(
            """
            SELECT id,project_id,category,file_name,extension,size_bytes,note,uploaded_by,created_at
            FROM project_attachment WHERE project_id=? AND deleted_at IS NULL ORDER BY created_at DESC
            """,
            projectId
        );
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public Map<String, Object> upload(String projectId, String category, String note, MultipartFile file) {
        var project = db.one("SELECT id,name FROM project WHERE id=?", projectId);
        ownership.checkProject(projectId);
        if (category == null || !CATEGORIES.containsKey(category)) throw new ApiException("附件类别不正确");
        if (note != null && note.length() > 500) throw new ApiException("备注不能超过 500 字");
        String id = db.id();
        var saved = store.save(
            id,
            file,
            ALLOWED,
            MAX_BYTES,
            "仅支持 PDF、图片（JPG/PNG）、Word、Excel 和 CAD（DWG/DXF）文件",
            sha ->
                db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project_attachment WHERE project_id=? AND sha256=? AND deleted_at IS NULL",
                    Integer.class,
                    projectId,
                    sha
                ) >
                0
        );
        db.jdbc.update(
            """
            INSERT INTO project_attachment(id,project_id,category,file_name,extension,size_bytes,sha256,storage_path,note,uploaded_by)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            projectId,
            category,
            saved.fileName(),
            saved.extension(),
            saved.size(),
            saved.sha256(),
            saved.relativePath(),
            note == null || note.isBlank() ? null : note.trim(),
            Ownership.username()
        );
        db.audit(
            "PROJECT_ATTACHMENT_UPLOADED",
            id,
            project.get("name") + "，" + CATEGORIES.get(category) + "：" + saved.fileName()
        );
        return Map.of("id", id);
    }

    public Stored file(String id) {
        var row = db.one("SELECT * FROM project_attachment WHERE id=? AND deleted_at IS NULL", id);
        ownership.checkProject(row.get("project_id").toString());
        return new Stored(
            row.get("file_name").toString(),
            FileStore.contentType(row.get("extension").toString()),
            ((Number) row.get("size_bytes")).longValue(),
            store.existing(row.get("storage_path").toString())
        );
    }

    @Transactional
    public void delete(String id) {
        var row = db.one("SELECT * FROM project_attachment WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
        ownership.checkProject(row.get("project_id").toString());
        if (!Roles.isAdmin() && !Ownership.username().equals(row.get("uploaded_by"))) throw new AccessDeniedException(
            "仅管理员或上传人可删除附件"
        );
        db.jdbc.update(
            "UPDATE project_attachment SET deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?",
            Ownership.username(),
            id
        );
        db.audit("PROJECT_ATTACHMENT_DELETED", id, db.text(row, "file_name"));
    }
}
