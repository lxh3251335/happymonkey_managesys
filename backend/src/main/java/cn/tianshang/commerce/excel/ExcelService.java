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

    /** 数据表，按导入的先后顺序排列（后面的表依赖前面的资料）。 */
    public enum Dataset {
        CUSTOMERS("customers", "客户资料", "客户名称、联系人、开票信息"),
        SUPPLIERS("suppliers", "供应商资料", "供应商名称、联系人、开票信息"),
        BUSINESS_LINES("business-lines", "客户集团与业务线", "如 美团 → 美团优选；项目、项目类型都挂在业务线下"),
        SCENARIOS("scenarios", "应用场景", "标准产品库的分类，如 冷柜、照明"),
        CITIES("cities", "城市", "门店所在城市及别名，用于从门店名称识别城市"),
        COMPANIES("companies", "签约主体", "我方签合同、开票的公司"),
        PRODUCTS("products", "标准产品库", "每家供应商一条：应用场景、名称、规格、型号、供应商、采购成本"),
        PRODUCT_COMPONENTS("product-components", "电气件清单", "标准产品内部的电气元件构成，只记录构成"),
        BUNDLES("bundles", "标准组合", "像套餐一样的一组标准品，一个组合一家供应商"),
        PROJECT_TYPES("project-types", "项目类型", "各业务线下的门店类型及标准配置（可含组合）和标准售价"),
        SUPPLIER_TEMPLATES("supplier-templates", "供应商成本模板", "某供应商做某项目类型时各产品的成本"),
        CUSTOMER_TEMPLATES("customer-templates", "客户报价模板", "某客户在某项目类型下约定的配置与售价"),
        PROJECTS("projects", "项目台账", "门店：客户、业务线、城市、合同号、负责人、发货"),
        QUOTE_LINES("quote-lines", "门店报价明细", "每个门店的报价明细（产品或组合 + 套数），生成报价版本"),
        CONTRACTS("contracts", "合同台账", "合同要素：标题、签订日期、签约主体、收款条件"),
        CONTRACT_INVOICES("contract-invoices", "开票记录", "每张发票一行"),
        RECEIPTS("receipts", "回款登记", "每笔到账及分到各合同的金额"),
        COSTS("costs", "成本登记", "合同的实际成本：材料、人工、安装、运输等");

        public final String path;
        public final String title;
        public final String about;

        Dataset(String path, String title, String about) {
            this.path = path;
            this.title = title;
            this.about = about;
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
    private final ProjectExcel projectExcel;
    private final DictionaryExcel dictionaryExcel;
    private final BundleExcel bundleExcel;
    private final CustomerTemplateExcel customerTemplateExcel;
    private final QuoteExcel quoteExcel;
    private final FinanceExcel financeExcel;

    public ExcelService(
        Database db,
        MasterService masters,
        CatalogService catalog,
        Validator validator,
        cn.tianshang.commerce.business.BusinessService business,
        cn.tianshang.commerce.dictionary.DictionaryService dictionary,
        ContractExcel contractExcel,
        ProjectExcel projectExcel,
        DictionaryExcel dictionaryExcel,
        BundleExcel bundleExcel,
        CustomerTemplateExcel customerTemplateExcel,
        QuoteExcel quoteExcel,
        FinanceExcel financeExcel
    ) {
        this.db = db;
        this.masters = masters;
        this.catalog = catalog;
        this.validator = validator;
        this.business = business;
        this.dictionary = dictionary;
        this.contractExcel = contractExcel;
        this.projectExcel = projectExcel;
        this.dictionaryExcel = dictionaryExcel;
        this.bundleExcel = bundleExcel;
        this.customerTemplateExcel = customerTemplateExcel;
        this.quoteExcel = quoteExcel;
        this.financeExcel = financeExcel;
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
            case PROJECTS -> projectExcel.columns();
            case CONTRACTS -> contractExcel.contractColumns();
            case CONTRACT_INVOICES -> contractExcel.invoiceColumns();
            case BUSINESS_LINES, SCENARIOS, CITIES, COMPANIES -> dictionaryExcel.columns(d);
            case PRODUCT_COMPONENTS -> bundleExcel.componentColumns();
            case BUNDLES -> bundleExcel.bundleColumns();
            case CUSTOMER_TEMPLATES -> customerTemplateExcel.columns();
            case QUOTE_LINES -> quoteExcel.columns();
            case RECEIPTS -> financeExcel.receiptColumns();
            case COSTS -> financeExcel.costColumns();
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
                Column.of("model", "型号", 14).note("同一供应商下 应用场景 + 名称 + 规格 + 型号 不能重复"),
                Column.of("unit", "单位", 8).note("留空为“台”"),
                Column.of("supplier", "供应商", 20)
                    .required()
                    .note("填写已登记的供应商名称；同一种产品不同供应商各一行"),
                Column.of("purchase_cost", "采购成本", 12)
                    .number()
                    .note("这家供应商的单台拿货价（元）；只有能看成本的角色能导出和导入"),
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
                Column.of("bundle", "组合名称", 20).note(
                    "填组合名称、产品留空：整个组合按套数展开；填了产品：该产品属于这个组合，相邻同名行同一组"
                ),
                Column.of("sets", "套数", 8).number().note("组合行用，留空为 1"),
                Column.of("scenario", "产品应用场景", 14).note("与标准产品库一致，用于定位产品"),
                Column.of("name", "产品名称", 22),
                Column.of("specification", "规格", 16),
                Column.of("model", "型号", 12),
                Column.of("supplier", "供应商", 18).note("同一种产品有多家供应商时必填"),
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
                Column.of("labor_cost", "人工成本", 12).number(),
                Column.of("finance_cost", "财务成本", 12).number(),
                Column.of("unit_cost", "单台成本", 12).number().note(EXPORT_ONLY + "；= 材料 + 人工 + 财务")
            );
        };
    }

    // ===================== 导出 =====================

    public byte[] export(Dataset d, boolean template) {
        if (
            (d == Dataset.SUPPLIER_TEMPLATES || d == Dataset.COSTS) && !Roles.canSeeCost()
        ) throw new AccessDeniedException(d.title + "仅管理员、管理层、财务可导出");
        List<Map<String, Object>> rows = template ? List.of() : exportRows(d);
        // 看不到成本的角色：导出时去掉成本、毛利列
        var columns = columns(d)
            .stream()
            .filter(c -> Roles.canSeeCost() || !CostRedaction.SENSITIVE.contains(c.key()))
            .toList();
        var notes = notes(d);
        db.audit("EXCEL_EXPORT", d.path, d.title + (template ? " 模板" : " " + rows.size() + " 行"));
        return ExcelIo.write(d.title, columns, rows, notes);
    }

    /** 模板和导出文件里“填写说明”表开头的说明。 */
    List<String> notes(Dataset d) {
        return switch (d) {
            case PROJECTS -> List.of(
                "项目台账 导入说明",
                "1. 有“系统编号”的行按编号更新；没有编号的按“客户 + 门店名称”匹配，匹配不到则新增。",
                "2. 业务线、城市须在数据字典中；负责人填写账户显示名称；填了运单号即视为已发货。",
                "3. 导入由管理员执行；删除不需要更新的列即可保持原值；有任何错误时整批不写入。",
                "4. 运单号、电话请保持文本格式，避免 Excel 改成科学计数法。"
            );
            case CONTRACTS -> List.of(
                "合同台账 导入说明",
                "1. 按“客户 + 合同号”匹配：已有合同更新合同要素，没有的新建合同（合同号以 # 开头的是未填合同号的单店订单）。",
                "2. 门店数、订单金额、开票、回款、成本、毛利等统计列只导出、不导入；开票请用“开票记录”导入，到款用“回款登记”导入。",
                "3. 导入由管理员执行，每份合同的修改都会记入合同变更记录（原因：Excel 批量导入）。",
                "4. 删除不需要更新的列即可保持原值；有任何错误时整批不写入。"
            );
            case CONTRACT_INVOICES -> List.of(
                "开票记录 导入说明",
                "1. 每行一张发票；按发票号码匹配，已登记的发票跳过，不能通过导入修改。",
                "2. 红字发票填写“原发票号码”，金额填正数；同一批次内可先蓝字后红字。",
                "3. 开票合计不能超过合同金额；有任何错误时整批不写入。",
                "4. 发票号码请保持文本格式，避免 Excel 改成科学计数法。"
            );
            case PROJECT_TYPES, SUPPLIER_TEMPLATES, CUSTOMER_TEMPLATES, BUNDLES, PRODUCT_COMPONENTS -> List.of(
                d.title + " 导入说明",
                "1. 同一个" +
                    (d == Dataset.PRODUCT_COMPONENTS ? "产品" : d == Dataset.BUNDLES ? "组合" : "模板") +
                    "写多行，每行一个产品；导入时以文件中的内容整份替换。",
                "2. 产品按“应用场景 + 名称 + 规格 + 型号”在标准产品库中定位；同一种产品有多家供应商时在“供应商”列指明。",
                d == Dataset.PROJECT_TYPES
                    ? "3. 标准组合可以只写“组合名称 + 套数”，导入时自动展开为组内各产品；也可以逐行列出组合里的产品。"
                    : "3. 列名带 * 的为必填。",
                "4. 导入前系统会逐行预览新增 / 更新 / 错误；有任何错误时整批不写入。"
            );
            case QUOTE_LINES -> List.of(
                "门店报价明细 导入说明",
                "1. 同一客户 + 门店写多行，每行一个产品，或一个标准组合（填组合名称和套数，产品留空）；每个门店生成一份新的报价版本。",
                "2. 门店不存在时按“城市、项目类型、合同号、下单日期”登记新门店；门店有合同号时报价直接出具，否则为草稿。",
                "3. 单价留空时按客户报价模板、项目类型标准售价取；税口径留空为含税 13%，有效期留空为 30 天后。",
                "4. 门店最新版本的明细与文件相同时跳过，所以同一份文件重复导入不会生成重复版本；有任何错误时整批不写入。"
            );
            case RECEIPTS -> List.of(
                "回款登记 导入说明",
                "1. 一行是“一笔到账分到一份合同”的金额：同一客户、到账日期、流水号的行合成一笔到账；到账金额写这笔钱的总额。",
                "2. 合同号留空表示这笔钱先不分配（待分配）；没分完的余额留作待分配，之后在合同台账里继续分。",
                "3. 流水号已登记过的到账跳过，所以同一份对账单可以反复导入；没有流水号的无法识别重复。",
                "4. 由管理员、财务导入；有任何错误时整批不写入。"
            );
            case COSTS -> List.of(
                "成本登记 导入说明",
                "1. 一行一笔实际成本，登记到客户的某份合同上；成本类别用下拉选项。",
                "2. 客户、合同号、类别、金额、发生日期、单据号完全相同的一笔已登记过时跳过。",
                "3. 由管理员、管理层、财务导入；有任何错误时整批不写入。"
            );
            default -> List.of(
                d.title + " 导入说明",
                "1. 第一张表为数据表，第一行是表头；列名带 * 的为必填。可删除不需要更新的列，缺少的列保持原值。",
                "2. 有“系统编号”的行按编号更新；没有编号的按名称匹配，匹配不到则新增。",
                "3. 导入前系统会逐行预览新增 / 更新 / 错误；有任何错误时整批不写入。",
                "4. 税号、银行账号等长数字请保持文本格式，避免 Excel 改成科学计数法。"
            );
        };
    }

    private List<Map<String, Object>> exportRows(Dataset d) {
        return switch (d) {
            case PROJECTS -> projectExcel.rows();
            case CONTRACTS -> contractExcel.contractRows();
            case CONTRACT_INVOICES -> contractExcel.invoiceRows();
            case BUSINESS_LINES, SCENARIOS, CITIES, COMPANIES -> dictionaryExcel.rows(d);
            case PRODUCT_COMPONENTS -> bundleExcel.componentRows();
            case BUNDLES -> bundleExcel.bundleRows();
            case CUSTOMER_TEMPLATES -> customerTemplateExcel.rows();
            case QUOTE_LINES -> quoteExcel.rows();
            case RECEIPTS -> financeExcel.receiptRows();
            case COSTS -> financeExcel.costRows();
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
                var suppliers = productSuppliers();
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
                        r.put("bundle", i.get("bundle_name"));
                        r.put("sets", i.get("bundle_sets"));
                        r.put("scenario", i.get("product_scenario"));
                        r.put("name", i.get("name"));
                        r.put("specification", i.get("specification"));
                        r.put("model", i.get("model"));
                        r.put("supplier", suppliers.get(Objects.toString(i.get("supplier_id"), "")));
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
        apply(ops);
        db.audit(
            "EXCEL_IMPORT",
            d.path,
            d.title + "：新增 " + report.created() + "，更新 " + report.updated() + "，无变化 " + report.unchanged()
        );
        return report;
    }

    /** 按预览的顺序逐条写入；某条写入失败时带上行号抛出。 */
    static void apply(List<Op> ops) {
        for (var op : ops) {
            if (op.apply() == null) continue;
            try {
                op.apply().run();
            } catch (ApiException e) {
                throw new ApiException(op.result().rows() + "：" + e.getMessage());
            }
        }
    }

    static Report report(Dataset d, List<Op> ops) {
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

    void requireImportRole(Dataset d) {
        var roles = SecurityContextHolder.getContext()
            .getAuthentication()
            .getAuthorities()
            .stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toSet());
        var allowed = switch (d) {
            case SUPPLIER_TEMPLATES -> List.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_FINANCE");
            case CONTRACT_INVOICES, RECEIPTS -> List.of("ROLE_ADMIN", "ROLE_FINANCE");
            case COSTS -> List.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_FINANCE");
            case CONTRACTS, PROJECTS, BUSINESS_LINES, SCENARIOS, CITIES, COMPANIES -> List.of("ROLE_ADMIN");
            default -> List.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SALES");
        };
        if (allowed.stream().noneMatch(roles::contains)) throw new AccessDeniedException("当前角色不能导入" + d.title);
    }

    /** 当前角色可以导入哪些表（“数据导入”页用）。 */
    public boolean canImport(Dataset d) {
        try {
            requireImportRole(d);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    List<Op> plan(Dataset d, ExcelIo.Sheet sheet) {
        if (sheet.rows().isEmpty()) throw new ApiException("Excel 中没有数据行");
        return switch (d) {
            case CUSTOMERS -> planPartners(sheet, "CUSTOMER");
            case SUPPLIERS -> planPartners(sheet, "SUPPLIER");
            case PRODUCTS -> planProducts(sheet);
            case PROJECT_TYPES -> planTypes(sheet);
            case SUPPLIER_TEMPLATES -> planSupplierTemplates(sheet);
            case PROJECTS -> projectExcel.plan(sheet);
            case CONTRACTS -> contractExcel.planContracts(sheet);
            case CONTRACT_INVOICES -> contractExcel.planInvoices(sheet);
            case BUSINESS_LINES, SCENARIOS, CITIES, COMPANIES -> dictionaryExcel.plan(d, sheet);
            case PRODUCT_COMPONENTS, BUNDLES -> bundleExcel.plan(d, sheet);
            case CUSTOMER_TEMPLATES -> customerTemplateExcel.plan(sheet);
            case QUOTE_LINES -> quoteExcel.plan(sheet);
            case RECEIPTS, COSTS -> financeExcel.plan(d, sheet);
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
                type,
                // 合同代码不在 Excel 中维护，更新时保留原值
                current == null ? null : (String) current.get("contract_code")
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
        var byKey = index(
            existing,
            p ->
                productKey(
                    db.text(p, "scenario"),
                    db.text(p, "name"),
                    db.text(p, "specification"),
                    db.text(p, "model")
                ) +
                "|" +
                db.text(p, "supplier_id")
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
            String supplierName = row.get("supplier");
            String supplierId = supplierName.isEmpty() ? null : suppliers.get(supplierName);
            if (supplierName.isEmpty()) errors.add("供应商必填");
            else if (supplierId == null) errors.add("供应商“" + supplierName + "”不存在，请先登记或导入供应商资料");
            String key = productKey(scenario, name, spec, model) + "|" + Objects.toString(supplierId, "");
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
                "与第 " + seen.get(key) + " 行重复（供应商 + 应用场景 + 名称 + 规格 + 型号）"
            );
            var other = byKey.get(key);
            if (current != null && other != null && !other.get("id").equals(current.get("id"))) errors.add(
                "与已有产品重复（供应商 + 应用场景 + 名称 + 规格 + 型号）"
            );
            String unit = value(sheet, row, "unit", current, "unit");
            if (unit.isEmpty()) unit = "台";
            boolean mainstream = sheet.present().contains("mainstream")
                ? ExcelIo.yes(row.get("mainstream"))
                : current != null && Boolean.TRUE.equals(current.get("mainstream"));
            // 采购成本：只有能看成本的角色能改；看不到成本的角色导入时保持原值
            BigDecimal purchaseCost = current == null ? null : Rows.decimal(current, "purchase_cost");
            if (sheet.present().contains("purchase_cost") && Roles.canSeeCost()) try {
                purchaseCost = ExcelIo.decimal(row.get("purchase_cost"), "采购成本");
                if (purchaseCost != null && purchaseCost.signum() < 0) errors.add("采购成本不能为负数");
            } catch (ApiException e) {
                errors.add(e.getMessage());
            }
            var input = new MasterService.ProductInput(
                scenario,
                name,
                spec,
                model,
                unit,
                supplierId,
                mainstream,
                purchaseCost
            );
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
            if (!eq(current.get("purchase_cost"), purchaseCost)) changed.add("采购成本");
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
        var products = new ProductIndex(db);
        var bundleLines = new BundleLines(db);
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
            // 组合展开行的默认售价：该类型里原来组合内同一产品的标准售价（单品行的售价不套到组合上）
            var prices = new HashMap<String, BigDecimal>();
            if (current != null) {
                @SuppressWarnings("unchecked")
                var oldItems = (List<Map<String, Object>>) current.get("items");
                for (var i : oldItems)
                    if (i.get("unit_price") != null && i.get("bundle_id") != null) prices.put(
                        i.get("product_id").toString(),
                        Rows.decimal(i, "unit_price")
                    );
            }
            var lines = new ArrayList<CatalogService.TypeLine>();
            for (var l : bundleLines.parse(rowsOf, products, prices::get, false, errors))
                lines.add(new CatalogService.TypeLine(l.productId(), l.quantity(), l.unitPrice(), l.group()));
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
                    eq(o.get("unit_price"), n.unitPrice()) &&
                    Objects.equals(o.get("bundle_id"), n.group() == null ? null : n.group().bundleId()) &&
                    eq(o.get("bundle_sets"), n.group() == null ? null : n.group().sets());
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
        var products = new ProductIndex(db);
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
                // 成本模板里的产品应是这家供应商的；同一种产品有多家供应商时优先取这家的
                String pid = products.find(
                    r.get("scenario"),
                    r.get("name"),
                    r.get("specification"),
                    r.get("model"),
                    null,
                    supplierId,
                    r.number(),
                    errors
                );
                if (pid == null) continue;
                if (!seen.add(pid)) errors.add("第 " + r.number() + " 行：同一产品重复");
                try {
                    var m = ExcelIo.decimal(r.get("material_cost"), "材料成本");
                    var l = ExcelIo.decimal(r.get("labor_cost"), "人工成本");
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

    /** 产品编号 → 供应商名称（项目类型导出用）。 */
    private Map<String, String> productSuppliers() {
        var out = new HashMap<String, String>();
        for (var p : db.jdbc.queryForList("SELECT p.id,s.name FROM product p JOIN partner s ON s.id=p.supplier_id"))
            out.put(p.get("id").toString(), db.text(p, "name"));
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
