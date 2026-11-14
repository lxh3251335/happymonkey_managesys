package cn.tianshang.commerce.master;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/products")
public class DrawingController {

    private final DrawingService service;

    public DrawingController(DrawingService service) {
        this.service = service;
    }

    @GetMapping("/{productId}/drawings")
    public List<Map<String, Object>> list(@PathVariable String productId) {
        return service.list(productId);
    }

    @PostMapping(value = "/{productId}/drawings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
        @PathVariable String productId,
        @RequestParam(required = false) String note,
        @RequestParam MultipartFile file
    ) {
        return service.upload(productId, note, file);
    }

    /** PDF 与图片可 inline 预览；DWG/DXF 一律下载。 */
    @GetMapping("/drawings/{id}/file")
    public ResponseEntity<FileSystemResource> download(
        @PathVariable String id,
        @RequestParam(defaultValue = "false") boolean inline
    ) {
        var f = service.file(id);
        boolean pdf = f.contentType().equals("application/pdf");
        boolean preview =
            inline && (pdf || f.contentType().startsWith("image/png") || f.contentType().startsWith("image/jpeg"));
        var disposition = (preview ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(
            f.fileName(),
            StandardCharsets.UTF_8
        ).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(f.contentType()))
            .contentLength(f.size())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(
                "Content-Security-Policy",
                preview && pdf
                    ? "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; object-src 'self'; frame-ancestors 'self'"
                    : "sandbox"
            )
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new FileSystemResource(f.path()));
    }

    @DeleteMapping("/drawings/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
