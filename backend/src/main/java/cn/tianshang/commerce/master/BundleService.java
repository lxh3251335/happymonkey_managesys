package cn.tianshang.commerce.master;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.dictionary.DictionaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标准组合（像套餐）：按应用场景把几个标准品和数量打包，报价和项目类型标准配置里可以整组加入。
 * 组合不单独定价，组合价 = 里面标准品的单价 × 数量之和。不再使用的停用，不删除（历史报价还引用它）。
 */
@Service
public class BundleService {

    private final Database db;
    private final DictionaryService dictionary;

    public BundleService(Database db, DictionaryService dictionary) {
        this.db = db;
        this.dictionary = dictionary;
    }

    /** 全部组合（含停用的），带里面的标准品；参考价按各标准品最近一次报价相加，缺价的不算。 */
    public List<Map<String, Object>> list() {
        var bundles = db.jdbc.queryForList("SELECT * FROM bundle ORDER BY active DESC,scenario,name");
        var items = new HashMap<String, List<Map<String, Object>>>();
        for (var i : db.jdbc.queryForList(
            """
            SELECT b.*,p.name,p.specification,p.model,p.unit,p.scenario AS product_scenario,
              (SELECT q.unit_price FROM quotation_item q JOIN quotation h ON h.id=q.quotation_id
                 WHERE q.product_id=p.id AND q.unit_price IS NOT NULL ORDER BY h.created_at DESC,q.id LIMIT 1) AS reference_price
            FROM bundle_item b JOIN product p ON p.id=b.product_id ORDER BY b.position
            """
        ))
            items.computeIfAbsent(i.get("bundle_id").toString(), k -> new ArrayList<>()).add(i);
        for (var b : bundles) {
            var list = items.getOrDefault(b.get("id").toString(), List.of());
            BigDecimal price = BigDecimal.ZERO;
            boolean complete = !list.isEmpty();
            for (var i : list) {
                if (i.get("reference_price") == null) complete = false;
                else price = price.add(
                    new BigDecimal(i.get("reference_price").toString()).multiply(
                        new BigDecimal(i.get("quantity").toString())
                    )
                );
            }
            b.put("items", list);
            b.put("reference_price", list.isEmpty() ? null : price);
            b.put("price_complete", complete);
        }
        return bundles;
    }

    public record BundleLine(
        @NotBlank String productId,
        @NotNull @DecimalMin("0.001") @DecimalMax("999999") @Digits(integer = 6, fraction = 3) BigDecimal quantity
    ) {}

    public record BundleInput(
        @NotBlank @Size(max = 60) String scenario,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String note,
        boolean active,
        @NotEmpty @Size(max = 100) List<@Valid BundleLine> items
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public String save(String id, @Valid BundleInput in) {
        var ids = in.items().stream().map(BundleLine::productId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new ApiException(
            "同一个标准品在组合里只能出现一次，请合并数量"
        );
        String current =
            id == null
                ? null
                : db.jdbc
                      .queryForList("SELECT scenario FROM bundle WHERE id=?", String.class, id)
                      .stream()
                      .findFirst()
                      .orElse(null);
        String scenario = dictionary.scenario(in.scenario(), current);
        String name = in.name().trim();
        int same = db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM bundle WHERE scenario=? AND name=? AND id<>?",
            Integer.class,
            scenario,
            name,
            id == null ? "" : id
        );
        if (same > 0) throw new ApiException("应用场景“" + scenario + "”下已经有叫“" + name + "”的组合");
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO bundle(id,scenario,name,note,active) VALUES(?,?,?,?,?)",
                id,
                scenario,
                name,
                blank(in.note()),
                in.active()
            );
        } else {
            db.one("SELECT id FROM bundle WHERE id=? FOR UPDATE", id);
            db.jdbc.update(
                "UPDATE bundle SET scenario=?,name=?,note=?,active=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                scenario,
                name,
                blank(in.note()),
                in.active(),
                id
            );
            db.jdbc.update("DELETE FROM bundle_item WHERE bundle_id=?", id);
        }
        int pos = 0;
        for (var line : in.items()) {
            db.one("SELECT id FROM product WHERE id=?", line.productId());
            db.jdbc.update(
                "INSERT INTO bundle_item(id,bundle_id,product_id,position,quantity) VALUES(?,?,?,?,?)",
                db.id(),
                id,
                line.productId(),
                ++pos,
                line.quantity()
            );
        }
        db.audit(
            "BUNDLE_SAVED",
            id,
            scenario + " / " + name + "，" + in.items().size() + " 种标准品" + (in.active() ? "" : "（停用）")
        );
        return id;
    }

    /** 一组的组合信息（保存明细时写入）：组合 ID、名称快照、套数。 */
    public record Group(String bundleId, String bundleName, BigDecimal sets, int no) {}

    /**
     * 核对明细里的分组：同一组号的行组合和套数必须一致，组合必须存在。返回 组号 → 组。
     * groupOf 取出一行的分组（不属于组合的行返回 null）。
     */
    public <T> Map<Integer, Group> resolve(List<T> lines, java.util.function.Function<T, LineGroup> groupOf) {
        var out = new LinkedHashMap<Integer, Group>();
        for (var line : lines) {
            var g = groupOf.apply(line);
            if (g == null) continue;
            var known = out.get(g.no());
            if (known != null) {
                if (
                    !known.bundleId().equals(g.bundleId()) || known.sets().compareTo(g.sets()) != 0
                ) throw new ApiException("第 " + g.no() + " 组里的明细组合或套数不一致");
                continue;
            }
            var b = db.one("SELECT id,name FROM bundle WHERE id=?", g.bundleId());
            out.put(g.no(), new Group(g.bundleId(), b.get("name").toString(), g.sets(), g.no()));
        }
        return out;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
