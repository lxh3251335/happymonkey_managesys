package cn.tianshang.commerce.search;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    public List<SearchService.Hit> search(@RequestParam(defaultValue = "") String q) {
        return service.search(q.length() > 60 ? q.substring(0, 60) : q);
    }
}
