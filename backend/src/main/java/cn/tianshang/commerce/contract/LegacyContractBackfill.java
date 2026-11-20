package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.Database;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 早期导入只把合同号和发货状态写在项目备注里；启动时补写到结构化字段，可重复执行。 */
@Component
@Order(1)
public class LegacyContractBackfill implements ApplicationRunner {

    private static final Pattern NOTE = Pattern.compile("历史发货状态：([^；]*)；原合同号：(.*)$");
    private final Database db;

    public LegacyContractBackfill(Database db) {
        this.db = db;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var rows = db.jdbc.queryForList(
            "SELECT id,note FROM project WHERE contract_no IS NULL AND shipping_status IS NULL AND note LIKE '历史发货状态：%'"
        );
        for (var row : rows) {
            var m = NOTE.matcher(db.text(row, "note").trim());
            if (!m.find()) continue;
            db.jdbc.update(
                "UPDATE project SET contract_no=?,shipping_status=? WHERE id=?",
                Contracts.blankToNull(m.group(2)),
                Contracts.shipping(m.group(1)),
                row.get("id")
            );
        }
    }
}
