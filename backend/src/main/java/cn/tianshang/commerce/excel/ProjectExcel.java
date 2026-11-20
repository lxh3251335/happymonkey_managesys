package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.business.BusinessService;
import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.dictionary.DictionaryService;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.excel.ExcelService.Op;
import cn.tianshang.commerce.excel.ExcelService.Result;
import cn.tianshang.commerce.project.ProjectService;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 项目台账 Excel 导入导出（导入限管理员）。
 *
 * <p>有“系统编号”按编号更新，否则按“客户 + 门店名称”匹配，匹配不到则新增。表中没有的列保持原值。
 * 写入复用 {@link ProjectService}，校验（业务线、城市字典、合同规则）与审计与页面一致。
 */
@Component
public class ProjectExcel {

    private static final String ID_NOTE = "导出时自带，用于按编号更新；新增时留空，请勿修改";
    private static final String STATS = "仅供查看，导入时忽略";
    private static final String[][] FIELDS = {
        { "name", "门店名称" },
        { "brand", "业务线" },
        { "city", "城市" },
        { "type", "项目类型" },
        { "owner", "负责人" },
        { "order_date", "下单日期" },
        { "contract_no", "合同号" },
        { "external_id", "云智冷项目ID" },
        { "note", "备注" },
        { "status", "发货状态" },
        { "expected_ship_on", "预计发货" },
        { "shipped_on", "发货日期" },
        { "courier", "快递公司" },
        { "tracking_no", "运单号" },
        { "consignee", "收货人" },
        { "consignee_phone", "收货电话" },
        { "ship_address", "收货地址" },
    };

    private final Database db;
    private final ProjectService projects;
    private final BusinessService business;
    private final DictionaryService dictionary;
    private final Validator validator;

    public ProjectExcel(
        Database db,
        ProjectService projects,
        BusinessService business,
        DictionaryService dictionary,
        Validator validator
    ) {
        this.db = db;
        this.projects = projects;
        this.business = business;
        this.dictionary = dictionary;
        this.validator = validator;
    }

    List<Column> columns() {
        var lines = new ArrayList<>(business.activeLineNames());
        lines.add(BusinessService.UNASSIGNED);
        return List.of(
            Column.of("id", "系统编号", 38).note(ID_NOTE),
            Column.of("code", "项目编号", 18).note(STATS),
            Column.of("name", "门店名称", 30).required().note("同一客户下门店名称不能重复"),
            Column.of("customer", "客户", 16).required().note("填写已登记的客户名称；已有项目不能更换客户"),
            Column.of("brand", "业务线", 12)
                .required()
                .note("在系统管理 → 数据字典中维护；可填名称或简称")
                .options(lines.toArray(String[]::new)),
            Column.of("city", "城市", 10).note("须在城市字典中，可填区县别名；留空按门店名称识别"),
            Column.of("type", "项目类型", 14).note("填写已有项目类型名称"),
            Column.of("owner", "负责人", 10).note("填写账户显示名称；留空为导入人"),
            Column.of("order_date", "下单日期", 12),
            Column.of("contract_no", "合同号", 18),
            Column.of("status", "发货状态", 10).options("制作中", "已发货").note("填了运单号即视为已发货"),
            Column.of("expected_ship_on", "预计发货", 12),
            Column.of("shipped_on", "发货日期", 12),
            Column.of("courier", "快递公司", 12).options(ProjectService.COURIERS.toArray(String[]::new)),
            Column.of("tracking_no", "运单号", 20),
            Column.of("consignee", "收货人", 10),
            Column.of("consignee_phone", "收货电话", 16),
            Column.of("ship_address", "收货地址", 40),
            Column.of("note", "备注", 24),
            Column.of("quote_count", "报价数", 8).number().note(STATS)
        );
    }

    List<Map<String, Object>> rows() {
        var out = new ArrayList<Map<String, Object>>();
        for (var p : projects.list()) {
            var r = new HashMap<String, Object>(p);
            r.put("customer", p.get("customer_name"));
            r.put("type", p.get("project_type_name"));
            r.put("owner", p.get("owner_name"));
            r.put("status", p.get("shipping_status"));
            for (String k : List.of("order_date", "expected_ship_on", "shipped_on")) r.put(k, day(p.get(k)));
            out.add(r);
        }
        return out;
    }

