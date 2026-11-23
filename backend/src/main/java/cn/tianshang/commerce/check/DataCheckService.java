package cn.tianshang.commerce.check;

import cn.tianshang.commerce.common.Database;
import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.contract.ContractLedger;
import cn.tianshang.commerce.contract.ContractService;
import cn.tianshang.commerce.excel.ExcelIo;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * 数据检查：一键把库里的资料过一遍，按“缺失 / 对不上 / 疑似重复或不规范”三类列出问题，每条带上能直接打开对应记录的链接。
 *
 * <p>只读，不改任何数据。给录入基础资料的同事和管理员核对用：导入一批资料后跑一遍，照着清单去补、去改。
 * 金额口径（合同额、已开票、已回款）直接取合同台账的数字，不另算一套。
 */
@Service
public class DataCheckService {

    public static final String MISSING = "MISSING",
        MISMATCH = "MISMATCH",
        SUSPECT = "SUSPECT";
    public static final String ERROR = "ERROR",
        WARN = "WARN",
        INFO = "INFO";
    public static final Map<String, String> GROUP_NAMES = Map.of(
        MISSING,
        "缺失",
        MISMATCH,
        "对不上",
        SUSPECT,
        "疑似重复或不规范"
    );
    public static final Map<String, String> LEVEL_NAMES = Map.of(ERROR, "错误", WARN, "提醒", INFO, "建议");

    /** 开票后超过这么多天没有回款的，列出来提醒。 */
    static final int INVOICE_UNPAID_DAYS = 60;
    /** 每项最多列出的明细条数（count 仍是总数）。 */
    static final int MAX_FINDINGS = 300;

    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\-+()（）/、转,，;；#]");

    /** 一条问题：label 是哪条资料，detail 是哪里不对，link 是打开它的页面地址（可为空）。 */
    public record Finding(String label, String detail, String link) {}

    /** 一项检查：group 三大类之一，level 严重程度，about 说明怎么处理，count 总数，findings 最多 MAX_FINDINGS 条。 */
    public record Check(
        String key,
        String group,
        String title,
        String level,
        String about,
        int count,
        List<Finding> findings
    ) {}

    public record Report(String ranAt, int errors, int warnings, int infos, int total, List<Check> checks) {}

    private final Database db;
    private final ContractService contracts;

