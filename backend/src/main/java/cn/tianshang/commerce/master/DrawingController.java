package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.FileResponses;
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
        return FileResponses.of(f.fileName(), f.contentType(), f.size(), f.path(), inline);
    }

    @DeleteMapping("/drawings/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
