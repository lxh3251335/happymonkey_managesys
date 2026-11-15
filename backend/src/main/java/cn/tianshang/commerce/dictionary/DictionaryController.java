package cn.tianshang.commerce.dictionary;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dictionaries")
public class DictionaryController {

    private final DictionaryService service;

    public DictionaryController(DictionaryService service) {
        this.service = service;
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return service.scenarios();
    }

    @PostMapping("/scenarios")
    public Map<String, String> createScenario(@Valid @RequestBody DictionaryService.ScenarioInput input) {
        return Map.of("id", service.saveScenario(null, input));
    }

    @PutMapping("/scenarios/{id}")
    public void updateScenario(@PathVariable String id, @Valid @RequestBody DictionaryService.ScenarioInput input) {
        service.saveScenario(id, input);
    }

    @GetMapping("/cities")
    public List<Map<String, Object>> cities() {
        return service.cities();
    }

    @PostMapping("/cities")
    public Map<String, String> createCity(@Valid @RequestBody DictionaryService.CityInput input) {
        return Map.of("id", service.saveCity(null, input));
    }

    @PutMapping("/cities/{id}")
    public void updateCity(@PathVariable String id, @Valid @RequestBody DictionaryService.CityInput input) {
        service.saveCity(id, input);
    }

    @GetMapping("/companies")
    public List<Map<String, Object>> companies() {
        return service.companies();
    }

    @PostMapping("/companies")
    public Map<String, String> createCompany(@Valid @RequestBody DictionaryService.CompanyInput input) {
        return Map.of("id", service.saveCompany(null, input));
    }

    @PutMapping("/companies/{id}")
    public void updateCompany(@PathVariable String id, @Valid @RequestBody DictionaryService.CompanyInput input) {
        service.saveCompany(id, input);
    }
}