    public DataCheckService(Database db, ContractService contracts) {
        this.db = db;
        this.contracts = contracts;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public Report run() {
        var checks = new ArrayList<Check>();
        missing(checks);
        mismatch(checks);
        suspect(checks);
        int e = 0,
            w = 0,
            i = 0;
        for (var c : checks) {
            if (c.count() == 0) continue;
            if (ERROR.equals(c.level())) e += c.count();
            else if (WARN.equals(c.level())) w += c.count();
            else i += c.count();
        }
        return new Report(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
            e,
            w,
            i,
            e + w + i,
            checks
        );
    }

    /** 检查结果导出为 Excel，一行一个问题，给去补资料的同事。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public byte[] export() {
        var report = run();
        var columns = List.of(
            ExcelIo.Column.of("group", "类别", 14),
            ExcelIo.Column.of("title", "检查项", 24),
            ExcelIo.Column.of("level", "程度", 8),
            ExcelIo.Column.of("label", "资料", 40),
            ExcelIo.Column.of("detail", "问题", 50),
            ExcelIo.Column.of("about", "怎么处理", 50),
            ExcelIo.Column.of("link", "系统页面", 40)
        );
        var rows = new ArrayList<Map<String, Object>>();
        for (var c : report.checks())
            for (var f : c.findings()) {
                var r = new LinkedHashMap<String, Object>();
                r.put("group", GROUP_NAMES.get(c.group()));
                r.put("title", c.title());
                r.put("level", LEVEL_NAMES.get(c.level()));
                r.put("label", f.label());
                r.put("detail", f.detail());
                r.put("about", c.about());
                r.put("link", f.link());
                rows.add(r);
            }
        var notes = List.of(
            "数据检查结果（" +
                report.ranAt() +
                "）：错误 " +
                report.errors() +
                "，提醒 " +
                report.warnings() +
                "，建议 " +
                report.infos(),
            "错误：会影响报价、应收或导入，应尽快修正；提醒：资料不完整或对不上，请核实；建议：补上更好，不影响使用。",
            "“系统页面”是打开对应记录的地址，接在系统网址后面即可。每项最多列 " + MAX_FINDINGS + " 条。"
        );
        db.audit("DATA_CHECK_EXPORT", "data-check", "导出数据检查结果 " + rows.size() + " 条");
        return ExcelIo.write("数据检查", columns, rows, notes);
    }

    // ---------- 缺失 ----------

    private void missing(List<Check> out) {
        out.add(
            check(
                "product-supplier",
                MISSING,
                "产品没有供应商",
                ERROR,
                "标准产品必须属于某家供应商，否则报价时取不到成本、导入时也无法定位。到基础资料 → 标准产品库补上供应商。",
                rows(
                    "SELECT id,scenario,name,specification,model FROM product WHERE supplier_id IS NULL ORDER BY scenario,name"
                ),
                r -> new Finding(productLabel(r), "没有供应商", productLink(r))
            )
        );
        if (Roles.canSeeCost()) out.add(
            check(
                "product-cost",
                MISSING,
                "产品没有采购成本",
                INFO,
                "没有采购成本的产品，报价明细里的成本要手工填。可在标准产品库逐个填，或用“标准产品库”导入表的“采购成本”列批量补。",
                rows(
                    "SELECT id,scenario,name,specification,model FROM product WHERE purchase_cost IS NULL ORDER BY scenario,name"
                ),
                r -> new Finding(productLabel(r), "没有采购成本", productLink(r))
            )
        );
        out.add(
            check(
                "product-drawing",
                MISSING,
                "产品没有图纸也没有电气件清单",
                INFO,
                "主流产品建议上传图纸或维护电气件清单，配置清单和采购时用得到。",
                rows(
                    """
                    SELECT p.id,p.scenario,p.name,p.specification,p.model FROM product p
                    WHERE NOT EXISTS(SELECT 1 FROM product_drawing d WHERE d.product_id=p.id AND d.deleted_at IS NULL)
                      AND NOT EXISTS(SELECT 1 FROM product_component c WHERE c.product_id=p.id)
                    ORDER BY p.scenario,p.name
                    """
                ),
                r -> new Finding(productLabel(r), "没有图纸，也没有电气件清单", productLink(r))
            )
        );
        out.add(
            check(
                "type-items",
                MISSING,
                "项目类型没有标准配置",
                WARN,
                "启用中的项目类型没有配置产品，新建报价时带不出标准清单。到报价配置 → 项目类型补上。",
                rows(
                    """
                    SELECT t.id,t.name,t.brand FROM project_type t WHERE t.active
                      AND NOT EXISTS(SELECT 1 FROM project_type_item i WHERE i.project_type_id=t.id) ORDER BY t.name
                    """
                ),
                r ->
                    new Finding(
                        text(r, "name") + "（" + text(r, "brand") + "）",
                        "没有配置任何产品",
                        "/quote-setup?tab=types"
                    )
            )
        );
        out.add(
            check(
                "supplier-template",
                MISSING,
                "供应商没有成本模板",
                WARN,
                "有产品的供应商没有启用的成本模板，按该供应商报价时取不到材料/人工/财务成本。到报价配置 → 供应商成本补上。",
                rows(
                    """
                    SELECT s.id,s.name,(SELECT COUNT(*) FROM product p WHERE p.supplier_id=s.id) AS products
                    FROM partner s WHERE s.kind='SUPPLIER'
                      AND EXISTS(SELECT 1 FROM product p WHERE p.supplier_id=s.id)
                      AND NOT EXISTS(SELECT 1 FROM supplier_template t WHERE t.supplier_id=s.id AND t.active)
                    ORDER BY s.name
                    """
                ),
                r ->
                    new Finding(
                        text(r, "name"),
                        "有 " + r.get("products") + " 个产品，没有成本模板",
                        "/quote-setup?tab=costs"
                    )
            )
        );
        out.add(
            check(
                "customer-invoice",
                MISSING,
                "客户开票信息不全",
                WARN,
                "有门店的客户缺开票信息，开票时要临时去问。到基础资料 → 客户资料补上税号、地址、电话、开户行和账号。",
                rows(
                    """
                    SELECT c.id,c.name,c.invoice_title,c.tax_no,c.address,c.invoice_phone,c.bank_name,c.bank_account
                    FROM partner c WHERE c.kind='CUSTOMER' AND EXISTS(SELECT 1 FROM project p WHERE p.customer_id=c.id)
                      AND (c.tax_no IS NULL OR c.address IS NULL OR c.invoice_phone IS NULL OR c.bank_name IS NULL OR c.bank_account IS NULL)
                    ORDER BY c.name
                    """
                ),
                r -> {
                    var lack = new ArrayList<String>();
                    if (blank(r.get("tax_no"))) lack.add("税号");
                    if (blank(r.get("address"))) lack.add("地址");
                    if (blank(r.get("invoice_phone"))) lack.add("电话");
                    if (blank(r.get("bank_name"))) lack.add("开户行");
                    if (blank(r.get("bank_account"))) lack.add("账号");
                    return new Finding(
                        text(r, "name"),
                        "缺：" + String.join("、", lack),
                        partnerLink("CUSTOMER", text(r, "name"))
                    );
                }
            )
        );
        out.add(
            check(
                "project-fields",
                MISSING,
                "门店资料不全",
                WARN,
                "门店缺城市、负责人或项目类型时，筛选和提醒都不准；已成交门店还要有收货人和收货地址才能发货。到项目台账里修改。",
                rows(
                    """
                    SELECT p.id,p.name,p.city,p.owner_id,p.project_type_id,p.contract_no,p.consignee,p.ship_address,
                      c.name AS customer_name,
                      (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
                    FROM project p JOIN partner c ON c.id=p.customer_id
                    WHERE p.city IS NULL OR p.owner_id IS NULL OR p.project_type_id IS NULL
                      OR ((p.contract_no IS NOT NULL AND p.contract_no<>'') AND (p.consignee IS NULL OR p.ship_address IS NULL))
                    ORDER BY c.name,p.name
                    """
                ),
                r -> {
                    var lack = new ArrayList<String>();
                    if (blank(r.get("city"))) lack.add("城市");
                    if (blank(r.get("owner_id"))) lack.add("负责人");
                    if (blank(r.get("project_type_id"))) lack.add("项目类型");
                    if (dealt(r)) {
                        if (blank(r.get("consignee"))) lack.add("收货人");
                        if (blank(r.get("ship_address"))) lack.add("收货地址");
                    }
                    return new Finding(projectLabel(r), "缺：" + String.join("、", lack), projectLink(r));
                }
            )
        );
        out.add(
            check(
                "dealt-no-quote",
                MISSING,
                "已成交门店没有报价明细",
                ERROR,
                "填了合同号的门店没有任何报价明细，合同额和应收都算不出来。到客户报价给这家门店建报价，或用“门店报价明细”导入。",
                rows(
                    """
                    SELECT p.id,p.name,p.contract_no,c.name AS customer_name,
                      (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
                    FROM project p JOIN partner c ON c.id=p.customer_id
                    WHERE p.contract_no IS NOT NULL AND p.contract_no<>''
                      AND NOT EXISTS(SELECT 1 FROM quotation q JOIN quotation_item i ON i.quotation_id=q.id WHERE q.project_id=p.id)
                    ORDER BY c.name,p.name
                    """
                ),
                r -> new Finding(projectLabel(r), "合同号 " + text(r, "contract_no") + "，没有报价明细", projectLink(r))
            )
        );
        out.add(
            check(
                "contract-fields",
                MISSING,
                "合同缺签订日期或签约主体",
                WARN,
                "已签订的合同没有签订日期或我方签约主体，打印和统计会缺项。到合同台账打开该合同补上。",
                rows(
                    """
                    SELECT k.id,k.customer_id,k.contract_no,k.signed_on,k.company_id,c.name AS customer_name
                    FROM contract k JOIN partner c ON c.id=k.customer_id
                    WHERE k.status IN ('SIGNED','COMPLETED') AND (k.signed_on IS NULL OR k.company_id IS NULL)
                    ORDER BY c.name,k.contract_no
                    """
                ),
                r -> {
                    var lack = new ArrayList<String>();
                    if (r.get("signed_on") == null) lack.add("签订日期");
                    if (blank(r.get("company_id"))) lack.add("签约主体");
                    return new Finding(
                        contractLabel(r),
                        "缺：" + String.join("、", lack),
                        contractLink(text(r, "customer_id"), text(r, "contract_no"))
                    );
                }
            )
        );
        out.add(
            check(
                "receivable-due",
                MISSING,
                "应收没有截止日期",
                WARN,
                "已确认的报价没有应收截止日期，逾期提醒不会提示。到客户报价打开该报价设置截止日期。",
                rows(
                    """
                    SELECT q.id,q.code,p.name AS project_name,c.name AS customer_name
                    FROM quotation q JOIN project p ON p.id=q.project_id JOIN partner c ON c.id=p.customer_id
                    WHERE q.status='ACCEPTED' AND q.due_date IS NULL ORDER BY c.name,p.name
                    """
                ),
                r ->
                    new Finding(
                        text(r, "customer_name") + " · " + text(r, "project_name") + " · " + text(r, "code"),
                        "已确认，没有应收截止日期",
                        "/quotes?detail=" + enc(text(r, "id"))
                    )
            )
        );
    }

    // ---------- 对不上 ----------

    private void mismatch(List<Check> out) {
        var rows = contracts.contracts();
        var today = LocalDate.now();
        out.add(
            check(
                "signed-vs-orders",
                MISMATCH,
                "签约金额和门店订单合计不一致",
                WARN,
                "合同记录里的签约金额与各门店确认订单的合计不同。合同额按签约金额算；如果是门店报价漏了或多了，到对应门店修改报价。",
                rows
                    .stream()
                    .filter(
                        r ->
                            r.get("signed_amount") != null && count(r.get("projects")) > 0 && !zero(r.get("amount_gap"))
                    )
                    .toList(),
                r ->
                    new Finding(
                        contractLabel(r),
                        "签约金额 " +
                            money(r.get("signed_amount")) +
                            "，门店订单合计 " +
                            money(r.get("order_amount")) +
                            "，相差 " +
                            money(r.get("amount_gap")),
                        contractLink(text(r, "customer_id"), text(r, "contract_key"))
                    )
            )
        );
        out.add(
            check(
                "invoiced-over",
                MISMATCH,
                "已开票超过合同额",
                ERROR,
                "开票合计大于合同额，多半是开票登记重复或合同额没填全。到合同台账核对开票记录。",
                rows
                    .stream()
                    .filter(r -> greater(r.get("invoiced"), r.get("amount")))
                    .toList(),
                r ->
                    new Finding(
                        contractLabel(r),
                        "已开票 " + money(r.get("invoiced")) + "，合同额 " + money(r.get("amount")),
                        contractLink(text(r, "customer_id"), text(r, "contract_key"))
                    )
            )
        );
        out.add(
            check(
                "received-over",
                MISMATCH,
                "已回款超过合同额",
                ERROR,
                "回款合计大于合同额，多半是回款分配错了合同或合同额没填全。到合同台账核对回款分配。",
                rows
                    .stream()
                    .filter(r -> greater(r.get("received"), r.get("amount")))
                    .toList(),
                r ->
                    new Finding(
                        contractLabel(r),
                        "已回款 " + money(r.get("received")) + "，合同额 " + money(r.get("amount")),
                        contractLink(text(r, "customer_id"), text(r, "contract_key"))
                    )
            )
        );
        out.add(
            check(
                "invoiced-unpaid",
                MISMATCH,
                "开票后超过 " + INVOICE_UNPAID_DAYS + " 天未回款",
                WARN,
                "已开票但回款不足，且最近一次开票已超过 " + INVOICE_UNPAID_DAYS + " 天。请催款或核对回款是否漏登记。",
                rows
                    .stream()
                    .filter(r -> {
                        var d = ContractLedger.date(r.get("last_invoice_on"));
                        return (
                            d != null &&
                            positive(r.get("invoiced_unreceived")) &&
                            d.plusDays(INVOICE_UNPAID_DAYS).isBefore(today)
                        );
                    })
                    .toList(),
                r ->
                    new Finding(
                        contractLabel(r),
                        "已开票 " +
                            money(r.get("invoiced")) +
                            "，已回款 " +
                            money(r.get("received")) +
                            "，最近开票 " +
                            r.get("last_invoice_on"),
                        contractLink(text(r, "customer_id"), text(r, "contract_key"))
                    )
            )
        );
        out.add(
            check(
                "project-contract-missing",
                MISMATCH,
                "门店填了合同号但没有合同记录",
                WARN,
                "门店上的合同号在合同台账里没有对应的合同记录（签订日期、签约金额等都是空的）。到合同台账把这份合同建起来，或改正门店上的合同号。",
                // 直接查表：没有报价的门店不在合同台账里，也要能发现
                rows(
                    """
                    SELECT p.customer_id,p.contract_no,c.name AS customer_name,COUNT(*) AS projects
                    FROM project p JOIN partner c ON c.id=p.customer_id
                    WHERE p.contract_no IS NOT NULL AND p.contract_no<>''
                      AND NOT EXISTS(SELECT 1 FROM contract k WHERE k.customer_id=p.customer_id AND k.contract_no=p.contract_no)
                    GROUP BY p.customer_id,p.contract_no,c.name ORDER BY c.name,p.contract_no
                    """
                ),
                r ->
                    new Finding(
                        contractLabel(r),
                        r.get("projects") + " 个门店用了这个合同号，没有合同记录",
                        contractLink(text(r, "customer_id"), text(r, "contract_no"))
                    )
            )
        );
        out.add(
            check(
                "contract-no-projects",
                MISMATCH,
                "合同下没有门店",
                WARN,
                "合同记录存在，但没有任何门店填这个合同号，合同额只能按签约金额算、没有明细。到项目台账把门店的合同号填上。",
                rows(
                    """
                    SELECT k.customer_id,k.contract_no,c.name AS customer_name FROM contract k JOIN partner c ON c.id=k.customer_id
                    WHERE NOT EXISTS(SELECT 1 FROM project p WHERE p.customer_id=k.customer_id AND p.contract_no=k.contract_no)
                    ORDER BY c.name,k.contract_no
                    """
                ),
                r ->
                    new Finding(
                        contractLabel(r),
                        "没有门店",
                        contractLink(text(r, "customer_id"), text(r, "contract_no"))
                    )
            )
        );
        out.add(
            check(
                "line-mismatch",
                MISMATCH,
                "门店业务线和项目类型的业务线不一致",
                WARN,
                "门店选的项目类型属于另一条业务线，标准配置和统计会串。到项目台账改门店的业务线或项目类型。",
                rows(
                    """
                    SELECT p.id,p.name,p.brand,p.contract_no,t.name AS type_name,t.brand AS type_brand,c.name AS customer_name,
                      (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
                    FROM project p JOIN project_type t ON t.id=p.project_type_id JOIN partner c ON c.id=p.customer_id
                    WHERE p.brand<>t.brand ORDER BY c.name,p.name
                    """
                ),
                r ->
                    new Finding(
                        projectLabel(r),
                        "门店业务线 " +
                            text(r, "brand") +
                            "，项目类型“" +
                            text(r, "type_name") +
                            "”属于 " +
                            text(r, "type_brand"),
                        projectLink(r)
                    )
            )
        );
    }

    // ---------- 疑似重复 / 不规范 ----------

    private void suspect(List<Check> out) {
        out.add(similarNames());
        out.add(similarProducts());
        out.add(storeUnderTwoCustomers());
        out.add(futureDates());
        out.add(zeroAmounts());
        out.add(phones());
    }

    private Check similarNames() {
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var r : rows("SELECT id,name,kind FROM partner ORDER BY kind,name"))
            groups.computeIfAbsent(r.get("kind") + "|" + normalize(text(r, "name")), k -> new ArrayList<>()).add(r);
        var dups = groups
            .values()
            .stream()
            .filter(g -> g.size() > 1)
            .toList();
        return check(
            "partner-similar",
            SUSPECT,
            "客户或供应商名称疑似重复",
            WARN,
            "几条记录的名称只差空格、全角半角或大小写，多半是同一家建了两次。确认后把门店、合同挪到一条上，另一条不再使用。",
            dups,
            g -> {
                boolean customer = "CUSTOMER".equals(g.get(0).get("kind"));
                return new Finding(
                    (customer ? "客户：" : "供应商：") +
                        g
                            .stream()
                            .map(r -> text(r, "name"))
                            .reduce((a, b) -> a + " / " + b)
                            .orElse(""),
                    g.size() + " 条名称只差空格、全角半角或大小写",
                    partnerLink(customer ? "CUSTOMER" : "SUPPLIER", text(g.get(0), "name"))
                );
            }
        );
    }

    private Check similarProducts() {
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var r : rows(
            "SELECT id,scenario,name,specification,model,supplier_id FROM product ORDER BY scenario,name"
        ))
            groups
                .computeIfAbsent(
                    normalize(text(r, "scenario")) +
                        "|" +
                        normalize(text(r, "name")) +
                        "|" +
                        normalize(text(r, "specification")) +
                        "|" +
                        normalize(text(r, "model")) +
                        "|" +
                        text(r, "supplier_id"),
                    k -> new ArrayList<>()
                )
                .add(r);
        var dups = groups
            .values()
            .stream()
            .filter(g -> g.size() > 1)
            .toList();
        return check(
            "product-similar",
            SUSPECT,
            "标准产品疑似重复",
            WARN,
            "同一供应商下几条产品的场景、名称、规格、型号只差空格、全角半角或大小写。确认后保留一条，把配置和报价里的引用改过去。",
            dups,
            g ->
                new Finding(
                    g
                        .stream()
                        .map(DataCheckService::productLabel)
                        .reduce((a, b) -> a + " / " + b)
                        .orElse(""),
                    g.size() + " 条只差空格、全角半角或大小写",
                    productLink(g.get(0))
                )
        );
    }

    private Check storeUnderTwoCustomers() {
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var r : rows(
            """
            SELECT p.id,p.name,p.contract_no,c.name AS customer_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
            FROM project p JOIN partner c ON c.id=p.customer_id
            WHERE p.name IN (SELECT name FROM project GROUP BY name HAVING COUNT(DISTINCT customer_id)>1)
            ORDER BY p.name,c.name
            """
        ))
            groups.computeIfAbsent(text(r, "name"), k -> new ArrayList<>()).add(r);
        return check(
            "store-two-customers",
            SUSPECT,
            "同一门店名出现在两个客户下",
            WARN,
            "同名门店挂在不同客户下，可能是客户选错了，也可能确实是两家的同名店。请核实，选错的改到正确客户下。",
            new ArrayList<>(groups.values()),
            g ->
                new Finding(
                    text(g.get(0), "name"),
                    "客户：" +
                        g
                            .stream()
                            .map(r -> text(r, "customer_name"))
                            .distinct()
                            .reduce((a, b) -> a + " / " + b)
                            .orElse(""),
                    "/projects?q=" + enc(text(g.get(0), "name"))
                )
        );
    }

    private Check futureDates() {
        var today = LocalDate.now();
        var cutoff = java.sql.Date.valueOf(today);
        var items = new ArrayList<Finding>();
        for (var r : rows(
            """
            SELECT p.id,p.name,p.order_date,p.shipped_on,p.contract_no,c.name AS customer_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
            FROM project p JOIN partner c ON c.id=p.customer_id WHERE p.order_date>? OR p.shipped_on>? ORDER BY c.name,p.name
            """,
            cutoff,
            cutoff
        )) {
            var ordered = ContractLedger.date(r.get("order_date"));
            var shipped = ContractLedger.date(r.get("shipped_on"));
            if (ordered != null && ordered.isAfter(today)) items.add(
                new Finding(projectLabel(r), "下单日期 " + ordered + " 在将来", projectLink(r))
            );
            if (shipped != null && shipped.isAfter(today)) items.add(
                new Finding(projectLabel(r), "发货日期 " + shipped + " 在将来", projectLink(r))
            );
        }
        for (var r : rows(
            "SELECT k.customer_id,k.contract_no,k.signed_on,c.name AS customer_name FROM contract k JOIN partner c ON c.id=k.customer_id WHERE k.signed_on>? ORDER BY c.name",
            cutoff
        ))
            items.add(
                new Finding(
                    contractLabel(r),
                    "签订日期 " + r.get("signed_on") + " 在将来",
                    contractLink(text(r, "customer_id"), text(r, "contract_no"))
                )
            );
        for (var r : rows(
            "SELECT i.customer_id,i.contract_no,i.invoice_no,i.issued_on,c.name AS customer_name FROM contract_invoice i JOIN partner c ON c.id=i.customer_id WHERE i.deleted_at IS NULL AND i.issued_on>? ORDER BY c.name",
            cutoff
        ))
            items.add(
                new Finding(
                    contractLabel(r) + " · 发票 " + text(r, "invoice_no"),
                    "开票日期 " + r.get("issued_on") + " 在将来",
                    contractLink(text(r, "customer_id"), text(r, "contract_no"))
                )
            );
        for (var r : rows(
            "SELECT r.customer_id,r.contract_no,r.reference,r.paid_on,c.name AS customer_name FROM contract_receipt r JOIN partner c ON c.id=r.customer_id WHERE r.paid_on>? ORDER BY c.name",
            cutoff
        ))
            items.add(
                new Finding(
                    contractLabel(r) + " · 流水 " + text(r, "reference"),
                    "到账日期 " + r.get("paid_on") + " 在将来",
                    contractLink(text(r, "customer_id"), text(r, "contract_no"))
                )
            );
        for (var r : rows(
            "SELECT x.customer_id,x.contract_no,x.amount,x.incurred_on,c.name AS customer_name FROM contract_cost x JOIN partner c ON c.id=x.customer_id WHERE x.deleted_at IS NULL AND x.incurred_on>? ORDER BY c.name",
            cutoff
        ))
            items.add(
                new Finding(
                    contractLabel(r) + " · 成本 " + money(r.get("amount")),
                    "发生日期 " + r.get("incurred_on") + " 在将来",
                    contractLink(text(r, "customer_id"), text(r, "contract_no"))
                )
            );
        return check(
            "future-dates",
            SUSPECT,
            "日期在将来",
            WARN,
            "下单、签订、开票、到账或成本发生日期晚于今天，多半是填错了年份或月份。打开对应记录改正。",
            items,
            f -> f
        );
    }

    private Check zeroAmounts() {
        var items = new ArrayList<Finding>();
        for (var r : rows(
            """
            SELECT q.id,q.code,p.name AS project_name,c.name AS customer_name,COUNT(*) AS n
            FROM quotation q JOIN quotation_item i ON i.quotation_id=q.id
              JOIN project p ON p.id=q.project_id JOIN partner c ON c.id=p.customer_id
            WHERE q.status IN ('ISSUED','ACCEPTED') AND (i.unit_price IS NULL OR i.unit_price<=0)
            GROUP BY q.id,q.code,p.name,c.name ORDER BY c.name,p.name
            """
        ))
            items.add(
                new Finding(
                    text(r, "customer_name") + " · " + text(r, "project_name") + " · " + text(r, "code"),
                    r.get("n") + " 行明细单价为空或为 0",
                    "/quotes?detail=" + enc(text(r, "id"))
                )
            );
        for (var r : rows(
            "SELECT k.customer_id,k.contract_no,c.name AS customer_name FROM contract k JOIN partner c ON c.id=k.customer_id WHERE k.signed_amount=0 ORDER BY c.name"
        ))
            items.add(
                new Finding(
                    contractLabel(r),
                    "签约金额为 0",
                    contractLink(text(r, "customer_id"), text(r, "contract_no"))
                )
            );
        return check(
            "zero-amounts",
            SUSPECT,
            "金额为空或为 0",
            WARN,
            "已出具或已确认的报价里有单价为空或为 0 的明细，或合同签约金额填了 0。赠送项请在备注说明，否则请补上金额。",
            items,
            f -> f
        );
    }

    private Check phones() {
        var items = new ArrayList<Finding>();
        for (var r : rows("SELECT id,name,kind,phone,invoice_phone FROM partner ORDER BY kind,name")) {
            String link = partnerLink(text(r, "kind"), text(r, "name"));
            if (badPhone(r.get("phone"))) items.add(
                new Finding(text(r, "name"), "联系电话“" + text(r, "phone") + "”格式不像电话", link)
            );
            if (badPhone(r.get("invoice_phone"))) items.add(
                new Finding(text(r, "name"), "开票电话“" + text(r, "invoice_phone") + "”格式不像电话", link)
            );
        }
        for (var r : rows(
            """
            SELECT p.id,p.name,p.consignee_phone,p.contract_no,c.name AS customer_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS accepted
            FROM project p JOIN partner c ON c.id=p.customer_id WHERE p.consignee_phone IS NOT NULL ORDER BY c.name,p.name
            """
        ))
            if (badPhone(r.get("consignee_phone"))) items.add(
                new Finding(projectLabel(r), "收货电话“" + text(r, "consignee_phone") + "”格式不像电话", projectLink(r))
            );
        for (var r : rows("SELECT id,name,phone FROM company WHERE phone IS NOT NULL ORDER BY name"))
            if (badPhone(r.get("phone"))) items.add(
                new Finding(
                    "签约主体 " + text(r, "name"),
                    "电话“" + text(r, "phone") + "”格式不像电话",
                    "/settings?tab=companies"
                )
            );
        return check(
            "phones",
            SUSPECT,
            "电话格式不对",
            INFO,
            "电话号码去掉分隔符后应是 7 到 20 位数字。写了姓名、备注或多个号码的，请分开填。",
            items,
            f -> f
        );
    }

    // ---------- 工具 ----------

    private <T> Check check(
        String key,
        String group,
        String title,
        String level,
        String about,
        List<T> rows,
        Function<T, Finding> finding
    ) {
        var findings = new ArrayList<Finding>();
        for (var r : rows) {
            if (findings.size() >= MAX_FINDINGS) break;
            findings.add(finding.apply(r));
        }
        return new Check(key, group, title, level, about, rows.size(), findings);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return db.jdbc.queryForList(sql, args);
    }

    /** 去掉空格、全角转半角、小写，用来找只差写法的重复名称。 */
    public static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static boolean badPhone(Object v) {
        if (v == null) return false;
        String s = v.toString().trim();
        if (s.isEmpty()) return false;
        String digits = PHONE_SEPARATORS.matcher(s).replaceAll("");
        return !digits.matches("\\d{7,20}");
    }

    private static boolean dealt(Map<String, Object> project) {
        return !blank(project.get("contract_no")) || count(project.get("accepted")) > 0;
    }

    private static String productLabel(Map<String, Object> r) {
        var parts = new ArrayList<String>();
        parts.add(text(r, "scenario"));
        parts.add(text(r, "name"));
        if (!blank(r.get("specification"))) parts.add(text(r, "specification"));
        if (!blank(r.get("model"))) parts.add(text(r, "model"));
        return String.join(" ", parts);
    }

    private static String productLink(Map<String, Object> r) {
        return "/masters?tab=PRODUCT&q=" + enc(text(r, "name"));
    }

    private static String partnerLink(String kind, String name) {
        return "/masters?tab=" + ("SUPPLIER".equals(kind) ? "SUPPLIER" : "CUSTOMER") + "&q=" + enc(name);
    }

    private static String projectLabel(Map<String, Object> r) {
        return text(r, "customer_name") + " · " + text(r, "name");
    }

    private static String projectLink(Map<String, Object> r) {
        return "/projects?q=" + enc(text(r, "name")) + (dealt(r) ? "" : "&scope=quoting");
    }

    private static String contractLabel(Map<String, Object> r) {
        String no = text(r, "contract_no");
        if (no.isEmpty()) no = text(r, "contract_key");
        return text(r, "customer_name") + " · " + (no.startsWith("#") || no.isEmpty() ? "未填合同号" : no);
    }

    private static String contractLink(String customerId, String key) {
        return "/cash?tab=contracts&customer=" + enc(customerId) + "&contract=" + enc(key);
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static String text(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static int count(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static boolean zero(Object v) {
        var d = decimal(v);
        return d == null || d.signum() == 0;
    }

    private static boolean positive(Object v) {
        var d = decimal(v);
        return d != null && d.signum() > 0;
    }

    private static boolean greater(Object a, Object b) {
        var x = decimal(a);
        var y = decimal(b);
        return x != null && y != null && x.compareTo(y) > 0;
    }

    private static String money(Object v) {
        var d = decimal(v);
        return d == null ? "-" : d.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
