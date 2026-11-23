package cn.tianshang.commerce.excel;

import static cn.tianshang.commerce.excel.ExcelService.*;
import static cn.tianshang.commerce.excel.Rows.*;

import cn.tianshang.commerce.business.BusinessService;
import cn.tianshang.commerce.dictionary.DictionaryService;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.excel.ExcelService.Dataset;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

/** 字典类资料的 Excel 导入导出：客户集团与业务线、应用场景、城市、签约主体。按名称匹配，写入复用字典服务（仅管理员）。 */
@Component
public class DictionaryExcel {

    private final BusinessService business;
    private final DictionaryService dictionary;
    private final Validator validator;

    public DictionaryExcel(BusinessService business, DictionaryService dictionary, Validator validator) {
        this.business = business;
        this.dictionary = dictionary;
        this.validator = validator;
    }

    // ===================== 列 =====================

    List<Column> columns(Dataset d) {
        return switch (d) {
            case BUSINESS_LINES -> List.of(
                Column.of("group", "客户集团", 24).required().note("如 美团；同一集团写多行，每行一条业务线"),
                Column.of("group_sort", "集团排序", 8).number(),
                Column.of("group_active", "集团启用", 8).options("是", "否"),
                Column.of("line", "业务线", 20).note("如 美团优选；留空表示只登记客户集团"),
                Column.of("short_name", "简称", 10).note("项目台账、项目类型里可以用简称填业务线"),
                Column.of("sort", "排序", 8).number(),
                Column.of("active", "启用", 8).options("是", "否"),
                Column.of("note", "备注", 30)
            );
            case SCENARIOS -> List.of(
                Column.of("name", "应用场景", 20).required().note("标准产品库按应用场景分类，如 冷柜、照明"),
                Column.of("sort", "排序", 8).number(),
                Column.of("active", "启用", 8).options("是", "否"),
                Column.of("note", "备注", 30)
            );
            case CITIES -> List.of(
                Column.of("name", "城市", 14).required(),
                Column.of("province", "省份", 12),
                Column.of("aliases", "别名", 30).note("从门店名称识别城市时也认别名；多个用顿号或逗号分开，如 沪、申"),
                Column.of("sort", "排序", 8).number(),
                Column.of("active", "启用", 8).options("是", "否")
            );
            case COMPANIES -> List.of(
                Column.of("name", "签约主体", 30).required().note("我方签合同、开票的公司全称"),
                Column.of("short_name", "简称", 12),
                Column.of("tax_no", "纳税人识别号", 22),
                Column.of("legal_rep", "法定代表人", 12),
                Column.of("address", "地址", 36),
                Column.of("phone", "电话", 16),
                Column.of("bank_name", "开户银行", 24),
                Column.of("bank_account", "银行账号", 26).note("只能包含数字、空格和短横线"),
                Column.of("is_default", "默认主体", 8)
                    .options("是", "否")
                    .note("新报价、新合同默认用的主体，只能有一家"),
                Column.of("active", "启用", 8).options("是", "否"),
                Column.of("sort", "排序", 8).number(),
                Column.of("note", "备注", 30)
            );
            default -> throw new IllegalArgumentException(d.name());
        };
    }

    // ===================== 导出 =====================

    List<Map<String, Object>> rows(Dataset d) {
        var out = new ArrayList<Map<String, Object>>();
        switch (d) {
            case BUSINESS_LINES -> {
                for (var g : business.list()) {
                    var base = new HashMap<String, Object>();
                    base.put("group", g.get("name"));
                    base.put("group_sort", g.get("sort"));
                    base.put("group_active", yesNo(g.get("active")));
                    @SuppressWarnings("unchecked")
                    var lines = (List<Map<String, Object>>) g.get("lines");
                    if (lines.isEmpty()) out.add(base);
                    for (var l : lines) {
                        var r = new HashMap<String, Object>(base);
                        r.put("line", l.get("name"));
                        r.put("short_name", l.get("short_name"));
                        r.put("sort", l.get("sort"));
                        r.put("active", yesNo(l.get("active")));
                        r.put("note", l.get("note"));
                        out.add(r);
                    }
                }
            }
            case SCENARIOS -> {
                for (var s : dictionary.scenarios()) out.add(flags(s, "active"));
            }
            case CITIES -> {
                for (var c : dictionary.cities()) out.add(flags(c, "active"));
            }
            case COMPANIES -> {
                for (var c : dictionary.companies()) out.add(flags(c, "active", "is_default"));
            }
            default -> throw new IllegalArgumentException(d.name());
        }
        return out;
    }

