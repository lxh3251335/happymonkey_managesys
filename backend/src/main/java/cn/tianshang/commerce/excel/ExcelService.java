package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.catalog.CatalogService;
import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.excel.ExcelIo.Column;
import cn.tianshang.commerce.excel.ExcelIo.Row;
import cn.tianshang.commerce.master.MasterService;
import cn.tianshang.commerce.master.TaxNumbers;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 基础资料 Excel 导入导出：客户、供应商、标准产品、项目类型、供应商成本模板。
 *
 * <p>导入先预览（逐行给出新增 / 更新 / 无变化 / 错误），确认后整批写入：任一行出错则整批不写入。
 * 有“系统编号”时按编号更新，否则按名称（产品按 应用场景+名称+规格+型号）匹配。表中没有的列保持原值。
 * 写入复用各业务服务，权限、校验和操作审计与页面录入一致。
 */
@Service
public class ExcelService {

    public enum Dataset {
        CUSTOMERS("customers", "客户资料"),
        SUPPLIERS("suppliers", "供应商资料"),
        PRODUCTS("products", "标准产品库"),
        PROJECT_TYPES("project-types", "项目类型"),
        SUPPLIER_TEMPLATES("supplier-templates", "供应商成本模板"),
        CONTRACTS("contracts", "合同台账"),
        CONTRACT_INVOICES("contract-invoices", "开票记录");

        public final String path;
        public final String title;

        Dataset(String path, String title) {
            this.path = path;
            this.title = title;
        }

        public static Dataset of(String path) {
            for (var d : values()) if (d.path.equals(path)) return d;
            throw new ApiException("不支持的数据类型：" + path);
        }
    }

    public record Result(String rows, String action, String label, List<String> messages) {}

    public record Report(String dataset, int created, int updated, int unchanged, int errors, List<Result> results) {}

    /** 一行（或一组行）的计划：预览结果 + 确认后的写入动作。 */
    record Op(Result result, Runnable apply) {}

    static final String CREATE = "CREATE",
        UPDATE = "UPDATE",
        SAME = "UNCHANGED",
        ERROR = "ERROR";
    private static final String ID_NOTE = "导出时自带，用于按编号更新；新增时留空，请勿修改";
    private static final String EXPORT_ONLY = "仅供查看，导入时忽略";

    private final Database db;
    private final MasterService masters;
    private final CatalogService catalog;
    private final Validator validator;
    private final cn.tianshang.commerce.business.BusinessService business;
    private final cn.tianshang.commerce.dictionary.DictionaryService dictionary;
    private final ContractExcel contractExcel;

    public ExcelService(
        Database db,
        MasterService masters,
        CatalogService catalog,
        Validator validator,
        cn.tianshang.commerce.business.BusinessService business,
        cn.tianshang.commerce.dictionary.DictionaryService dictionary,
        ContractExcel contractExcel
    ) {
        this.db = db;
        this.masters = masters;
        this.catalog = catalog;
        this.validator = validator;
        this.business = business;
        this.dictionary = dictionary;
        this.contractExcel = contractExcel;
    }

    /** 业务线下拉选项：启用的业务线 + “未指定”。 */
    private String[] lineOptions() {
        var names = new ArrayList<>(business.activeLineNames());
        names.add(cn.tianshang.commerce.business.BusinessService.UNASSIGNED);
        return names.toArray(String[]::new);
    }

    // ===================== 列定义 =====================

