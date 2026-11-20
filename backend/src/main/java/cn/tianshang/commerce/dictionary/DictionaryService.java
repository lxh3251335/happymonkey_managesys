package cn.tianshang.commerce.dictionary;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.master.Cities;
import cn.tianshang.commerce.master.TaxNumbers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据字典：应用场景、城市、我方签约主体。
 *
 * <p>产品的应用场景、项目的城市保存名称，字典改名时同步更新；停用的条目保留在已有记录上，但不能再新选。
 */
@Service
public class DictionaryService {

    private final Database db;

    public DictionaryService(Database db) {
        this.db = db;
    }

    // ===================== 应用场景 =====================

    public List<Map<String, Object>> scenarios() {
        return db.jdbc.queryForList(
            """
            SELECT s.*,(SELECT COUNT(*) FROM product p WHERE p.scenario=s.name) AS products
            FROM scenario s ORDER BY s.sort,s.name
            """
        );
    }

    public record ScenarioInput(
        @NotBlank @Size(max = 60) String name,
        int sort,
        boolean active,
        @Size(max = 500) String note
    ) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String saveScenario(String id, @Valid ScenarioInput s) {
        String name = s.name().trim();
        unique("scenario", name, id, "应用场景");
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO scenario(id,name,sort,active,note) VALUES(?,?,?,?,?)",
                id,
                name,
                s.sort(),
                s.active(),
                blank(s.note())
            );
            db.audit("SCENARIO_CREATED", id, name);
            return id;
        }
        var old = db.one("SELECT * FROM scenario WHERE id=? FOR UPDATE", id);
        String oldName = db.text(old, "name");
        db.jdbc.update(
            "UPDATE scenario SET name=?,sort=?,active=?,note=? WHERE id=?",
            name,
            s.sort(),
            s.active(),
            blank(s.note()),
            id
        );
        int n = oldName.equals(name)
            ? 0
            : db.jdbc.update("UPDATE product SET scenario=? WHERE scenario=?", name, oldName);
        db.audit(
            "SCENARIO_UPDATED",
            id,
            oldName + " → " + name + (s.active() ? "" : "（停用）") + (n > 0 ? "；同步更新 " + n + " 个产品" : "")
        );
        return id;
    }

    /** 录入的应用场景须在字典中；停用的场景只允许保留原值。 */
    public String scenario(String input, String current) {
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) throw new ApiException("请选择应用场景");
        var rows = db.jdbc.queryForList("SELECT name,active FROM scenario WHERE name=?", v);
        if (rows.isEmpty()) throw new ApiException("应用场景“" + v + "”不在字典中，请先在系统管理 → 数据字典中添加");
        if (!Boolean.TRUE.equals(rows.get(0).get("active")) && !v.equals(current)) throw new ApiException(
            "应用场景“" + v + "”已停用"
        );
        return v;
    }

    /** 历史导入：场景不存在时自动加入字典。 */
    public void ensureScenario(String name) {
        if (name == null || name.isBlank()) return;
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM scenario WHERE name=?", Integer.class, name.trim()) == 0) {
            int sort = db.jdbc.queryForObject("SELECT COALESCE(MAX(sort),0)+1 FROM scenario", Integer.class);
            db.jdbc.update("INSERT INTO scenario(id,name,sort) VALUES(?,?,?)", db.id(), name.trim(), sort);
        }
    }

    public List<String> activeScenarioNames() {
        return db.jdbc.queryForList("SELECT name FROM scenario WHERE active=TRUE ORDER BY sort,name", String.class);
    }

    // ===================== 城市 =====================

    public List<Map<String, Object>> cities() {
        return db.jdbc.queryForList(
            """
            SELECT c.*,(SELECT COUNT(*) FROM project p WHERE p.city=c.name) AS projects
            FROM city c ORDER BY c.sort,c.province,c.name
            """
        );
    }

    public record CityInput(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 30) String province,
        @Size(max = 1000) String aliases,
        int sort,
        boolean active
    ) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String saveCity(String id, @Valid CityInput c) {
        String name = c.name().trim();
        unique("city", name, id, "城市");
        var aliases = Cities.splitAliases(c.aliases());
        // 别名不能与其他城市的名称或别名重复，否则识别会有歧义
        for (var other : db.jdbc.queryForList("SELECT id,name,aliases FROM city WHERE id<>?", id == null ? "" : id)) {
            String otherName = db.text(other, "name");
            if (aliases.contains(otherName)) throw new ApiException("别名“" + otherName + "”是另一个城市的名称");
            for (String a : Cities.splitAliases(db.text(other, "aliases"))) {
                if (aliases.contains(a)) throw new ApiException("别名“" + a + "”已属于" + otherName);
                if (a.equals(name)) throw new ApiException("“" + name + "”已是" + otherName + "的别名");
            }
        }
        String aliasText = aliases.isEmpty() ? null : String.join("、", aliases);
        if (id == null) {
            id = db.id();
            db.jdbc.update(
                "INSERT INTO city(id,name,province,aliases,sort,active) VALUES(?,?,?,?,?,?)",
                id,
                name,
                blank(c.province()),
                aliasText,
                c.sort(),
                c.active()
            );
            db.audit("CITY_CREATED", id, name);
            return id;
        }
        var old = db.one("SELECT * FROM city WHERE id=? FOR UPDATE", id);
        String oldName = db.text(old, "name");
        db.jdbc.update(
            "UPDATE city SET name=?,province=?,aliases=?,sort=?,active=? WHERE id=?",
            name,
            blank(c.province()),
            aliasText,
            c.sort(),
            c.active(),
            id
        );
        int n = oldName.equals(name) ? 0 : db.jdbc.update("UPDATE project SET city=? WHERE city=?", name, oldName);
        db.audit(
            "CITY_UPDATED",
            id,
            oldName + " → " + name + (c.active() ? "" : "（停用）") + (n > 0 ? "；同步更新 " + n + " 个项目" : "")
        );
        return id;
    }

    /** 录入的城市：可填城市名或别名（如“大兴”→北京）；空值为 null；字典中没有则报错。 */
    public String city(String input, String current) {
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) return null;
        if (v.equals(current)) return v;
        for (var c : db.jdbc.queryForList("SELECT name,aliases,active FROM city")) {
            String name = db.text(c, "name");
            if (name.equals(v) || Cities.splitAliases(db.text(c, "aliases")).contains(v)) {
                if (!Boolean.TRUE.equals(c.get("active")) && !name.equals(current)) throw new ApiException(
                    "城市“" + name + "”已停用"
                );
                return name;
            }
        }
        throw new ApiException("城市“" + v + "”不在城市字典中，请先在系统管理 → 数据字典中添加");
    }

    /** 从门店名称识别城市（只用启用的城市）。 */
    public String detectCity(String storeName) {
        return Cities.detect(storeName, cityEntries());
    }

    private List<Cities.Entry> cityEntries() {
        var out = new ArrayList<Cities.Entry>();
        for (var c : db.jdbc.queryForList("SELECT name,aliases FROM city WHERE active=TRUE ORDER BY sort,name"))
            out.add(new Cities.Entry(db.text(c, "name"), Cities.splitAliases(db.text(c, "aliases"))));
        return out;
    }

    /** 首次启动：城市字典为空时写入内置城市清单。 */
    @Transactional
    public void seedCities() {
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM city", Integer.class) > 0) return;
        int sort = 0;
        for (var e : Cities.BUILT_IN.entrySet())
            for (String name : e.getValue()) {
                var aliases = Cities.BUILT_IN_ALIASES.getOrDefault(name, List.of());
                db.jdbc.update(
                    "INSERT INTO city(id,name,province,aliases,sort) VALUES(?,?,?,?,?)",
                    db.id(),
                    name,
                    e.getKey(),
                    aliases.isEmpty() ? null : String.join("、", aliases),
                    ++sort
                );
            }
    }

    /** 把已有数据中出现、但字典里没有的应用场景和城市补进字典（可重复执行）。 */
    @Transactional
    public void syncFromData() {
        for (String s : db.jdbc.queryForList("SELECT DISTINCT scenario FROM product", String.class)) ensureScenario(s);
        for (String c : db.jdbc.queryForList(
            "SELECT DISTINCT city FROM project WHERE city IS NOT NULL AND city<>''",
            String.class
        ))
            if (db.jdbc.queryForObject("SELECT COUNT(*) FROM city WHERE name=?", Integer.class, c) == 0) {
                int sort = db.jdbc.queryForObject("SELECT COALESCE(MAX(sort),0)+1 FROM city", Integer.class);
                db.jdbc.update("INSERT INTO city(id,name,sort) VALUES(?,?,?)", db.id(), c, sort);
            }
    }

    // ===================== 我方签约主体 =====================

    public List<Map<String, Object>> companies() {
        return db.jdbc.queryForList(
            """
            SELECT c.*,
              (SELECT COUNT(*) FROM contract k WHERE k.company_id=c.id) AS contracts,
              (SELECT COUNT(*) FROM quotation q WHERE q.company_id=c.id) AS quotes
            FROM company c ORDER BY c.sort,c.name
            """
        );
    }

    public record CompanyInput(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 30) String shortName,
        @Size(max = 30) String taxNo,
        @Size(max = 60) String legalRep,
        @Size(max = 300) String address,
        @Size(max = 60) String phone,
        @Size(max = 200) String bankName,
        @Size(max = 40) @Pattern(
            regexp = "^$|[0-9\\- ]{6,40}",
            message = "银行账号只能包含数字、空格和短横线"
        ) String bankAccount,
        boolean isDefault,
        boolean active,
        int sort,
        @Size(max = 500) String note
    ) {}

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public String saveCompany(String id, @Valid CompanyInput c) {
        String name = c.name().trim();
        String shortName = blank(c.shortName());
        unique("company", name, id, "签约主体");
        if (
            shortName != null &&
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM company WHERE short_name=? AND id<>?",
                Integer.class,
                shortName,
                id == null ? "" : id
            ) >
            0
        ) throw new ApiException("简称“" + shortName + "”已被使用");
        if (c.isDefault() && !c.active()) throw new ApiException("默认签约主体不能停用");
        Object[] values = {
            name,
            shortName,
            TaxNumbers.normalize(c.taxNo()),
            blank(c.legalRep()),
            blank(c.address()),
            blank(c.phone()),
            blank(c.bankName()),
            blank(c.bankAccount()),
            c.isDefault(),
            c.active(),
            c.sort(),
            blank(c.note()),
        };
        if (c.isDefault()) db.jdbc.update("UPDATE company SET is_default=FALSE");
        if (id == null) {
            id = db.id();
            var args = new ArrayList<Object>(List.of(id));
            args.addAll(Arrays.asList(values));
            db.jdbc.update(
                """
                INSERT INTO company(id,name,short_name,tax_no,legal_rep,address,phone,bank_name,bank_account,is_default,active,sort,note)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                args.toArray()
            );
            db.audit("COMPANY_CREATED", id, name);
        } else {
            var old = db.one("SELECT * FROM company WHERE id=? FOR UPDATE", id);
            var args = new ArrayList<Object>(Arrays.asList(values));
            args.add(id);
            db.jdbc.update(
                """
                UPDATE company SET name=?,short_name=?,tax_no=?,legal_rep=?,address=?,phone=?,bank_name=?,bank_account=?,
                is_default=?,active=?,sort=?,note=? WHERE id=?
                """,
                args.toArray()
            );
            db.audit("COMPANY_UPDATED", id, db.text(old, "name") + " → " + name + (c.active() ? "" : "（停用）"));
        }
        if (db.jdbc.queryForObject("SELECT COUNT(*) FROM company WHERE is_default=TRUE", Integer.class) == 0) {
            throw new ApiException("至少需要一个默认签约主体");
        }
        return id;
    }

    /** 选择签约主体：为空用默认；停用的只允许保留原值。 */
    public String company(String input, String current) {
        String v = blank(input);
        if (v == null) return defaultCompanyId();
        var rows = db.jdbc.queryForList("SELECT id,active FROM company WHERE id=?", v);
        if (rows.isEmpty()) throw new ApiException("签约主体不存在");
        if (!Boolean.TRUE.equals(rows.get(0).get("active")) && !v.equals(current)) throw new ApiException(
            "该签约主体已停用"
        );
        return v;
    }

    /** 按名称或简称找签约主体（Excel 导入用）；找不到返回 null。 */
    public String companyByName(String name) {
        if (name == null || name.isBlank()) return null;
        var ids = db.jdbc.queryForList(
            "SELECT id FROM company WHERE name=? OR short_name=?",
            String.class,
            name.trim(),
            name.trim()
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public String defaultCompanyId() {
        var ids = db.jdbc.queryForList(
            "SELECT id FROM company WHERE is_default=TRUE AND active=TRUE ORDER BY sort",
            String.class
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Map<String, Object> companyRow(String id) {
        if (id == null) return null;
        var rows = db.jdbc.queryForList("SELECT * FROM company WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ===================== 工具 =====================

    private void unique(String table, String name, String id, String label) {
        if (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE name=? AND id<>?",
                Integer.class,
                name,
                id == null ? "" : id
            ) >
            0
        ) throw new ApiException(label + "“" + name + "”已存在");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
