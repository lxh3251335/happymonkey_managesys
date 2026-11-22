package cn.tianshang.commerce.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;

/** 附件下载的响应：合同附件、产品图纸、门店附件共用同一套安全响应头。 */
public final class FileResponses {

    private FileResponses() {}

    /**
     * PDF 和图片在 inline=true 时可在浏览器里预览，其余一律作为附件下载。
     * 预览 PDF 时不允许脚本和外部资源（Chrome 不渲染带 sandbox 的 PDF），其余加 sandbox。
     */
    public static ResponseEntity<FileSystemResource> of(
        String fileName,
        String contentType,
        long size,
        Path path,
        boolean inline
    ) {
        boolean pdf = contentType.equals("application/pdf");
        boolean preview = inline && (pdf || contentType.equals("image/png") || contentType.equals("image/jpeg"));
        var disposition = (preview ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(
            fileName,
            StandardCharsets.UTF_8
        ).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .contentLength(size)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(
                "Content-Security-Policy",
                preview && pdf
                    ? "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; object-src 'self'; frame-ancestors 'self'"
                    : "sandbox"
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new FileSystemResource(path));
    }
}