    List<Column> columns(Dataset d) {
        return switch (d) {
            case CONTRACTS -> contractExcel.contractColumns();
            case CONTRACT_INVOICES -> contractExcel.invoiceColumns();
            case CUSTOMERS, SUPPLIERS -> List.of(
                Column.of("id", "系统编号", 38).note(ID_NOTE),
                Column.of("name", "名称", 28).required().note("同类资料中名称不能重复"),
                Column.of("contact", "联系人", 12),
                Column.of("phone", "联系电话", 16),
                Column.of("invoice_title", "开票抬头", 30).note("留空时开票抬头同名称"),
                Column.of("tax_no", "纳税人识别号", 22).note("18 位统一社会信用代码会核对校验位"),
                Column.of("address", "注册地址", 36),
                Column.of("invoice_phone", "开票电话", 16),
                Column.of("bank_name", "开户银行", 24),
                Column.of("bank_account", "银行账号", 26).note("只能包含数字、空格和短横线"),
                Column.of("invoice_type", "发票类型", 10).options("专票", "普票"),
                Column.of("note", "备注", 30)
            );
            case PRODUCTS -> List.of(
                Column.of("id", "系统编号", 38).note(ID_NOTE),
                Column.of("scenario", "应用场景", 14)
                    .required()
                    .note("在系统管理 → 数据字典 → 应用场景中维护")
                    .options(dictionary.activeScenarioNames().toArray(String[]::new)),
                Column.of("name", "产品名称", 24).required(),
                Column.of("specification", "规格", 18).note("软件、服务类可留空"),
                Column.of("model", "型号", 14).note("应用场景 + 名称 + 规格 + 型号 不能重复"),
                Column.of("unit", "单位", 8).note("留空为“台”"),
                Column.of("supplier", "供应商", 20).note("填写已登记的供应商名称"),
                Column.of("mainstream", "主流配置", 10).options("是", "否"),
                Column.of("drawings", "图纸数", 8).number().note(EXPORT_ONLY),
                Column.of("reference_price", "历史售价参考", 14).number().note(EXPORT_ONLY)
            );
            case PROJECT_TYPES -> List.of(
                Column.of("type", "项目类型", 16)
                    .required()
                    .note("同一项目类型写多行，每行一个产品；导入时以文件中的配置替换该类型的全部配置"),
                Column.of("brand", "业务线", 12)
                    .required()
                    .note("在系统管理 → 业务字典中维护；可填业务线名称或简称")
                    .options(lineOptions()),
                Column.of("active", "启用", 8).options("是", "否"),
                Column.of("type_note", "类型备注", 20),
                Column.of("scenario", "产品应用场景", 14).note("与标准产品库一致，用于定位产品"),
                Column.of("name", "产品名称", 22),
                Column.of("specification", "规格", 16),
                Column.of("model", "型号", 12),
                Column.of("quantity", "数量", 8).number(),
                Column.of("unit_price", "标准售价", 12).number().note("对客标准售价（元），可留空")
            );
            case SUPPLIER_TEMPLATES -> List.of(
                Column.of("supplier", "供应商", 20).required(),
                Column.of("type", "项目类型", 16)
                    .required()
                    .note("同一供应商 + 项目类型写多行；导入时以文件中的成本替换该模板的全部成本"),
                Column.of("active", "启用", 8).options("是", "否"),
                Column.of("template_note", "模板备注", 20),
                Column.of("scenario", "产品应用场景", 14),
                Column.of("name", "产品名称", 22).required(),
                Column.of("specification", "规格", 16),
                Column.of("model", "型号", 12),
                Column.of("material_cost", "材料成本", 12).number(),
                Column.of("labor_cost", "团队人工", 12).number(),
                Column.of("finance_cost", "财务成本", 12).number(),
                Column.of("unit_cost", "单台成本", 12).number().note(EXPORT_ONLY + "；= 材料 + 人工 + 财务")
            );
        };
    }

    // ===================== 导出 =====================

    public byte[] export(Dataset d, boolean template) {
        List<Map<String, Object>> rows = template ? List.of() : exportRows(d);
        var notes =
            d == Dataset.CONTRACTS
                ? List.of(
                      "合同台账 导入说明",
                      "1. 按“客户 + 合同号”匹配：已有合同更新合同要素，没有的新建合同（合同号以 # 开头的是未填合同号的单店订单）。",
                      "2. 门店数、订单金额、开票、到款、成本、毛利等统计列只导出、不导入；开票请用“开票记录”导入，到款在系统中登记。",
                      "3. 导入由管理员执行，每份合同的修改都会记入合同变更记录（原因：Excel 批量导入）。",
                      "4. 删除不需要更新的列即可保持原值；有任何错误时整批不写入。"
                  )
                : d == Dataset.CONTRACT_INVOICES
                    ? List.of(
                          "开票记录 导入说明",
                          "1. 每行一张发票；按发票号码匹配，已登记的发票跳过，不能通过导入修改。",
                          "2. 红字发票填写“原发票号码”，金额填正数；同一批次内可先蓝字后红字。",
                          "3. 开票合计不能超过合同金额；有任何错误时整批不写入。",
                          "4. 发票号码请保持文本格式，避免 Excel 改成科学计数法。"
                      )
                    : List.of(
                          d.title + " 导入说明",
                          "1. 第一张表为数据表，第一行是表头；列名带 * 的为必填。可删除不需要更新的列，缺少的列保持原值。",
                          "2. 有“系统编号”的行按编号更新；没有编号的按名称匹配，匹配不到则新增。",
                          "3. 导入前系统会逐行预览新增 / 更新 / 错误；有任何错误时整批不写入。",
                          "4. 税号、银行账号等长数字请保持文本格式，避免 Excel 改成科学计数法。"
                      );
        db.audit("EXCEL_EXPORT", d.path, d.title + (template ? " 模板" : " " + rows.size() + " 行"));
        return ExcelIo.write(d.title, columns(d), rows, notes);
    }

