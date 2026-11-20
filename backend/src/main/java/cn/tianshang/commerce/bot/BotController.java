package cn.tianshang.commerce.bot;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 机器人账号绑定：每个人管理自己的绑定；管理员可以查看和解除所有人的。 */
@RestController
@RequestMapping("/api/bot")
public class BotController {

    private final BotBindingService service;

    public BotController(BotBindingService service) {
        this.service = service;
    }

    @GetMapping("/binding")
    public Map<String, Object> mine() {
        return service.mine();
    }

    @PostMapping("/binding/code")
    public Map<String, Object> code() {
        return service.newCode();
    }

    @DeleteMapping("/binding")
    public void unbindMine() {
        service.unbindMine();
    }

    @GetMapping("/bindings")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @DeleteMapping("/bindings/{id}")
    public void unbind(@PathVariable String id) {
        service.unbind(id);
    }
}
