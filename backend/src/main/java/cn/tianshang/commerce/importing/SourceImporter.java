package cn.tianshang.commerce.importing;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.contract.Contracts;
import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceImporter {

    private final Database db;
    private final ObjectMapper mapper;
    private final cn.tianshang.commerce.business.BusinessService business;
    private final cn.tianshang.commerce.dictionary.DictionaryService dictionary;

    public SourceImporter(
        Database db,
        ObjectMapper mapper,
        cn.tianshang.commerce.business.BusinessService business,
        cn.tianshang.commerce.dictionary.DictionaryService dictionary
    ) {
        this.db = db;
        this.mapper = mapper;
        this.business = business;
        this.dictionary = dictionary;
    }

    @Transactional
    public void importSeed(Path path) throws Exception {
        JsonNode seed = mapper.readTree(path.toFile());
        String batch = seed.path("batch").asText();
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM import_batch WHERE id=?", Integer.class, batch) > 0) return;
        var projects = new HashMap<String, String>();
        var quotes = new HashMap<String, String>();
        var products = new HashMap<String, String>();
        int position = 0;
        for (var r : seed.path("records")) {
            String customer = partner(r.path("customer").asText());
            String key = customer + "::" + r.path("project").asText();
            String projectId = projects.get(key),
                quoteId = quotes.get(key);
            if (projectId == null) {
                projectId = db.id();
                quoteId = db.id();
                projects.put(key, projectId);
                quotes.put(key, quoteId);
                String code = "LS-" + projectId.substring(0, 8).toUpperCase();
                var date = r.path("orderDate").isNull() ? null : LocalDate.parse(r.path("orderDate").asText());
                String note =
                    "历史发货状态：" + r.path("shipping").asText() + "；原合同号：" + r.path("contract").asText();
                db.jdbc.update(
                    "INSERT INTO project(id,code,name,brand,customer_id,order_date,note,contract_no,shipping_status,city) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    projectId,
                    code,
                    r.path("project").asText(),
                    business.resolveOrUnassigned(r.path("brand").asText()),
                    customer,
                    date,
                    note,
                    Contracts.blankToNull(r.path("contract").asText()),
                    Contracts.shipping(r.path("shipping").asText()),
                    dictionary.detectCity(r.path("project").asText())
                );
                db.jdbc.update(
                    "INSERT INTO quotation(id,code,project_id,version,status,tax_basis,note,created_at) VALUES(?,?,?,1,'HISTORICAL','UNKNOWN',?,?)",
                    quoteId,
                    "LSBJ-" + quoteId.substring(0, 8).toUpperCase(),
                    projectId,
                    "历史报价导入；税口径、合同及应收需核实",
                    Timestamp.valueOf(date == null ? LocalDateTime.now() : date.atStartOfDay())
                );
            }
            String productKey =
                r.path("brand").asText() +
                "::" +
                r.path("name").asText() +
                "::" +
                r.path("specification").asText() +
                "::" +
                r.path("model").asText();
            String productId = products.get(productKey);
            if (productId == null) {
                var existing = db.jdbc.queryForList(
                    "SELECT id FROM product WHERE scenario=? AND name=? AND specification=? AND model=?",
                    r.path("brand").asText(),
                    r.path("name").asText(),
                    r.path("specification").asText(),
                    r.path("model").asText()
                );
                productId = existing.isEmpty() ? db.id() : existing.get(0).get("id").toString();
                products.put(productKey, productId);
                if (existing.isEmpty()) dictionary.ensureScenario(r.path("brand").asText());
                if (existing.isEmpty()) db.jdbc.update(
                    "INSERT INTO product(id,scenario,name,specification,model,unit) VALUES(?,?,?,?,?,'台')",
                    productId,
                    r.path("brand").asText(),
                    r.path("name").asText(),
                    r.path("specification").asText(),
                    r.path("model").asText()
                );
            }
            BigDecimal qty = num(r, "quantity"),
                amount = num(r, "amount"),
                cost = num(r, "cost");
            db.jdbc.update(
                """
                INSERT INTO quotation_item(id,quotation_id,product_id,position,name,specification,unit,quantity,unit_price,unit_cost,amount,cost,source,note)
                VALUES(?,?,?,?,?,?,'台',?,?,?,?,?,?,?)
                """,
                db.id(),
                quoteId,
                productId,
                ++position,
                r.path("name").asText(),
                r.path("specification").asText(),
                qty,
                amount == null ? null : amount.divide(qty, 6, RoundingMode.HALF_UP),
                cost == null ? null : cost.divide(qty, 6, RoundingMode.HALF_UP),
                amount,
                cost,
                r.path("source").asText() + "；原始 " + r.path("rawSource").asText(),
                r.path("note").asText()
            );
        }
        db.jdbc.update("INSERT INTO import_batch(id,detail) VALUES(?,?)", batch, seed.path("summary").toString());
        db.audit("SOURCE_IMPORTED", batch, seed.path("summary").toString());
    }

    private BigDecimal num(JsonNode r, String key) {
        return r.path(key).isNull() ? null : new BigDecimal(r.path(key).asText());
    }

    private String partner(String name) {
        var rows = db.jdbc.queryForList("SELECT id FROM partner WHERE kind='CUSTOMER' AND name=?", name);
        if (!rows.isEmpty()) return rows.get(0).get("id").toString();
        String id = db.id();
        db.jdbc.update("INSERT INTO partner(id,name,kind) VALUES(?,?,'CUSTOMER')", id, name);
        return id;
    }
}