    private List<Map<String, Object>> exportRows(Dataset d) {
        return switch (d) {
            case CONTRACTS -> contractExcel.contractRows();
            case CONTRACT_INVOICES -> contractExcel.invoiceRows();
            case CUSTOMERS, SUPPLIERS -> {
                String kind = d == Dataset.CUSTOMERS ? "CUSTOMER" : "SUPPLIER";
                var out = new ArrayList<Map<String, Object>>();
                for (var p : masters.partners()) {
                    if (!kind.equals(p.get("kind"))) continue;
                    var r = new HashMap<String, Object>(p);
                    r.put("invoice_type", invoiceTypeName(db.text(p, "invoice_type")));
                    out.add(r);
                }
                yield out;
            }
            case PRODUCTS -> masters
                .products()
                .stream()
                .map(p -> {
                    var r = new HashMap<String, Object>(p);
                    r.put("supplier", p.get("supplier_name"));
                    r.put("mainstream", Boolean.TRUE.equals(p.get("mainstream")) ? "是" : "否");
                    return (Map<String, Object>) r;
                })
                .toList();
            case PROJECT_TYPES -> {
                var out = new ArrayList<Map<String, Object>>();
                for (var t : catalog.types()) {
                    @SuppressWarnings("unchecked")
                    var items = (List<Map<String, Object>>) t.get("items");
                    var base = new HashMap<String, Object>();
                    base.put("type", t.get("name"));
                    base.put("brand", t.get("brand"));
                    base.put("active", Boolean.TRUE.equals(t.get("active")) ? "是" : "否");
                    base.put("type_note", t.get("note"));
                    if (items.isEmpty()) out.add(base);
                    for (var i : items) {
                        var r = new HashMap<String, Object>(base);
                        r.put("scenario", i.get("product_scenario"));
                        r.put("name", i.get("name"));
                        r.put("specification", i.get("specification"));
                        r.put("model", i.get("model"));
                        r.put("quantity", i.get("quantity"));
                        r.put("unit_price", i.get("unit_price"));
                        out.add(r);
                    }
                }
                yield out;
            }
            case SUPPLIER_TEMPLATES -> {
                var scenarios = new HashMap<String, String>();
                for (var p : db.jdbc.queryForList("SELECT id,scenario,model FROM product"))
                    scenarios.put(p.get("id").toString(), db.text(p, "scenario") + "\u0000" + db.text(p, "model"));
                var out = new ArrayList<Map<String, Object>>();
                for (var t : catalog.supplierTemplates()) {
                    @SuppressWarnings("unchecked")
                    var items = (List<Map<String, Object>>) t.get("items");
                    for (var i : items) {
                        var sm = scenarios.getOrDefault(i.get("product_id").toString(), "\u0000").split("\u0000", -1);
                        var r = new HashMap<String, Object>();
                        r.put("supplier", t.get("supplier_name"));
                        r.put("type", t.get("project_type_name"));
                        r.put("active", Boolean.TRUE.equals(t.get("active")) ? "是" : "否");
                        r.put("template_note", t.get("note"));
                        r.put("scenario", sm[0]);
                        r.put("name", i.get("name"));
                        r.put("specification", i.get("specification"));
                        r.put("model", sm.length > 1 ? sm[1] : "");
                        r.put("material_cost", i.get("material_cost"));
                        r.put("labor_cost", i.get("labor_cost"));
                        r.put("finance_cost", i.get("finance_cost"));
                        r.put("unit_cost", i.get("unit_cost"));
                        out.add(r);
                    }
                }
                yield out;
            }
        };
    }

