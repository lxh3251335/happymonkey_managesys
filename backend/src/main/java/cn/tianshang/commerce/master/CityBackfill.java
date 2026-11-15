package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.dictionary.DictionaryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 启动时为未填城市的项目按门店名称识别城市；只补空值，不覆盖人工填写，可重复执行。 */
@Component
@Order(2)
public class CityBackfill implements ApplicationRunner {

    private final Database db;
    private final DictionaryService dictionary;

    public CityBackfill(Database db, DictionaryService dictionary) {
        this.db = db;
        this.dictionary = dictionary;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (var row : db.jdbc.queryForList("SELECT id,name FROM project WHERE city IS NULL")) {
            String city = dictionary.detectCity(db.text(row, "name"));
            if (city != null) db.jdbc.update(
                "UPDATE project SET city=? WHERE id=? AND city IS NULL",
                city,
                row.get("id")
            );
        }
    }
}
