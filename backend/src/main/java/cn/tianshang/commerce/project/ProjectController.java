package cn.tianshang.commerce.project;

import cn.tianshang.commerce.common.FileResponses;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService service;
    private final ProjectAttachmentService attachments;

    public ProjectController(ProjectService service, ProjectAttachmentService attachments) {
        this.service = service;
        this.attachments = attachments;
    }

    // ---------- 门店附件：配置清单、设计图 ----------

    @GetMapping("/projects/{id}/attachments")
    public List<Map<String, Object>> attachments(@PathVariable String id) {
        return attachments.list(id);
    }

    @PostMapping(value = "/projects/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
        @PathVariable String id,
        @RequestParam String category,
        @RequestParam(required = false) String note,
        @RequestParam MultipartFile file
    ) {
        return attachments.upload(id, category, note, file);
    }

    @GetMapping("/project-attachments/{id}/file")
    public ResponseEntity<FileSystemResource> download(
        @PathVariable String id,
        @RequestParam(defaultValue = "false") boolean inline
    ) {
        var f = attachments.file(id);
        return FileResponses.of(f.fileName(), f.contentType(), f.size(), f.path(), inline);
    }

    @DeleteMapping("/project-attachments/{id}")
    public void deleteAttachment(@PathVariable String id) {
        attachments.delete(id);
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping("/projects")
    public Map<String, String> create(@Valid @RequestBody ProjectService.ProjectInput input) {
        return Map.of("id", service.create(input));
    }

    @PutMapping("/projects/{id}")
    public void update(@PathVariable String id, @Valid @RequestBody ProjectService.ProjectInput input) {
        service.update(id, input);
    }

    @PutMapping("/projects/{id}/delivery")
    public void delivery(@PathVariable String id, @Valid @RequestBody ProjectService.DeliveryInput input) {
        service.delivery(id, input);
    }

    @PostMapping("/projects/owner")
    public Map<String, Object> assignOwner(@Valid @RequestBody ProjectService.AssignInput input) {
        return service.assignOwner(input);
    }

    @GetMapping("/projects/couriers")
    public List<String> couriers() {
        return ProjectService.COURIERS;
    }

    @GetMapping("/owners")
    public List<Map<String, Object>> owners() {
        return service.owners();
    }
}