    // ===================== 导入 =====================

    public Report preview(Dataset d, MultipartFile file) {
        requireImportRole(d);
        return report(d, plan(d, ExcelIo.read(file, columns(d))));
    }

    @Transactional
    public Report importFile(Dataset d, MultipartFile file) {
        requireImportRole(d);
        var ops = plan(d, ExcelIo.read(file, columns(d)));
        var report = report(d, ops);
        if (report.errors() > 0) throw new ApiException(
            "有 " + report.errors() + " 处错误，整批未导入。请先在预览中修正"
        );
        for (var op : ops) {
            if (op.apply() == null) continue;
            try {
                op.apply().run();
            } catch (ApiException e) {
                throw new ApiException(op.result().rows() + "：" + e.getMessage());
            }
        }
        db.audit(
            "EXCEL_IMPORT",
            d.path,
            d.title + "：新增 " + report.created() + "，更新 " + report.updated() + "，无变化 " + report.unchanged()
        );
        return report;
    }

    private static Report report(Dataset d, List<Op> ops) {
        var results = ops.stream().map(Op::result).toList();
        int c = 0,
            u = 0,
            s = 0,
            e = 0;
        for (var r : results)
            switch (r.action()) {
                case CREATE -> c++;
                case UPDATE -> u++;
                case SAME -> s++;
                default -> e++;
            }
        return new Report(d.path, c, u, s, e, results);
    }