    private static Map<String, Object> flags(Map<String, Object> row, String... keys) {
        var r = new HashMap<String, Object>(row);
        for (String k : keys) r.put(k, yesNo(row.get(k)));
        return r;
    }

    private static String yesNo(Object v) {
        return Boolean.TRUE.equals(v) ? "是" : "否";
    }

    // ===================== 导入 =====================

    List<Op> plan(Dataset d, ExcelIo.Sheet sheet) {
        return switch (d) {
            case BUSINESS_LINES -> planBusinessLines(sheet);
            case SCENARIOS -> planScenarios(sheet);
            case CITIES -> planCities(sheet);
            case COMPANIES -> planCompanies(sheet);
            default -> throw new IllegalArgumentException(d.name());
        };
    }

    private List<Op> planBusinessLines(ExcelIo.Sheet sheet) {
        var groups = new HashMap<String, Map<String, Object>>();
        var lines = new HashMap<String, Map<String, Object>>();
        for (var g : business.list()) {
            groups.put(text(g, "name"), g);
            @SuppressWarnings("unchecked")
            var ls = (List<Map<String, Object>>) g.get("lines");
            for (var l : ls) lines.put(text(l, "name"), l);
        }
        var ops = new ArrayList<Op>();
        var plannedGroups = new HashSet<String>();
        var seenLines = new HashMap<String, Integer>();
        for (var row : sheet.rows()) {
            String groupName = row.get("group").trim();
            String where = at(row);
            if (groupName.isEmpty()) {
                ops.add(new Op(new Result(where, ERROR, row.get("line"), List.of("客户集团必填")), null));
                continue;
            }
            // 集团：同一集团只按第一行处理一次
            if (plannedGroups.add(groupName)) {
                var errors = new ArrayList<String>();
                var cur = groups.get(groupName);
                int sort = sort(sheet, row, "group_sort", cur, errors);
                boolean active = flag(sheet, row, "group_active", cur, "active", true);
                var input = new BusinessService.GroupInput(groupName, sort, active);
                validate(validator, input, errors, Map.of("name", "客户集团"));
                String label = "客户集团 " + groupName;
                if (!errors.isEmpty()) ops.add(new Op(new Result(where, ERROR, label, errors), null));
                else if (cur == null) ops.add(
                    new Op(new Result(where, CREATE, label, List.of()), () -> business.saveGroup(null, input))
                );
                else if (
                    sort == ((Number) cur.get("sort")).intValue() && active == Boolean.TRUE.equals(cur.get("active"))
                ) ops.add(new Op(new Result(where, SAME, label, List.of()), null));
                else {
                    String id = cur.get("id").toString();
                    ops.add(
                        new Op(new Result(where, UPDATE, label, List.of("变更：排序 / 启用")), () ->
                            business.saveGroup(id, input)
                        )
                    );
                }
            }
            String lineName = row.get("line").trim();
            if (lineName.isEmpty()) continue;
            var errors = new ArrayList<String>();
            if (seenLines.put(lineName, row.number()) != null) errors.add(
                "与第 " + seenLines.get(lineName) + " 行的业务线重复"
            );
            var cur = lines.get(lineName);
            String shortName = blank(value(sheet, row, "short_name", cur, "short_name"));
            String note = blank(value(sheet, row, "note", cur, "note"));
            int sort = sort(sheet, row, "sort", cur, errors);
            boolean active = flag(sheet, row, "active", cur, "active", true);
            var existingGroup = groups.get(groupName);
            String groupIdForCheck = existingGroup == null ? "(新集团)" : existingGroup.get("id").toString();
            var check = new BusinessService.LineInput(groupIdForCheck, lineName, shortName, sort, active, note);
            validate(validator, check, errors, Map.of("name", "业务线", "shortName", "简称", "note", "备注"));
            String label = groupName + " / " + lineName;
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(where, ERROR, label, errors), null));
                continue;
            }
            // 集团可能是这份文件里新建的，写入时再按名称取编号
            java.util.function.Supplier<BusinessService.LineInput> input = () ->
                new BusinessService.LineInput(groupId(groupName), lineName, shortName, sort, active, note);
            if (cur == null) {
                ops.add(
                    new Op(new Result(where, CREATE, label, List.of()), () -> business.saveLine(null, input.get()))
                );
                continue;
            }
            var changed = new ArrayList<String>();
            if (existingGroup == null || !existingGroup.get("id").equals(cur.get("group_id"))) changed.add("所属集团");
            if (!Objects.equals(shortName, blank(text(cur, "short_name")))) changed.add("简称");
            if (sort != ((Number) cur.get("sort")).intValue()) changed.add("排序");
            if (active != Boolean.TRUE.equals(cur.get("active"))) changed.add("启用");
            if (!Objects.equals(note, blank(text(cur, "note")))) changed.add("备注");
            String id = cur.get("id").toString();
            ops.add(
                changed.isEmpty()
                    ? new Op(new Result(where, SAME, label, List.of()), null)
                    : new Op(new Result(where, UPDATE, label, List.of("变更：" + String.join("、", changed))), () ->
                          business.saveLine(id, input.get())
                      )
            );
        }
        return ops;
    }

    private String groupId(String name) {
        for (var g : business.list()) if (text(g, "name").equals(name)) return g.get("id").toString();
        throw new cn.tianshang.commerce.common.ApiException("客户集团“" + name + "”不存在");
    }

    private List<Op> planScenarios(ExcelIo.Sheet sheet) {
        var existing = index(dictionary.scenarios(), s -> text(s, "name"));
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            String name = row.get("name").trim();
            var errors = new ArrayList<String>();
            if (name.isEmpty()) errors.add("应用场景必填");
            else if (seen.put(name, row.number()) != null) errors.add("与第 " + seen.get(name) + " 行重复");
            var cur = existing.get(name);
            int sort = sort(sheet, row, "sort", cur, errors);
            boolean active = flag(sheet, row, "active", cur, "active", true);
            String note = blank(value(sheet, row, "note", cur, "note"));
            var input = new DictionaryService.ScenarioInput(name, sort, active, note);
            validate(validator, input, errors, Map.of("name", "应用场景", "note", "备注"));
            ops.add(
                simple(at(row), "应用场景 " + name, errors, cur, changes(cur, sort, active, field("note", note)), () ->
                    dictionary.saveScenario(cur == null ? null : cur.get("id").toString(), input)
                )
            );
        }
        return ops;
    }

    private List<Op> planCities(ExcelIo.Sheet sheet) {
        var existing = index(dictionary.cities(), c -> text(c, "name"));
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            String name = row.get("name").trim();
            var errors = new ArrayList<String>();
            if (name.isEmpty()) errors.add("城市必填");
            else if (seen.put(name, row.number()) != null) errors.add("与第 " + seen.get(name) + " 行重复");
            var cur = existing.get(name);
            int sort = sort(sheet, row, "sort", cur, errors);
            boolean active = flag(sheet, row, "active", cur, "active", true);
            String province = blank(value(sheet, row, "province", cur, "province"));
            String aliases = blank(value(sheet, row, "aliases", cur, "aliases"));
            var input = new DictionaryService.CityInput(name, province, aliases, sort, active);
            validate(validator, input, errors, Map.of("name", "城市", "province", "省份", "aliases", "别名"));
            var fields = new LinkedHashMap<String, String>();
            fields.put("province", province);
            fields.put("aliases", aliases);
            ops.add(
                simple(at(row), "城市 " + name, errors, cur, changes(cur, sort, active, fields), () ->
                    dictionary.saveCity(cur == null ? null : cur.get("id").toString(), input)
                )
            );
        }
        return ops;
    }

    private List<Op> planCompanies(ExcelIo.Sheet sheet) {
        var existing = index(dictionary.companies(), c -> text(c, "name"));
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        int defaults = 0;
        for (var row : sheet.rows()) {
            String name = row.get("name").trim();
            var errors = new ArrayList<String>();
            if (name.isEmpty()) errors.add("签约主体必填");
            else if (seen.put(name, row.number()) != null) errors.add("与第 " + seen.get(name) + " 行重复");
            var cur = existing.get(name);
            int sort = sort(sheet, row, "sort", cur, errors);
            boolean active = flag(sheet, row, "active", cur, "active", true);
            boolean isDefault = flag(sheet, row, "is_default", cur, "is_default", false);
            if (isDefault && ++defaults > 1) errors.add("只能有一家默认主体");
            var fields = new LinkedHashMap<String, String>();
            for (String k : List.of(
                "short_name",
                "tax_no",
                "legal_rep",
                "address",
                "phone",
                "bank_name",
                "bank_account",
                "note"
            ))
                fields.put(k, blank(value(sheet, row, k, cur, k)));
            var input = new DictionaryService.CompanyInput(
                name,
                fields.get("short_name"),
                fields.get("tax_no"),
                fields.get("legal_rep"),
                fields.get("address"),
                fields.get("phone"),
                fields.get("bank_name"),
                fields.get("bank_account"),
                isDefault,
                active,
                sort,
                fields.get("note")
            );
            validate(
                validator,
                input,
                errors,
                Map.of("name", "签约主体", "shortName", "简称", "taxNo", "纳税人识别号", "bankAccount", "银行账号")
            );
            var changed = changes(cur, sort, active, fields);
            if (cur != null && isDefault != Boolean.TRUE.equals(cur.get("is_default"))) changed.add("默认主体");
            ops.add(
                simple(at(row), "签约主体 " + name, errors, cur, changed, () ->
                    dictionary.saveCompany(cur == null ? null : cur.get("id").toString(), input)
                )
            );
        }
        return ops;
    }

    private static final Map<String, String> FIELD_NAMES = Map.ofEntries(
        Map.entry("note", "备注"),
        Map.entry("province", "省份"),
        Map.entry("aliases", "别名"),
        Map.entry("short_name", "简称"),
        Map.entry("tax_no", "纳税人识别号"),
        Map.entry("legal_rep", "法定代表人"),
        Map.entry("address", "地址"),
        Map.entry("phone", "电话"),
        Map.entry("bank_name", "开户银行"),
        Map.entry("bank_account", "银行账号")
    );

    /** 单个字段的比较项（值可以为空，Map.of 不允许空值）。 */
    private static Map<String, String> field(String key, String value) {
        var m = new LinkedHashMap<String, String>();
        m.put(key, value);
        return m;
    }

    /** 与现有记录比较排序、启用和若干文本字段，返回变更的字段名。 */
    private static List<String> changes(Map<String, Object> cur, int sort, boolean active, Map<String, String> fields) {
        var changed = new ArrayList<String>();
        if (cur == null) return changed;
        if (sort != ((Number) cur.get("sort")).intValue()) changed.add("排序");
        if (active != Boolean.TRUE.equals(cur.get("active"))) changed.add("启用");
        for (var e : fields.entrySet())
            if (!Objects.equals(e.getValue(), blank(text(cur, e.getKey())))) changed.add(
                FIELD_NAMES.getOrDefault(e.getKey(), e.getKey())
            );
        return changed;
    }

    private static Op simple(
        String where,
        String label,
        List<String> errors,
        Map<String, Object> cur,
        List<String> changed,
        Runnable apply
    ) {
        if (!errors.isEmpty()) return new Op(new Result(where, ERROR, label, errors), null);
        if (cur == null) return new Op(new Result(where, CREATE, label, List.of()), apply);
        if (changed.isEmpty()) return new Op(new Result(where, SAME, label, List.of()), null);
        return new Op(new Result(where, UPDATE, label, List.of("变更：" + String.join("、", changed))), apply);
    }
}
