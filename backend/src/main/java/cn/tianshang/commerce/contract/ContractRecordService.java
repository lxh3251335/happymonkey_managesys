package cn.tianshang.commerce.contract;

import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.dictionary.DictionaryService;
import cn.tianshang.commerce.project.ProjectService;
import cn.tianshang.commerce.quote.QuoteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同记录：生成正式合同、维护合同要素，以及已建立合同的变更审批与修改轨迹。
 *
 * <p>规则：拟定中（草稿）的合同由负责人直接修改；已签订、已完结、已终止的合同，非管理员的任何修改都提交为变更申请，
 * 管理员批准后才生效。管理员可直接修改，但必须填写原因。所有修改（含草稿修改、管理员直接修改）都记入 contract_change，
 * 保存每个字段的原值与新值、申请人、审批人与时间。
 */
@Service
public class ContractRecordService {

    /** 可修改的合同要素：字段 → 中文名。顺序即展示顺序。 */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("status", "合同状态");
        FIELDS.put("company_id", "我方签约主体");
        FIELDS.put("title", "合同名称");
        FIELDS.put("content", "合同内容");
        FIELDS.put("signed_on", "签订日期");
        FIELDS.put("signed_amount", "签约金额");
        FIELDS.put("payment_terms", "付款条款");
        FIELDS.put("start_on", "履约开始");
        FIELDS.put("end_on", "履约结束");
        FIELDS.put("warranty_months", "质保期（月）");
        FIELDS.put("retention_amount", "质保金");
        FIELDS.put("owner_id", "销售负责人");
        FIELDS.put("customer_signer", "客户签约人");
        FIELDS.put("archived", "原件已归档");
        FIELDS.put("archive_no", "归档编号");
        FIELDS.put("note", "备注");
    }

    private final Database db;
    private final ContractService contracts;
    private final QuoteService quotes;
    private final ProjectService projects;
    private final DictionaryService dictionary;
    private final Ownership ownership;
    private final ObjectMapper json;
    private final ContractNumbers numbers;

    public ContractRecordService(
        Database db,
        ContractService contracts,
        QuoteService quotes,
        ProjectService projects,
        DictionaryService dictionary,
        Ownership ownership,
        ObjectMapper json,
        ContractNumbers numbers
    ) {
        this.db = db;
        this.contracts = contracts;
        this.quotes = quotes;
        this.projects = projects;
        this.dictionary = dictionary;
        this.ownership = ownership;
        this.json = json;
        this.numbers = numbers;
    }

    /** 合同要素。字段为空表示清空（null）；未提交的字段在 Excel 导入时由调用方填入原值。 */
    public record Info(
        @Pattern(regexp = "DRAFT|SIGNED|COMPLETED|TERMINATED") String status,
        @Size(max = 36) String companyId,
        @Size(max = 200) String title,
        @Size(max = 2000) String content,
        LocalDate signedOn,
        @DecimalMin("0") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal signedAmount,
        @Size(max = 1000) String paymentTerms,
        LocalDate startOn,
        LocalDate endOn,
        @Min(0) @Max(240) Integer warrantyMonths,
        @DecimalMin("0") @DecimalMax("99999999999") @Digits(integer = 11, fraction = 2) BigDecimal retentionAmount,
        @Size(max = 36) String ownerId,
        @Size(max = 100) String customerSigner,
        boolean archived,
        @Size(max = 100) String archiveNo,
        @Size(max = 2000) String note
    ) {}

    public record UpdateInput(@NotNull @Valid Info info, @Size(max = 500) String reason) {}

    // ===================== 建立合同 =====================

    public record CreateInput(
        @NotBlank String customerId,
        @Size(max = 100) String contractNo,
        @NotNull @Valid Info info
    ) {}

    /** 手工登记合同（如先签框架合同、后补门店订单）。合同号留空时自动编号。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public Map<String, Object> create(@Valid CreateInput c) {
        db.one("SELECT id FROM partner WHERE id=? AND kind='CUSTOMER'", c.customerId());
        String no = Contracts.blankToNull(c.contractNo());
        if (no == null) no = numbers.next(c.customerId());
        if (no.startsWith("#")) throw new ApiException("合同号不能以 # 开头");
        if (
            exists(c.customerId(), no) ||
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM project WHERE customer_id=? AND contract_no=?",
                Integer.class,
                c.customerId(),
                no
            ) >
            0
        ) throw new ApiException("合同 " + no + " 已存在，请在合同台账中修改");
        var values = normalize(c.info(), null);
        values.put("owner_id", projects.ownerForNew((String) values.get("owner_id")));
        if (values.get("status") == null) values.put("status", "DRAFT");
        String id = insert(c.customerId(), no, values);
        record(id, "DIRECT", diff(Map.of(), values), "新建合同", Ownership.username());
        db.audit("CONTRACT_CREATED", id, no + "（" + ContractService.STATUS.get(values.get("status")) + "）");
        return Map.of("id", id, "contractKey", no);
    }

    public record ReplaceQuoteInput(@Size(max = 500) String reason, LocalDate dueDate) {}

    /**
     * 确认新版本并取代门店原已确认的报价（改价、增补或减配）。
     *
     * <ul>
     *   <li>合同已签订：属于合同变更，只有管理层 / 系统管理员可办理，必须填写原因，并记入合同变更记录；</li>
     *   <li>合同拟定中或还没有合同：负责人可直接办理；</li>
     *   <li>新金额不得低于该门店已回款；合同未填签约金额时，变更后的合同金额不得低于已开票金额。</li>
     * </ul>
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public Map<String, Object> replaceQuote(String quoteId, @Valid ReplaceQuoteInput in) {
        var quote = quotes.detail(quoteId);
        String projectId = quote.get("project_id").toString();
        var project = db.one("SELECT * FROM project WHERE id=? FOR UPDATE", projectId);
        String customerId = project.get("customer_id").toString();
        String contractNo = Contracts.blankToNull((String) project.get("contract_no"));
        String contractKey = contractNo != null ? contractNo : "#" + project.get("code");
        boolean established = !List.of("NONE", "DRAFT").contains(projects.contractState(projectId));
        String reason = Contracts.blankToNull(in.reason());
        if (established) {
            if (!Roles.canApprove()) throw new ApiException(
                "该门店的合同已签订，更换报价属于合同变更，请联系管理层办理"
            );
            if (reason == null) throw new ApiException("请填写变更原因");
        }
        var old = db.jdbc.queryForList(
            "SELECT id FROM quotation WHERE project_id=? AND status='ACCEPTED' AND id<>?",
            String.class,
            projectId,
            quoteId
        );
        if (old.isEmpty()) throw new ApiException("该门店没有已确认的报价，请直接办理客户确认");
        if (quote.get("amount") == null) throw new ApiException("报价金额不完整，无法确认");
        BigDecimal newAmount = ContractLedger.decimal(quote.get("amount")),
            oldAmount = ContractLedger.decimal(quotes.detail(old.get(0)).get("amount"));
        BigDecimal received = ContractLedger.decimal(
            db.jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(CASE WHEN e.kind='RECEIPT' THEN e.amount ELSE -e.amount END),0)
                FROM cash_entry e JOIN quotation q ON q.id=e.quotation_id WHERE q.project_id=?
                """,
                BigDecimal.class,
                projectId
            )
        );
        if (received.compareTo(newAmount) > 0) throw new ApiException(
            "该门店已回款 " + money(received) + "，高于新报价金额 " + money(newAmount) + "，请先办理退款"
        );
        var contract = contracts.contract(customerId, contractKey);
        BigDecimal signed = ContractLedger.decimal(contract.get("signed_amount"));
        boolean signedKept = contract.get("signed_amount") != null;
        BigDecimal after = signedKept
            ? signed
            : ContractLedger.decimal(contract.get("order_amount")).subtract(oldAmount).add(newAmount);
        BigDecimal invoiced = ContractLedger.decimal(contract.get("invoiced"));
        if (invoiced.compareTo(after) > 0) throw new ApiException(
            "变更后合同金额 " + money(after) + " 低于已开票金额 " + money(invoiced) + "，请先办理红字冲销"
        );
        var result = quotes.supersede(quoteId, in.dueDate());
        String before = "V" + result.get("old_version") + " " + money(oldAmount),
            now = "V" + result.get("new_version") + " " + money(newAmount);
        // 有合同号就记入合同变更记录；只有门店订单、还没有合同记录的历史合同先补建记录
        if (contractNo != null) record(
            ensure(customerId, contractNo),
            "DIRECT",
            Map.of("门店报价 · " + project.get("name"), Arrays.asList(before, now)),
            reason != null ? reason : "合同拟定中更换门店报价",
            Ownership.username()
        );
        db.audit(
            "CONTRACT_QUOTE_REPLACED",
            projectId,
            "门店报价 " + before + " → " + now + (reason == null ? "" : "；原因：" + reason)
        );
        var out = new LinkedHashMap<String, Object>();
        out.put("customerId", customerId);
        out.put("contractKey", contractKey);
        out.put("oldAmount", oldAmount);
        out.put("newAmount", newAmount);
        // 合同填了签约金额时，合同金额仍以签约金额为准，需要时在合同信息中另行修改
        out.put("signedAmountKept", signedKept);
        return out;
    }

    private static String money(BigDecimal v) {
        return "¥" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    public record FromQuoteInput(
        @Size(max = 100) String contractNo,
        @Size(max = 36) String companyId,
        LocalDate signedOn,
        LocalDate dueDate,
        @Size(max = 1000) String paymentTerms
    ) {}

    /**
     * 客户确认报价后生成正式合同：确认该门店订单的应收，门店写入合同号，并建立合同记录。
     * 填了签订日期即为“已签订”，否则为“拟定中”，签订后再补填。
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public Map<String, Object> fromQuote(String quoteId, @Valid FromQuoteInput in) {
        var quote = quotes.detail(quoteId);
        String status = quote.get("status").toString();
        if (!List.of("ISSUED", "ACCEPTED", "HISTORICAL").contains(status)) throw new ApiException(
            "请先出具报价单，客户确认后再生成合同"
        );
        String projectId = quote.get("project_id").toString();
        var project = db.one("SELECT * FROM project WHERE id=? FOR UPDATE", projectId);
        String customerId = project.get("customer_id").toString();
        String current = Contracts.blankToNull((String) project.get("contract_no"));
        String requested = Contracts.blankToNull(in.contractNo());
        if (current != null && requested != null && !current.equals(requested)) throw new ApiException(
            "该门店已属于合同 " + current + "，如需调整请联系管理员"
        );
        String no = current != null ? current : requested != null ? requested : numbers.next(customerId);
        if (no.startsWith("#")) throw new ApiException("合同号不能以 # 开头");
        var existing = row(customerId, no);
        if (
            current == null && existing != null && !"DRAFT".equals(existing.get("status")) && !Roles.canApprove()
        ) throw new ApiException("合同 " + no + " 已签订，新增门店属于合同变更，请联系管理员处理");
        if (!"ACCEPTED".equals(status)) {
            if (in.dueDate() == null) throw new ApiException("请填写应收截止日期");
            quotes.transition(quoteId, "accept", in.dueDate());
        }
        if (current == null) db.jdbc.update("UPDATE project SET contract_no=? WHERE id=?", no, projectId);
        String id;
        if (existing == null) {
            var values = new LinkedHashMap<String, Object>();
            values.put("status", in.signedOn() != null ? "SIGNED" : "DRAFT");
            values.put(
                "company_id",
                dictionary.company(in.companyId() != null ? in.companyId() : (String) quote.get("company_id"), null)
            );
            values.put("title", project.get("name") + " 设备购销合同");
            values.put("content", summary(quote));
            values.put("signed_on", in.signedOn());
            values.put("payment_terms", Contracts.blankToNull(in.paymentTerms()));
            values.put(
                "owner_id",
                project.get("owner_id") != null ? project.get("owner_id") : projects.ownerForNew(null)
            );
            values.put("archived", false);
            id = insert(customerId, no, values);
            record(
                id,
                "DIRECT",
                diff(Map.of(), values),
                "由报价 " + quote.get("code") + " 生成合同",
                Ownership.username()
            );
        } else id = existing.get("id").toString();
        db.audit("CONTRACT_FROM_QUOTE", id, "报价 " + quote.get("code") + " → 合同 " + no);
        return Map.of("id", id, "customerId", customerId, "contractKey", no);
    }

    // ===================== 修改与审批 =====================

    /**
     * 修改合同要素。返回 applied=true 表示已生效；pending=true 表示已提交变更申请，等待管理员批准。
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES','FINANCE')")
    @Transactional
    public Map<String, Object> update(String customerId, String contractKey, @Valid UpdateInput u) {
        ownership.checkContract(customerId, contractKey);
        String id = ensure(customerId, contractKey);
        var current = db.one("SELECT * FROM contract WHERE id=? FOR UPDATE", id);
        var values = normalize(u.info(), current);
        var changes = diff(current, values);
        if (changes.isEmpty()) throw new ApiException("没有需要保存的修改");
        boolean admin = Roles.canApprove();
        if (changes.containsKey("owner_id") && !admin) throw new ApiException("调整合同负责人须由管理层或管理员办理");
        String reason = Contracts.blankToNull(u.reason());
        boolean draft = "DRAFT".equals(current.get("status"));
        if (admin || draft) {
            if (admin && !draft && reason == null) throw new ApiException("请填写修改原因");
            apply(id, changes);
            record(id, "DIRECT", changes, reason == null ? "修改拟定中的合同" : reason, Ownership.username());
            db.audit("CONTRACT_UPDATED", id, describe(changes));
            return Map.of("applied", true, "pending", false);
        }
        if (reason == null) throw new ApiException("已签订合同的修改须填写变更原因，提交管理员审批");
        if (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM contract_change WHERE contract_id=? AND status='PENDING'",
                Integer.class,
                id
            ) >
            0
        ) throw new ApiException("该合同已有待审批的变更申请，请等待处理或撤回后再提交");
        String changeId = record(id, "PENDING", changes, reason, Ownership.username());
        db.audit("CONTRACT_CHANGE_REQUESTED", id, describe(changes) + "；原因：" + reason);
        return Map.of("applied", false, "pending", true, "changeId", changeId);
    }

    public record ReviewInput(@Size(max = 500) String note) {}

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public void approve(String changeId, @Valid ReviewInput r) {
        var change = pending(changeId);
        String id = change.get("contract_id").toString();
        var current = db.one("SELECT * FROM contract WHERE id=? FOR UPDATE", id);
        var changes = parse(change.get("changes").toString());
        // 申请后合同又被改过的字段：原值对不上则不能直接套用
        for (var e : changes.entrySet()) {
            String now = text(current.get(e.getKey()));
            if (!Objects.equals(now, e.getValue().get(0))) throw new ApiException(
                "“" + FIELDS.get(e.getKey()) + "”在申请后已被修改，请驳回后由申请人重新提交"
            );
        }
        apply(id, changes);
        db.jdbc.update(
            "UPDATE contract_change SET status='APPROVED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note=? WHERE id=?",
            Ownership.username(),
            Contracts.blankToNull(r.note()),
            changeId
        );
        db.audit("CONTRACT_CHANGE_APPROVED", id, describe(changes));
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public void reject(String changeId, @Valid ReviewInput r) {
        var change = pending(changeId);
        if (Contracts.blankToNull(r.note()) == null) throw new ApiException("请填写驳回原因");
        db.jdbc.update(
            "UPDATE contract_change SET status='REJECTED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note=? WHERE id=?",
            Ownership.username(),
            r.note().trim(),
            changeId
        );
        db.audit("CONTRACT_CHANGE_REJECTED", change.get("contract_id").toString(), r.note().trim());
    }

    /** 申请人撤回自己的申请。 */
    @Transactional
    public void withdraw(String changeId) {
        var change = pending(changeId);
        if (!Ownership.username().equals(change.get("requested_by"))) throw new AccessDeniedException(
            "只能撤回自己提交的申请"
        );
        db.jdbc.update(
            "UPDATE contract_change SET status='WITHDRAWN',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",
            Ownership.username(),
            changeId
        );
        db.audit("CONTRACT_CHANGE_WITHDRAWN", change.get("contract_id").toString(), "撤回变更申请");
    }

    /** 待审批的变更（管理员看全部，其他人看自己提交的）。 */
    public List<Map<String, Object>> pendingChanges() {
        var rows = db.jdbc.queryForList(
            """
            SELECT x.*,k.customer_id,k.contract_no,k.title,c.name AS customer_name,u.display_name AS requester_name
            FROM contract_change x JOIN contract k ON k.id=x.contract_id JOIN partner c ON c.id=k.customer_id
            LEFT JOIN app_user u ON u.username=x.requested_by
            WHERE x.status='PENDING' ORDER BY x.requested_at
            """
        );
        if (!Roles.canApprove()) rows = rows
            .stream()
            .filter(r -> Ownership.username().equals(r.get("requested_by")))
            .toList();
        for (var r : rows) r.put("changes", readable(parse(r.get("changes").toString())));
        return rows;
    }

    /** 合同详情：台账行 + 合同要素 + 变更记录 + 相关操作记录（回款、开票、成本、附件、确认）。 */
    public Map<String, Object> detail(String customerId, String contractKey) {
        var out = new LinkedHashMap<String, Object>(contracts.contract(customerId, contractKey));
        var header = row(customerId, contractKey);
        out.put("info", header == null ? null : header);
        var changes = new ArrayList<Map<String, Object>>();
        var entityIds = new ArrayList<String>(List.of(contractKey));
        if (header != null) {
            entityIds.add(header.get("id").toString());
            for (var x : db.jdbc.queryForList(
                """
                SELECT x.*,u.display_name AS requester_name,v.display_name AS reviewer_name
                FROM contract_change x LEFT JOIN app_user u ON u.username=x.requested_by
                LEFT JOIN app_user v ON v.username=x.reviewed_by
                WHERE x.contract_id=? ORDER BY x.requested_at DESC
                """,
                header.get("id")
            )) {
                x.put("changes", readable(parse(x.get("changes").toString())));
                changes.add(x);
            }
        }
        out.put("changes", changes);
        for (String table : List.of("contract_receipt", "contract_invoice", "contract_cost", "contract_attachment"))
            entityIds.addAll(
                db.jdbc.queryForList(
                    "SELECT id FROM " + table + " WHERE customer_id=? AND contract_no=?",
                    String.class,
                    customerId,
                    contractKey
                )
            );
        // 门店的发货登记、合同号调整等也属于合同执行轨迹
        entityIds.addAll(
            db.jdbc.queryForList(
                "SELECT id FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
                String.class,
                customerId,
                contractKey,
                contractKey
            )
        );
        String marks = String.join(",", Collections.nCopies(entityIds.size(), "?"));
        out.put(
            "events",
            visibleEvents(
                db.jdbc.queryForList(
                    "SELECT a.*,u.display_name AS actor_name FROM audit_log a LEFT JOIN app_user u ON u.username=a.actor WHERE a.entity_id IN (" +
                        marks +
                        ") ORDER BY a.created_at DESC",
                    entityIds.toArray()
                )
            )
        );
        out.put(
            "receipts",
            db.jdbc.queryForList(
                """
                SELECT r.*,(SELECT COALESCE(SUM(x.amount),0) FROM contract_receipt x WHERE x.reverses_id=r.id) AS refunded
                FROM contract_receipt r WHERE r.customer_id=? AND r.contract_no=? ORDER BY r.paid_on DESC,r.created_at DESC
                """,
                customerId,
                contractKey
            )
        );
        return out;
    }

    /** 看不到成本的角色不显示成本登记、成本修正类操作记录（其说明含金额）。 */
    private static List<Map<String, Object>> visibleEvents(List<Map<String, Object>> events) {
        if (Roles.canSeeCost()) return events;
        return events
            .stream()
            .filter(e -> {
                String a = Objects.toString(e.get("action"), "");
                return !a.startsWith("CONTRACT_COST") && !a.startsWith("QUOTE_COST");
            })
            .toList();
    }

    /** 预览修改（不写入）：返回有变化的字段（中文名 → 原值/新值），校验失败抛出业务异常。 */
    public List<Map<String, Object>> previewChanges(String customerId, String contractKey, Info info) {
        var current = row(customerId, contractKey);
        var values = normalize(info, current);
        return readable(diff(current == null ? baseline(customerId, contractKey) : current, values));
    }

    /** 没有合同记录的历史合同，建立记录时的默认值（与 ensure 一致）；全新合同为空。 */
    public Map<String, Object> baseline(String customerId, String contractKey) {
        var projects = db.jdbc.queryForList(
            "SELECT owner_id FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
            customerId,
            contractKey,
            contractKey
        );
        if (projects.isEmpty()) return Map.of();
        var owners = projects
            .stream()
            .map(p -> (String) p.get("owner_id"))
            .distinct()
            .toList();
        var out = new HashMap<String, Object>();
        out.put("status", "SIGNED");
        out.put("owner_id", owners.size() == 1 ? owners.get(0) : null);
        out.put("archived", false);
        return out;
    }

    // ===================== 内部 =====================

    /** 取合同记录 ID；历史合同（只有门店订单）没有记录时建立一条“已签订”记录。 */
    @Transactional
    public String ensure(String customerId, String contractKey) {
        var r = row(customerId, contractKey);
        if (r != null) return r.get("id").toString();
        var projects = db.jdbc.queryForList(
            "SELECT owner_id FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
            customerId,
            contractKey,
            contractKey
        );
        if (projects.isEmpty()) throw new ApiException("未找到该合同");
        var owners = projects
            .stream()
            .map(p -> (String) p.get("owner_id"))
            .distinct()
            .toList();
        var values = new LinkedHashMap<String, Object>();
        values.put("status", "SIGNED");
        values.put("owner_id", owners.size() == 1 ? owners.get(0) : null);
        values.put("archived", false);
        return insert(customerId, contractKey, values);
    }

    private boolean exists(String customerId, String no) {
        return row(customerId, no) != null;
    }

    Map<String, Object> row(String customerId, String no) {
        var rows = db.jdbc.queryForList(
            """
            SELECT k.*,m.name AS company_name,u.display_name AS owner_name FROM contract k
            LEFT JOIN company m ON m.id=k.company_id LEFT JOIN app_user u ON u.id=k.owner_id
            WHERE k.customer_id=? AND k.contract_no=?
            """,
            customerId,
            no
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String insert(String customerId, String no, Map<String, Object> values) {
        String id = db.id();
        var cols = new ArrayList<String>(List.of("id", "customer_id", "contract_no"));
        var args = new ArrayList<Object>(List.of(id, customerId, no));
        for (var e : values.entrySet()) {
            if (!FIELDS.containsKey(e.getKey())) continue;
            cols.add(e.getKey());
            args.add(e.getValue());
        }
        db.jdbc.update(
            "INSERT INTO contract(" +
                String.join(",", cols) +
                ") VALUES(" +
                String.join(",", Collections.nCopies(cols.size(), "?")) +
                ")",
            args.toArray()
        );
        return id;
    }

    /** 录入值规范化为数据库值；校验签约主体、负责人、日期先后。 */
    private LinkedHashMap<String, Object> normalize(Info i, Map<String, Object> current) {
        var v = new LinkedHashMap<String, Object>();
        v.put("status", i.status() == null ? (current == null ? null : current.get("status")) : i.status());
        String currentCompany = current == null ? null : (String) current.get("company_id");
        v.put(
            "company_id",
            Contracts.blankToNull(i.companyId()) == null ? null : dictionary.company(i.companyId(), currentCompany)
        );
        v.put("title", Contracts.blankToNull(i.title()));
        v.put("content", Contracts.blankToNull(i.content()));
        v.put("signed_on", i.signedOn());
        v.put("signed_amount", i.signedAmount());
        v.put("payment_terms", Contracts.blankToNull(i.paymentTerms()));
        v.put("start_on", i.startOn());
        v.put("end_on", i.endOn());
        v.put("warranty_months", i.warrantyMonths());
        v.put("retention_amount", i.retentionAmount());
        String owner = Contracts.blankToNull(i.ownerId());
        if (owner != null && (current == null || !owner.equals(current.get("owner_id")))) {
            var u = db.jdbc.queryForList("SELECT role FROM app_user WHERE id=?", owner);
            if (
                u.isEmpty() || !List.of("ADMIN", "SALES").contains(u.get(0).get("role").toString())
            ) throw new ApiException("负责人须为商务或管理员账户");
        }
        v.put("owner_id", owner);
        v.put("customer_signer", Contracts.blankToNull(i.customerSigner()));
        v.put("archived", i.archived());
        v.put("archive_no", Contracts.blankToNull(i.archiveNo()));
        v.put("note", Contracts.blankToNull(i.note()));
        if (i.startOn() != null && i.endOn() != null && i.endOn().isBefore(i.startOn())) throw new ApiException(
            "履约结束日期不能早于开始日期"
        );
        if (
            "SIGNED".equals(v.get("status")) &&
            i.signedOn() == null &&
            current != null &&
            "DRAFT".equals(current.get("status"))
        ) throw new ApiException("确认签订请填写签订日期");
        if (i.signedOn() != null && i.signedOn().isAfter(LocalDate.now())) throw new ApiException(
            "签订日期不能晚于今天"
        );
        return v;
    }

    /** 字段 → [原值, 新值]（文本形式）；只含有变化的字段。 */
    private static LinkedHashMap<String, List<String>> diff(Map<String, Object> current, Map<String, Object> values) {
        var out = new LinkedHashMap<String, List<String>>();
        for (String f : FIELDS.keySet()) {
            if (!values.containsKey(f)) continue;
            String a = text(current.get(f)),
                b = text(values.get(f));
            if ("archived".equals(f) && a == null) a = "false";
            if (!Objects.equals(a, b)) out.put(f, Arrays.asList(a, b));
        }
        return out;
    }

    /** 数据库值的统一文本：日期取 yyyy-MM-dd，金额去掉多余的 0，空串为 null。 */
    static String text(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate().toString();
        String s = v.toString();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) s = s.substring(0, 10);
        if (s.matches("-?\\d+\\.\\d+")) s = new BigDecimal(s).stripTrailingZeros().toPlainString();
        return s.isEmpty() ? null : s;
    }

    private void apply(String id, Map<String, List<String>> changes) {
        var sets = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (var e : changes.entrySet()) {
            if (!FIELDS.containsKey(e.getKey())) throw new IllegalStateException("未知字段 " + e.getKey());
            sets.add(e.getKey() + "=?");
            args.add(dbValue(e.getKey(), e.getValue().get(1)));
        }
        args.add(id);
        db.jdbc.update(
            "UPDATE contract SET " + String.join(",", sets) + ",updated_at=CURRENT_TIMESTAMP WHERE id=?",
            args.toArray()
        );
    }

    private static Object dbValue(String field, String v) {
        if (v == null) return null;
        return switch (field) {
            case "signed_on", "start_on", "end_on" -> LocalDate.parse(v);
            case "signed_amount", "retention_amount" -> new BigDecimal(v);
            case "warranty_months" -> Integer.valueOf(v);
            case "archived" -> Boolean.valueOf(v);
            default -> v;
        };
    }

    private String record(
        String contractId,
        String status,
        Map<String, List<String>> changes,
        String reason,
        String user
    ) {
        String id = db.id();
        try {
            db.jdbc.update(
                "INSERT INTO contract_change(id,contract_id,status,changes,reason,requested_by,reviewed_by,reviewed_at) VALUES(?,?,?,?,?,?,?,?)",
                id,
                contractId,
                status,
                json.writeValueAsString(changes),
                reason,
                user,
                status.equals("DIRECT") ? user : null,
                status.equals("DIRECT") ? new java.sql.Timestamp(System.currentTimeMillis()) : null
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private Map<String, Object> pending(String changeId) {
        var change = db.one("SELECT * FROM contract_change WHERE id=? FOR UPDATE", changeId);
        if (!"PENDING".equals(change.get("status"))) throw new ApiException("该申请已处理");
        return change;
    }

    private LinkedHashMap<String, List<String>> parse(String text) {
        try {
            return json.readValue(text, new TypeReference<LinkedHashMap<String, List<String>>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("变更记录损坏", e);
        }
    }

    /** 变更内容转成展示用：字段中文名、原值、新值（签约主体、负责人、状态换成名称）。 */
    private List<Map<String, Object>> readable(Map<String, List<String>> changes) {
        var users = ownership.names();
        var companies = new HashMap<String, String>();
        for (var c : db.jdbc.queryForList("SELECT id,name FROM company"))
            companies.put(c.get("id").toString(), c.get("name").toString());
        var out = new ArrayList<Map<String, Object>>();
        for (var e : changes.entrySet()) {
            var x = new LinkedHashMap<String, Object>();
            x.put("field", e.getKey());
            x.put("label", FIELDS.getOrDefault(e.getKey(), e.getKey()));
            x.put("before", show(e.getKey(), e.getValue().get(0), users, companies));
            x.put("after", show(e.getKey(), e.getValue().get(1), users, companies));
            out.add(x);
        }
        return out;
    }

    private static String show(String field, String v, Map<String, String> users, Map<String, String> companies) {
        if (v == null) return null;
        return switch (field) {
            case "owner_id" -> users.getOrDefault(v, v);
            case "company_id" -> companies.getOrDefault(v, v);
            case "status" -> ContractService.STATUS.getOrDefault(v, v);
            case "archived" -> "true".equals(v) ? "是" : "否";
            default -> v;
        };
    }

    private String describe(Map<String, List<String>> changes) {
        var parts = new ArrayList<String>();
        for (var x : readable(changes))
            parts.add(
                x.get("label") +
                    "：" +
                    Objects.toString(x.get("before"), "空") +
                    " → " +
                    Objects.toString(x.get("after"), "空")
            );
        String s = String.join("；", parts);
        return s.length() > 1900 ? s.substring(0, 1900) + "…" : s;
    }

    /** 合同内容摘要：按报价明细列出品名与数量。 */
    private static String summary(Map<String, Object> quote) {
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) quote.get("items");
        var parts = new ArrayList<String>();
        for (var i : items) {
            var q = new BigDecimal(i.get("quantity").toString()).stripTrailingZeros().toPlainString();
            parts.add(i.get("name") + "×" + q + Objects.toString(i.get("unit"), ""));
        }
        String s = "供货：" + String.join("、", parts);
        return s.length() > 1990 ? s.substring(0, 1990) + "…" : s;
    }
}