    private void requireImportRole(Dataset d) {
        var roles = SecurityContextHolder.getContext()
            .getAuthentication()
            .getAuthorities()
            .stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toSet());
        var allowed = switch (d) {
            case SUPPLIER_TEMPLATES, CONTRACT_INVOICES -> List.of("ROLE_ADMIN", "ROLE_FINANCE");
            case CONTRACTS -> List.of("ROLE_ADMIN");
            default -> List.of("ROLE_ADMIN", "ROLE_SALES");
        };
        if (allowed.stream().noneMatch(roles::contains)) throw new AccessDeniedException("当前角色不能导入" + d.title);
    }

    private List<Op> plan(Dataset d, ExcelIo.Sheet sheet) {
        if (sheet.rows().isEmpty()) throw new ApiException("Excel 中没有数据行");
        return switch (d) {
            case CUSTOMERS -> planPartners(sheet, "CUSTOMER");
            case SUPPLIERS -> planPartners(sheet, "SUPPLIER");
            case PRODUCTS -> planProducts(sheet);
            case PROJECT_TYPES -> planTypes(sheet);
            case SUPPLIER_TEMPLATES -> planSupplierTemplates(sheet);
            case CONTRACTS -> contractExcel.planContracts(sheet);
            case CONTRACT_INVOICES -> contractExcel.planInvoices(sheet);
        };
    }

    // ---------- 客户 / 供应商 ----------

    private List<Op> planPartners(ExcelIo.Sheet sheet, String kind) {
        var existing = masters
            .partners()
            .stream()
            .filter(p -> kind.equals(p.get("kind")))
            .toList();
        var byId = index(existing, p -> p.get("id").toString());
        var byName = index(existing, p -> db.text(p, "name").trim());
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        String[][] fields = {
            { "contact", "contact" },
            { "phone", "phone" },
            { "note", "note" },
            { "invoice_title", "invoice_title" },
            { "tax_no", "tax_no" },
            { "address", "address" },
            { "invoice_phone", "invoice_phone" },
            { "bank_name", "bank_name" },
            { "bank_account", "bank_account" },
        };
        for (var row : sheet.rows()) {
            var errors = new ArrayList<String>();
            String name = row.get("name");
            String label = name.isEmpty() ? "（未填名称）" : name;
            Map<String, Object> current = null;
            String id = row.get("id");
            if (!id.isEmpty()) {
                current = byId.get(id);
                if (current == null) errors.add(
                    "系统编号不存在或不属于" + (kind.equals("CUSTOMER") ? "客户" : "供应商")
                );
            } else current = byName.get(name);
            if (name.isEmpty()) errors.add("名称必填");
            if (!name.isEmpty() && seen.put(name, row.number()) != null) errors.add(
                "与第 " + seen.get(name) + " 行名称重复"
            );
            if (current != null && !name.equals(db.text(current, "name"))) {
                var other = byName.get(name);
                if (other != null && !other.get("id").equals(current.get("id"))) errors.add(
                    "名称与已有资料“" + name + "”重复"
                );
            }
            var v = new HashMap<String, String>();
            for (var f : fields) v.put(f[0], value(sheet, row, f[0], current, f[1]));
            String type;
            if (sheet.present().contains("invoice_type")) {
                type = invoiceTypeCode(row.get("invoice_type"));
                if (type == null && !row.get("invoice_type").isEmpty()) errors.add("发票类型只能填“专票”或“普票”");
            } else type = current == null ? null : (String) current.get("invoice_type");
            try {
                if (!v.get("tax_no").isEmpty()) v.put("tax_no", TaxNumbers.normalize(v.get("tax_no")));
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            var input = new MasterService.PartnerInput(
                name,
                kind,
                blank(v.get("contact")),
                blank(v.get("phone")),
                blank(v.get("note")),
                blank(v.get("invoice_title")),
                blank(v.get("tax_no")),
                blank(v.get("address")),
                blank(v.get("invoice_phone")),
                blank(v.get("bank_name")),
                blank(v.get("bank_account")),
                type
            );
            validate(input, errors, Map.of("bankAccount", "银行账号", "name", "名称", "taxNo", "纳税人识别号"));
            String rows = "第 " + row.number() + " 行";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (current == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> masters.partner(input)));
                continue;
            }
            var changed = new ArrayList<String>();
            if (!name.equals(db.text(current, "name"))) changed.add("名称");
            String[][] labels = {
                { "contact", "联系人" },
                { "phone", "联系电话" },
                { "note", "备注" },
                { "invoice_title", "开票抬头" },
                { "tax_no", "税号" },
                { "address", "地址" },
                { "invoice_phone", "开票电话" },
                { "bank_name", "开户行" },
                { "bank_account", "账号" },
            };
            for (var l : labels) if (!v.get(l[0]).equals(db.text(current, l[0]))) changed.add(l[1]);
            if (!Objects.equals(type, current.get("invoice_type"))) changed.add("发票类型");
            String cid = current.get("id").toString();
            ops.add(
                changed.isEmpty()
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(new Result(rows, UPDATE, label, List.of("变更：" + String.join("、", changed))), () ->
                          masters.updatePartner(cid, input)
                      )
            );
        }
        return ops;
    }

    // ---------- 标准产品 ----------

    private List<Op> planProducts(ExcelIo.Sheet sheet) {
        var existing = masters.products();
        var byId = index(existing, p -> p.get("id").toString());
        var byKey = index(existing, p ->
            productKey(db.text(p, "scenario"), db.text(p, "name"), db.text(p, "specification"), db.text(p, "model"))
        );
        var suppliers = supplierIndex();
        var seen = new HashMap<String, Integer>();
        var ops = new ArrayList<Op>();
        for (var row : sheet.rows()) {
            var errors = new ArrayList<String>();
            String scenario = row.get("scenario"),
                name = row.get("name"),
                spec = row.get("specification");
            String label = name + (spec.isEmpty() ? "" : " / " + spec);
            Map<String, Object> current;
            String id = row.get("id");
            String model = value(sheet, row, "model", id.isEmpty() ? null : byId.get(id), "model");
            String key = productKey(scenario, name, spec, model);
            if (!id.isEmpty()) {
                current = byId.get(id);
                if (current == null) errors.add("系统编号不存在");
            } else current = byKey.get(key);
            if (scenario.isEmpty()) errors.add("应用场景必填");
            else try {
                dictionary.scenario(scenario, current == null ? null : db.text(current, "scenario"));
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            if (name.isEmpty()) errors.add("产品名称必填");
            if (seen.put(key, row.number()) != null) errors.add(
                "与第 " + seen.get(key) + " 行重复（应用场景 + 名称 + 规格 + 型号）"
            );
            var other = byKey.get(key);
            if (current != null && other != null && !other.get("id").equals(current.get("id"))) errors.add(
                "与已有产品重复（应用场景 + 名称 + 规格 + 型号）"
            );
            String unit = value(sheet, row, "unit", current, "unit");
            if (unit.isEmpty()) unit = "台";
            String supplierId;
            if (sheet.present().contains("supplier")) {
                String s = row.get("supplier");
                supplierId = s.isEmpty() ? null : suppliers.get(s);
                if (!s.isEmpty() && supplierId == null) errors.add("供应商“" + s + "”不存在，请先登记或导入供应商资料");
            } else supplierId = current == null ? null : (String) current.get("supplier_id");
            boolean mainstream = sheet.present().contains("mainstream")
                ? ExcelIo.yes(row.get("mainstream"))
                : current != null && Boolean.TRUE.equals(current.get("mainstream"));
            var input = new MasterService.ProductInput(scenario, name, spec, model, unit, supplierId, mainstream);
            validate(
                input,
                errors,
                Map.of("scenario", "应用场景", "name", "产品名称", "specification", "规格", "unit", "单位")
            );
            String rows = "第 " + row.number() + " 行";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (current == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> masters.product(input)));
                continue;
            }
            var changed = new ArrayList<String>();
            if (!scenario.equals(db.text(current, "scenario"))) changed.add("应用场景");
            if (!name.equals(db.text(current, "name"))) changed.add("名称");
            if (!spec.equals(db.text(current, "specification"))) changed.add("规格");
            if (!model.equals(db.text(current, "model"))) changed.add("型号");
            if (!unit.equals(db.text(current, "unit"))) changed.add("单位");
            if (!Objects.equals(supplierId, current.get("supplier_id"))) changed.add("供应商");
            if (mainstream != Boolean.TRUE.equals(current.get("mainstream"))) changed.add("主流配置");
            String cid = current.get("id").toString();
            ops.add(
                changed.isEmpty()
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(new Result(rows, UPDATE, label, List.of("变更：" + String.join("、", changed))), () ->
                          masters.updateProduct(cid, input)
                      )
            );
        }
        return ops;
    }

    // ---------- 项目类型 ----------

    private List<Op> planTypes(ExcelIo.Sheet sheet) {
        var existing = index(catalog.types(), t -> db.text(t, "name"));
        var products = productIndex();
        var groups = group(sheet.rows(), r -> r.get("type"));
        var ops = new ArrayList<Op>();
        for (var g : groups.entrySet()) {
            String typeName = g.getKey();
            var rowsOf = g.getValue();
            var first = rowsOf.get(0);
            var errors = new ArrayList<String>();
            if (typeName.isEmpty()) errors.add("项目类型必填");
            String brand = first.get("brand");
            try {
                brand = business.resolve(
                    brand,
                    existing.get(typeName) == null ? null : db.text(existing.get(typeName), "brand")
                );
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            var current = existing.get(typeName);
            boolean active =
                sheet.present().contains("active") && !first.get("active").isEmpty()
                    ? ExcelIo.yes(first.get("active"))
                    : current == null || Boolean.TRUE.equals(current.get("active"));
            String note = sheet.present().contains("type_note")
                ? first.get("type_note")
                : current == null
                    ? ""
                    : db.text(current, "note");
            var lines = new ArrayList<CatalogService.TypeLine>();
            var seen = new HashSet<String>();
            for (var r : rowsOf) {
                if (r.get("name").isEmpty() && r.get("specification").isEmpty()) continue;
                String pid = products.get(
                    productKey(r.get("scenario"), r.get("name"), r.get("specification"), r.get("model"))
                );
                if (pid == null) {
                    errors.add(
                        "第 " +
                            r.number() +
                            " 行：标准产品库中找不到“" +
                            r.get("scenario") +
                            " / " +
                            r.get("name") +
                            " / " +
                            r.get("specification") +
                            "”"
                    );
                    continue;
                }
                if (!seen.add(pid)) errors.add("第 " + r.number() + " 行：同一产品重复");
                try {
                    var qty = ExcelIo.decimal(r.get("quantity"), "数量");
                    if (qty == null || qty.signum() <= 0) errors.add("第 " + r.number() + " 行：数量必须大于 0");
                    else lines.add(
                        new CatalogService.TypeLine(pid, qty, ExcelIo.decimal(r.get("unit_price"), "标准售价"))
                    );
                } catch (ApiException e) {
                    errors.add("第 " + r.number() + " 行：" + e.getMessage());
                }
            }
            var input = new CatalogService.TypeInput(typeName, brand, blank(note), active, lines);
            validate(input, errors, Map.of("name", "项目类型", "brand", "业务线"));
            String rows = span(rowsOf);
            String label = typeName + "（" + lines.size() + " 项配置）";
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (current == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> catalog.saveType(null, input)));
                continue;
            }
            @SuppressWarnings("unchecked")
            var oldItems = (List<Map<String, Object>>) current.get("items");
            boolean same =
                brand.equals(db.text(current, "brand")) &&
                active == Boolean.TRUE.equals(current.get("active")) &&
                Objects.equals(blank(note), blank(db.text(current, "note"))) &&
                oldItems.size() == lines.size();
            for (int i = 0; same && i < lines.size(); i++) {
                var o = oldItems.get(i);
                var n = lines.get(i);
                same =
                    o.get("product_id").equals(n.productId()) &&
                    eq(o.get("quantity"), n.quantity()) &&
                    eq(o.get("unit_price"), n.unitPrice());
            }
            String tid = current.get("id").toString();
            ops.add(
                same
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(
                          new Result(rows, UPDATE, label, List.of("以文件中的配置替换原有 " + oldItems.size() + " 项")),
                          () -> catalog.saveType(tid, input)
                      )
            );
        }
        return ops;
    }

    // ---------- 供应商成本模板 ----------

    private List<Op> planSupplierTemplates(ExcelIo.Sheet sheet) {
        var suppliers = supplierIndex();
        var types = index(catalog.types(), t -> db.text(t, "name"));
        var products = productIndex();
        var existing = index(catalog.supplierTemplates(), t -> t.get("supplier_id") + "::" + t.get("project_type_id"));
        var groups = group(sheet.rows(), r -> r.get("supplier") + "\u0000" + r.get("type"));
        var ops = new ArrayList<Op>();
        for (var g : groups.entrySet()) {
            var rowsOf = g.getValue();
            var first = rowsOf.get(0);
            String supplierName = first.get("supplier"),
                typeName = first.get("type");
            var errors = new ArrayList<String>();
            String supplierId = suppliers.get(supplierName);
            if (supplierId == null) errors.add("供应商“" + supplierName + "”不存在，请先登记或导入供应商资料");
            var type = types.get(typeName);
            if (type == null) errors.add("项目类型“" + typeName + "”不存在，请先维护项目类型");
            var current = supplierId == null || type == null ? null : existing.get(supplierId + "::" + type.get("id"));
            boolean active =
                sheet.present().contains("active") && !first.get("active").isEmpty()
                    ? ExcelIo.yes(first.get("active"))
                    : current == null || Boolean.TRUE.equals(current.get("active"));
            String note = sheet.present().contains("template_note")
                ? first.get("template_note")
                : current == null
                    ? ""
                    : db.text(current, "note");
            var lines = new ArrayList<CatalogService.CostLine>();
            var seen = new HashSet<String>();
            for (var r : rowsOf) {
                String pid = products.get(
                    productKey(r.get("scenario"), r.get("name"), r.get("specification"), r.get("model"))
                );
                if (pid == null) {
                    errors.add(
                        "第 " +
                            r.number() +
                            " 行：标准产品库中找不到“" +
                            r.get("scenario") +
                            " / " +
                            r.get("name") +
                            " / " +
                            r.get("specification") +
                            "”"
                    );
                    continue;
                }
                if (!seen.add(pid)) errors.add("第 " + r.number() + " 行：同一产品重复");
                try {
                    var m = ExcelIo.decimal(r.get("material_cost"), "材料成本");
                    var l = ExcelIo.decimal(r.get("labor_cost"), "团队人工");
                    var f = ExcelIo.decimal(r.get("finance_cost"), "财务成本");
                    for (var x : new BigDecimal[] { m, l, f })
                        if (x != null && x.signum() < 0) errors.add("第 " + r.number() + " 行：成本不能为负数");
                    lines.add(new CatalogService.CostLine(pid, m, l, f));
                } catch (ApiException e) {
                    errors.add("第 " + r.number() + " 行：" + e.getMessage());
                }
            }
            String rows = span(rowsOf);
            String label = supplierName + " · " + typeName + "（" + lines.size() + " 项成本）";
            if (!errors.isEmpty() || supplierId == null || type == null) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            var input = new CatalogService.SupplierTemplateInput(
                supplierId,
                type.get("id").toString(),
                blank(note),
                active,
                lines
            );
            validate(input, errors, Map.of());
            if (!errors.isEmpty()) {
                ops.add(new Op(new Result(rows, ERROR, label, errors), null));
                continue;
            }
            if (current == null) {
                ops.add(new Op(new Result(rows, CREATE, label, List.of()), () -> catalog.saveSupplierTemplate(input)));
                continue;
            }
            @SuppressWarnings("unchecked")
            var oldItems = (List<Map<String, Object>>) current.get("items");
            var oldBy = index(oldItems, i -> i.get("product_id").toString());
            boolean same =
                active == Boolean.TRUE.equals(current.get("active")) &&
                Objects.equals(blank(note), blank(db.text(current, "note"))) &&
                oldItems.size() ==
                lines
                    .stream()
                    .filter(x -> x.materialCost() != null || x.laborCost() != null || x.financeCost() != null)
                    .count();
            for (var n : lines) {
                if (!same) break;
                var o = oldBy.get(n.productId());
                same =
                    o != null &&
                    eq(o.get("material_cost"), n.materialCost()) &&
                    eq(o.get("labor_cost"), n.laborCost()) &&
                    eq(o.get("finance_cost"), n.financeCost());
            }
            ops.add(
                same
                    ? new Op(new Result(rows, SAME, label, List.of()), null)
                    : new Op(
                          new Result(rows, UPDATE, label, List.of("以文件中的成本替换原有 " + oldItems.size() + " 项")),
                          () -> catalog.saveSupplierTemplate(input)
                      )
            );
        }
        return ops;
    }

    // ===================== 工具 =====================

    /** 列在表中时取表中值，否则保留原值（新增时为空）。 */
    private String value(ExcelIo.Sheet sheet, Row row, String key, Map<String, Object> current, String column) {
        if (sheet.present().contains(key)) return row.get(key);
        return current == null ? "" : db.text(current, column);
    }

    private <T> void validate(T input, List<String> errors, Map<String, String> names) {
        for (var v : validator.validate(input)) {
            String field = v.getPropertyPath().toString();
            errors.add(names.getOrDefault(field, field) + "：" + v.getMessage());
        }
    }

    private Map<String, String> supplierIndex() {
        var out = new HashMap<String, String>();
        for (var p : masters.partners())
            if ("SUPPLIER".equals(p.get("kind"))) out.put(db.text(p, "name").trim(), p.get("id").toString());
        return out;
    }

    private Map<String, String> productIndex() {
        var out = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT id,scenario,name,specification,model FROM product"))
            out.put(
                productKey(
                    db.text(p, "scenario"),
                    db.text(p, "name"),
                    db.text(p, "specification"),
                    db.text(p, "model")
                ),
                p.get("id").toString()
            );
        return out;
    }

    static String productKey(String scenario, String name, String spec, String model) {
        return String.join("\u0000", scenario.trim(), name.trim(), spec.trim(), model == null ? "" : model.trim());
    }

    private static <T> Map<String, T> index(List<T> rows, java.util.function.Function<T, String> key) {
        var out = new HashMap<String, T>();
        for (var r : rows) out.putIfAbsent(key.apply(r), r);
        return out;
    }

    private static LinkedHashMap<String, List<Row>> group(
        List<Row> rows,
        java.util.function.Function<Row, String> key
    ) {
        var out = new LinkedHashMap<String, List<Row>>();
        for (var r : rows) out.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        return out;
    }

    private static String span(List<Row> rows) {
        int a = rows.get(0).number(),
            b = rows.get(rows.size() - 1).number();
        return a == b ? "第 " + a + " 行" : "第 " + a + "–" + b + " 行";
    }

    private static boolean eq(Object a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return new BigDecimal(a.toString()).compareTo(b) == 0;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String invoiceTypeName(String code) {
        return switch (code) {
            case "SPECIAL" -> "专票";
            case "NORMAL" -> "普票";
            default -> "";
        };
    }

    static String invoiceTypeCode(String name) {
        return switch (name.trim()) {
            case "专票", "增值税专用发票", "SPECIAL" -> "SPECIAL";
            case "普票", "增值税普通发票", "NORMAL" -> "NORMAL";
            default -> null;
        };
    }
}
