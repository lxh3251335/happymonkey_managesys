package cn.tianshang.commerce.dictionary;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动时初始化城市字典，并把已有数据中的应用场景、城市补进字典。须在城市回填与历史导入之前执行。 */
@Component
@Order(0)
public class DictionarySeeder implements ApplicationRunner {

    private final DictionaryService service;

    public DictionarySeeder(DictionaryService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.seedCities();
        service.syncFromData();
    }
}