    List<Op> plan(ExcelIo.Sheet sheet) {
        var existing = projects.list();
        var byId = new HashMap<String, Map<String, Object>>();
        var byKey = new HashMap<String, Map<String, Object>>();
        for (var p : existing) {
            byId.put(p.get("id").toString(), p);
            byKey.putIfAbsent(p.get("customer_id") + "::" + db.text(p, "name").trim(), p);
        }
        var customers = new HashMap<String, String>();
        for (var c : db.jdbc.queryForList("SELECT id,name FROM partner WHERE kind='CUSTOMER'"))
            customers.put(db.text(c, "name").trim(), c.get("id").toString());
        var types = new HashMap<String, String>();
        for (var t : db.jdbc.queryForList("SELECT id,name FROM project_type"))
            types.put(db.text(t, "name").trim(), t.get("id").toString());
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            var errors = new ArrayList<String>();
            String rows = "第 " + row.number() + " 行";
            String id = row.get("id");
            Map<String, Object> current = null;
            if (!id.isEmpty()) {
                current = byId.get(id);
                if (current == null) errors.add("系统编号不存在或无权修改");
            }
            String customerName = value(sheet, row, "customer", current, "customer_name");
            String customerId = customers.get(customerName);
            if (customerName.isEmpty()) errors.add("客户必填");
            else if (customerId == null) errors.add("客户“" + customerName + "”未登记");
            String name = value(sheet, row, "name", current, "name");
            if (name.isEmpty()) errors.add("门店名称必填");
            if (current == null && customerId != null) current = byKey.get(customerId + "::" + name);
            if (current != null && customerId != null && !customerId.equals(current.get("customer_id"))) errors.add(
                "已有项目不能更换客户"
            );
            String key = customerId + "::" + name;
            if (customerId != null && !name.isEmpty() && seen.put(key, row.number()) != null) errors.add(
                "与第 " + seen.get(key) + " 行是同一门店"
            );
            String label =
                (customerName.isEmpty() ? "" : customerName + " / ") + (name.isEmpty() ? "（未填门店）" : name);
            ProjectService.ProjectInput input = null;
            try {
                input = input(sheet, row, current, customerId, types);
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            if (input != null) for (var v : validator.validate(input))
                errors.add(v.getPropertyPath() + "：" + v.getMessage());
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ExcelService.ERROR, label, errors), null));
                continue;
            }
            final var in = input;
            if (current == null) {
                ops.add(new Op(new Result(rows, ExcelService.CREATE, label, List.of()), () -> projects.create(in)));
                continue;
            }
            var changed = new ArrayList<String>();
            var next = resolved(in);
            for (var f : FIELDS)
                if (
                    sheet.present().contains(f[0]) &&
                    !Objects.equals(norm(next.get(f[0])), norm(currentValue(current, f[0])))
                ) changed.add(f[1]);
            String pid = current.get("id").toString();
            ops.add(
                changed.isEmpty()
                    ? new Op(new Result(rows, ExcelService.SAME, label, List.of()), null)
                    : new Op(
                          new Result(rows, ExcelService.UPDATE, label, List.of("变更：" + String.join("、", changed))),
                          () -> projects.update(pid, in)
                      )
            );
        }
        return ops;
    }

    /** 表格行 + 当前值 → 项目输入；字典、日期、负责人在这里校验。 */
    private ProjectService.ProjectInput input(
        ExcelIo.Sheet sheet,
        Row row,
        Map<String, Object> cur,
        String customerId,
        Map<String, String> types
    ) {
        String brand = business.resolve(
            value(sheet, row, "brand", cur, "brand"),
            cur == null ? null : db.text(cur, "brand")
        );
        String city = dictionary.city(
            value(sheet, row, "city", cur, "city"),
            cur == null ? null : (String) cur.get("city")
        );
        String typeName = value(sheet, row, "type", cur, "project_type_name");
        String typeId = null;
        if (!typeName.isEmpty()) {
            typeId = types.get(typeName);
            if (typeId == null) throw new ApiException("项目类型“" + typeName + "”不存在");
        }
        String ownerId;
        if (sheet.present().contains("owner")) ownerId = row.get("owner").isEmpty()
            ? ""
            : projects.ownerByName(row.get("owner"));
        else ownerId = cur == null ? null : Objects.toString(cur.get("owner_id"), "");
        var delivery = new ProjectService.DeliveryInput(
            value(sheet, row, "status", cur, "shipping_status"),
            ExcelIo.date(value(sheet, row, "expected_ship_on", cur, "expected_ship_on"), "预计发货"),
            ExcelIo.date(value(sheet, row, "shipped_on", cur, "shipped_on"), "发货日期"),
            value(sheet, row, "courier", cur, "courier"),
            value(sheet, row, "tracking_no", cur, "tracking_no").replaceAll("\\s", ""),
            value(sheet, row, "consignee", cur, "consignee"),
            value(sheet, row, "consignee_phone", cur, "consignee_phone"),
            value(sheet, row, "ship_address", cur, "ship_address")
        );
        LocalDate orderDate = ExcelIo.date(value(sheet, row, "order_date", cur, "order_date"), "下单日期");
        return new ProjectService.ProjectInput(
            value(sheet, row, "name", cur, "name"),
            brand,
            customerId,
            value(sheet, row, "external_id", cur, "external_id"),
            orderDate,
            value(sheet, row, "note", cur, "note"),
            value(sheet, row, "contract_no", cur, "contract_no"),
            city,
            typeId,
            ownerId,
            delivery
        );
    }

    /** 列在表中取表中值，否则取当前值（新增时为空）。 */
    private String value(ExcelIo.Sheet sheet, Row row, String key, Map<String, Object> cur, String column) {
        if (sheet.present().contains(key)) return row.get(key);
        return cur == null ? "" : Objects.toString(day(cur.get(column)), "");
    }

    /** 当前值：类型、负责人按 ID 比较，其余按库中值。 */
    private static Object currentValue(Map<String, Object> cur, String key) {
        return switch (key) {
            case "type" -> cur.get("project_type_id");
            case "owner" -> cur.get("owner_id");
            case "status" -> cur.get("shipping_status");
            default -> day(cur.get(key));
        };
    }

    /**
     * 导入后将保存的值（与 {@link ProjectService} 规则一致）：业务线、城市已换成字典名称，
     * 有运单号即已发货，已发货未填发货日期则沿用原日期，用于判断是否有变化。
     */
    private static Map<String, Object> resolved(ProjectService.ProjectInput in) {
        var d = in.delivery();
        var m = new HashMap<String, Object>();
        m.put("name", in.name());
        m.put("brand", in.brand());
        m.put("city", in.city());
        m.put("type", in.projectTypeId());
        m.put("owner", in.ownerId());
        m.put("order_date", in.orderDate());
        m.put("contract_no", in.contractNo());
        m.put("external_id", in.externalId());
        m.put("note", in.note());
        boolean tracking = d.trackingNo() != null && !d.trackingNo().isBlank();
        m.put("status", tracking ? "已发货" : d.status());
        m.put("expected_ship_on", d.expectedShipOn());
        m.put("shipped_on", d.shippedOn());
        m.put("courier", d.courier());
        m.put("tracking_no", d.trackingNo());
        m.put("consignee", d.consignee());
        m.put("consignee_phone", d.consigneePhone());
        m.put("ship_address", d.shipAddress());
        return m;
    }

    private static String norm(Object v) {
        String s = v == null ? "" : v.toString().trim();
        if (s.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) try {
            return ExcelIo.date(s, "").toString();
        } catch (ApiException ignored) {}
        return s;
    }

    private static Object day(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.matches("\\d{4}-\\d{2}-\\d{2}.*") ? s.substring(0, 10) : v;
    }
}
