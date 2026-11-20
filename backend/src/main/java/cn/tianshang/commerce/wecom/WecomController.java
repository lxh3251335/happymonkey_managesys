package cn.tianshang.commerce.wecom;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wecom")
public class WecomController {

    private final WecomConfigService service;

    public WecomController(WecomConfigService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public Map<String, Object> get() {
        return service.get();
    }

    @PutMapping("/config")
    public Map<String, Object> save(@Valid @RequestBody WecomConfigService.ConfigInput input) {
        return service.save(input);
    }

    @PostMapping("/reconnect")
    public Map<String, Object> reconnect() {
        return service.reconnect();
    }
}
