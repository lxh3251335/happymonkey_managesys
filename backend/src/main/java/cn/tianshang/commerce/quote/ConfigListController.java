package cn.tianshang.commerce.quote;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/quotes")
public class ConfigListController {

    private final ConfigListService service;

    public ConfigListController(ConfigListService service) {
        this.service = service;
    }

    /** 读客户的配置清单并对到产品库，返回建议，不写数据。 */
    @PostMapping(value = "/config-list", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> read(@RequestParam MultipartFile file) {
        return service.read(file);
    }
}
