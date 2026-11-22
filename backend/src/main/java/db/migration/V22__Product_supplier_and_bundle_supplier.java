package db.migration;

import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 标准品按供应商区分：同一种产品（应用场景 + 名称 + 规格 + 型号）每家供应商一条，所以唯一性要加上供应商。
 * 原来的唯一约束是建表时匿名加的，各数据库里的名字不同，这里从 information_schema 查出来再删。
 * 同时给产品加采购成本（这家供应商的拿货价），给组合加供应商（一个组合一家供应商）。
 */
public class V22__Product_supplier_and_bundle_supplier extends BaseJavaMigration {

    @Override
    public void migrate(Context context) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
        List<String> uniques = jdbc.queryForList(
            "SELECT constraint_name FROM information_schema.table_constraints WHERE LOWER(table_name)='product' AND constraint_type='UNIQUE'",
            String.class
        );
        for (String name : uniques) jdbc.execute("ALTER TABLE product DROP CONSTRAINT \"" + name + "\"");
        jdbc.execute(
            "ALTER TABLE product ADD CONSTRAINT uq_product_supplier UNIQUE(scenario,name,specification,model,supplier_id)"
        );
        jdbc.execute("ALTER TABLE product ADD COLUMN purchase_cost NUMERIC(18,6)");
        jdbc.execute("ALTER TABLE bundle ADD COLUMN supplier_id VARCHAR(36) REFERENCES partner(id)");
    }
}
