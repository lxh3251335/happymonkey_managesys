package cn.tianshang.commerce.project;

import cn.tianshang.commerce.business.BusinessService;
import cn.tianshang.commerce.common.*;
import cn.tianshang.commerce.contract.Contracts;
import cn.tianshang.commerce.dictionary.DictionaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目台账（门店项目管理）：门店登记、销售负责人、收货信息、发货与物流。
 *
 * <p>报价、合同、开票、回款等财务事项由报价与合同模块负责；这里只管理项目本身的执行。
 * 商务只能查看和办理自己负责的项目（见 {@link Ownership}）。
 */
@Service
public class ProjectService {

    /** 常用快递 / 物流公司（前端下拉建议，可填其他）。 */
    public static final List<String> COURIERS = List.of(
        "顺丰速运",
        "京东物流",
        "德邦快递",
        "中通快递",
        "圆通速递",
        "韵达快递",
        "申通快递",
        "EMS",
        "跨越速运",
        "安能物流",
        "专车配送",
        "客户自提"
    );

    private final Database db;
    private final BusinessService business;
    private final DictionaryService dictionary;
    private final Ownership ownership;

    public ProjectService(Database db, BusinessService business, DictionaryService dictionary, Ownership ownership) {
        this.db = db;
        this.business = business;
        this.dictionary = dictionary;
        this.ownership = ownership;
    }

    // ===================== 查询 =====================

    /** 门店项目列表；商务只看到自己负责的项目。 */
    public List<Map<String, Object>> list() {
        String sql = """
            SELECT p.*, c.name AS customer_name, t.name AS project_type_name, u.display_name AS owner_name,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id) AS quote_count,
              (SELECT COUNT(*) FROM quotation q WHERE q.project_id=p.id AND q.status='ACCEPTED') AS confirmed_quotes,
              CASE WHEN p.contract_no IS NULL OR p.contract_no='' THEN 'NONE' ELSE COALESCE(k.status,'SIGNED') END AS contract_state
            FROM project p JOIN partner c ON c.id=p.customer_id LEFT JOIN project_type t ON t.id=p.project_type_id
            LEFT JOIN app_user u ON u.id=p.owner_id
            LEFT JOIN contract k ON k.customer_id=p.customer_id AND k.contract_no=p.contract_no
            """;
        if (ownership.restricted()) return db.jdbc.queryForList(
            sql + " WHERE p.owner_id=? ORDER BY p.created_at DESC,p.code",
            Objects.toString(ownership.userId(), "-")
        );
        return db.jdbc.queryForList(sql + " ORDER BY p.created_at DESC,p.code");
    }

    /**
     * 门店的合同状态：NONE 未签合同；DRAFT 合同拟定中；其余为已建立的合同（有合同号但没有合同记录的历史门店视为 SIGNED）。
     */
    public String contractState(String projectId) {
        var rows = db.jdbc.queryForList(
            """
            SELECT CASE WHEN p.contract_no IS NULL OR p.contract_no='' THEN 'NONE' ELSE COALESCE(k.status,'SIGNED') END AS state
            FROM project p LEFT JOIN contract k ON k.customer_id=p.customer_id AND k.contract_no=p.contract_no
            WHERE p.id=?
            """,
            String.class,
            projectId
        );
        if (rows.isEmpty()) throw new ApiException("记录不存在");
        return rows.get(0);
    }

    /** 可选负责人（启用的商务与管理员），所有登录用户可读，用于下拉选择与筛选。 */
    public List<Map<String, Object>> owners() {
        return db.jdbc.queryForList(
            "SELECT id,display_name,role,enabled FROM app_user WHERE role IN ('ADMIN','SALES') ORDER BY enabled DESC,role DESC,display_name"
        );
    }

    // ===================== 登记与修改 =====================

    /**
     * 校验门店没有用过：除 ignoreQuoteId 以外没有其他报价、没有合同号、没有发货记录。
     * 删除草稿并连带删除门店时，先用它确认门店可删，再动报价。
     */
    public Map<String, Object> requireUnused(String id, String ignoreQuoteId) {
        ownership.checkProject(id);
        var p = db.one("SELECT * FROM project WHERE id=? FOR UPDATE", id);
        int quotes = db.jdbc.queryForObject(
            "SELECT COUNT(*) FROM quotation WHERE project_id=? AND id<>?",
            Integer.class,
            id,
            Objects.toString(ignoreQuoteId, "-")
        );
        if (quotes > 0) throw new ApiException("该门店还有其他报价，不能删除门店");
        if (blank((String) p.get("contract_no")) != null) throw new ApiException("该门店已有合同号，不能删除");
        if (p.get("shipped_on") != null || blank((String) p.get("tracking_no")) != null) throw new ApiException(
            "该门店已有发货记录，不能删除"
        );
        return p;
    }

    /**
     * 删除没有用过的门店：没有任何报价、没有合同号、也没有发货记录时才能删。
     * 用于撤销“新建报价时顺带登记”的门店；有业务痕迹的门店不允许删除。
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public void deleteUnused(String id) {
        var p = requireUnused(id, null);
        db.jdbc.update("DELETE FROM project WHERE id=?", id);
        db.audit("PROJECT_DELETED", id, "删除未使用的门店：" + p.get("name"));
    }

    /** 发货与收货信息。填了运单号即视为已发货。 */
    public record DeliveryInput(
        @Pattern(regexp = "^$|制作中|已发货") String status,
        LocalDate expectedShipOn,
        @PastOrPresent LocalDate shippedOn,
        @Size(max = 40) String courier,
        @Size(max = 60) @Pattern(
            regexp = "^$|[0-9A-Za-z\\-]{4,60}",
            message = "运单号只能包含数字、字母和短横线"
        ) String trackingNo,
        @Size(max = 60) String consignee,
        @Size(max = 40) @Pattern(
            regexp = "^$|[0-9+\\-\\s()转]{5,40}",
            message = "电话只能包含数字、空格、+、-、括号或“转”"
        ) String consigneePhone,
        @Size(max = 300) String shipAddress
    ) {}

    /** 门店项目基本信息；delivery 为空时不改动发货与收货信息。 */
    public record ProjectInput(
        @NotBlank @Size(max = 300) String name,
        @NotBlank @Size(max = 30) String brand,
        @NotBlank String customerId,
        @Size(max = 150) String externalId,
        LocalDate orderDate,
        @Size(max = 2000) String note,
        @Size(max = 100) String contractNo,
        @Size(max = 50) String city,
        @Size(max = 36) String projectTypeId,
        @Size(max = 36) String ownerId,
        @Valid DeliveryInput delivery
    ) {}

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public String create(@Valid ProjectInput p) {
        requireCustomer(p.customerId());
        String id = db.id(),
            code = "XM-" + LocalDate.now().getYear() + "-" + id.substring(0, 8).toUpperCase();
        String contractNo = Contracts.blankToNull(p.contractNo());
        if (!Roles.canApprove() && established(p.customerId(), contractNo, id)) throw new ApiException(
            "合同 " + contractNo + " 已签订，新增门店属于合同变更，请联系管理员处理"
        );
        db.jdbc.update(
            "INSERT INTO project(id,code,name,brand,customer_id,external_id,order_date,note,contract_no,city,project_type_id,owner_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id,
            code,
            p.name().trim(),
            business.resolve(p.brand(), null),
            p.customerId(),
            blank(p.externalId()),
            p.orderDate(),
            p.note(),
            contractNo,
            city(p, null),
            projectType(p.projectTypeId()),
            ownerForNew(p.ownerId())
        );
        db.audit("PROJECT_CREATED", id, p.name());
        if (p.delivery() != null) applyDelivery(id, p.delivery());
        return id;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public void update(String id, @Valid ProjectInput p) {
        var original = db.one("SELECT * FROM project WHERE id=? FOR UPDATE", id);
        ownership.checkProject(id);
        requireCustomer(p.customerId());
        if (!original.get("customer_id").equals(p.customerId())) throw new ApiException(
            "已登记项目不允许更换客户，请新建项目"
        );
        String oldNo = Contracts.blankToNull((String) original.get("contract_no")),
            newNo = Contracts.blankToNull(p.contractNo());
        if (
            !Roles.canApprove() &&
            !Objects.equals(oldNo, newNo) &&
            (established(p.customerId(), oldNo, id) || established(p.customerId(), newNo, id))
        ) throw new ApiException("门店所属合同已签订，调整合同号属于合同变更，请联系管理员处理");
        String owner = (String) original.get("owner_id");
        if (Roles.canApprove() && p.ownerId() != null) {
            owner = blank(p.ownerId());
            if (owner != null) requireOwner(owner);
        }
        db.jdbc.update(
            "UPDATE project SET name=?,brand=?,external_id=?,order_date=?,note=?,contract_no=?,city=?,project_type_id=?,owner_id=? WHERE id=?",
            p.name().trim(),
            business.resolve(p.brand(), db.text(original, "brand")),
            blank(p.externalId()),
            p.orderDate(),
            p.note(),
            newNo,
            city(p, (String) original.get("city")),
            projectType(p.projectTypeId()),
            owner,
            id
        );
        var changes = new ArrayList<String>();
        if (!Objects.equals(oldNo, newNo)) changes.add(
            "合同号 " + Objects.toString(oldNo, "空") + " → " + Objects.toString(newNo, "空")
        );
        if (!Objects.equals(owner, original.get("owner_id"))) changes.add("负责人变更");
        db.audit("PROJECT_UPDATED", id, "更新项目信息" + (changes.isEmpty() ? "" : "：" + String.join("；", changes)));
        if (p.delivery() != null) applyDelivery(id, p.delivery());
    }

    /** 登记发货与物流：项目执行事项，负责人直接办理（不属于合同变更），记审计。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    @Transactional
    public void delivery(String id, @Valid DeliveryInput d) {
        db.one("SELECT id FROM project WHERE id=? FOR UPDATE", id);
        ownership.checkProject(id);
        applyDelivery(id, d);
    }

    private void applyDelivery(String id, DeliveryInput d) {
        var p = db.one("SELECT * FROM project WHERE id=?", id);
        String tracking = blank(d.trackingNo());
        String status = Contracts.shipping(d.status());
        if (tracking != null) status = Contracts.SHIPPED; // 有运单号即已发出
        LocalDate shippedOn = null;
        if (Contracts.SHIPPED.equals(status)) shippedOn =
            d.shippedOn() != null
                ? d.shippedOn()
                : date(p.get("shipped_on")) != null
                    ? date(p.get("shipped_on"))
                    : LocalDate.now();
        Object[] next = {
            status,
            d.expectedShipOn(),
            shippedOn,
            blank(d.courier()),
            tracking,
            blank(d.consignee()),
            blank(d.consigneePhone()),
            blank(d.shipAddress()),
        };
        String[] cols = {
            "shipping_status",
            "expected_ship_on",
            "shipped_on",
            "courier",
            "tracking_no",
            "consignee",
            "consignee_phone",
            "ship_address",
        };
        String[] labels = {
            "发货状态",
            "预计发货",
            "发货日期",
            "快递公司",
            "运单号",
            "收货人",
            "收货电话",
            "收货地址",
        };
        var changes = new ArrayList<String>();
        for (int i = 0; i < cols.length; i++) {
            String before = text(p.get(cols[i])),
                after = text(next[i]);
            if (!Objects.equals(before, after)) changes.add(
                labels[i] + " " + Objects.toString(before, "空") + " → " + Objects.toString(after, "空")
            );
        }
        if (changes.isEmpty()) return;
        db.jdbc.update(
            """
            UPDATE project SET shipping_status=?,expected_ship_on=?,shipped_on=?,courier=?,tracking_no=?,
              consignee=?,consignee_phone=?,ship_address=? WHERE id=?
            """,
            next[0],
            next[1],
            next[2],
            next[3],
            next[4],
            next[5],
            next[6],
            next[7],
            id
        );
        String detail = p.get("name") + "：" + String.join("；", changes);
        db.audit("PROJECT_DELIVERY", id, detail.length() > 1900 ? detail.substring(0, 1900) + "…" : detail);
    }

    public record AssignInput(@NotEmpty List<String> projectIds, String ownerId, boolean includeContracts) {}

    /** 批量分配负责人（管理员、管理层）；可同时把这些门店所属合同的负责人一并调整。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public Map<String, Object> assignOwner(@Valid AssignInput a) {
        String owner = blank(a.ownerId());
        if (owner != null) requireOwner(owner);
        int projects = 0,
            contracts = 0;
        var keys = new LinkedHashSet<String>();
        for (String id : a.projectIds()) {
            var p = db.one("SELECT id,code,customer_id,contract_no FROM project WHERE id=? FOR UPDATE", id);
            projects += db.jdbc.update("UPDATE project SET owner_id=? WHERE id=?", owner, id);
            String no = Contracts.blankToNull((String) p.get("contract_no"));
            keys.add(p.get("customer_id") + "::" + (no != null ? no : "#" + p.get("code")));
        }
        if (a.includeContracts()) for (String k : keys) {
            var parts = k.split("::", 2);
            contracts += db.jdbc.update(
                "UPDATE contract SET owner_id=?,updated_at=CURRENT_TIMESTAMP WHERE customer_id=? AND contract_no=?",
                owner,
                parts[0],
                parts[1]
            );
        }
        String name =
            owner == null
                ? "未分配"
                : db.text(db.one("SELECT display_name FROM app_user WHERE id=?", owner), "display_name");
        db.audit(
            "PROJECT_OWNER_ASSIGNED",
            owner == null ? "-" : owner,
            projects + " 个门店、" + contracts + " 份合同 → " + name
        );
        return Map.of("projects", projects, "contracts", contracts);
    }

    // ===================== 规则 =====================

    /** 新建项目或合同的负责人：商务为本人；管理员可指定，未指定为本人。 */
    public String ownerForNew(String requested) {
        String me = ownership.userId();
        if (!Roles.canApprove()) return me;
        String v = blank(requested);
        if (v == null) return me;
        requireOwner(v);
        return v;
    }

    /** 按显示名称找负责人（Excel 导入用）。 */
    public String ownerByName(String name) {
        if (name == null || name.isBlank()) return null;
        var ids = db.jdbc.queryForList(
            "SELECT id FROM app_user WHERE display_name=? AND role IN ('ADMIN','SALES')",
            String.class,
            name.trim()
        );
        if (ids.isEmpty()) throw new ApiException("负责人“" + name + "”不是商务或管理员账户");
        if (ids.size() > 1) throw new ApiException("有多个账户叫“" + name + "”，请先在用户管理中区分显示名称");
        return ids.get(0);
    }

    private void requireOwner(String userId) {
        var rows = db.jdbc.queryForList("SELECT role FROM app_user WHERE id=?", userId);
        if (rows.isEmpty()) throw new ApiException("负责人不存在");
        if (!List.of("ADMIN", "SALES").contains(rows.get(0).get("role").toString())) throw new ApiException(
            "负责人须为商务或管理员账户"
        );
    }

    /**
     * 合同号是否属于已建立的合同：有合同记录且不是草稿，或没有合同记录但已有门店使用该合同号（历史合同）。
     * 非管理员不能把门店移出或加入已建立的合同（合同内容变更须管理员处理）。
     */
    private boolean established(String customerId, String contractNo, String exceptProjectId) {
        if (contractNo == null) return false;
        var rows = db.jdbc.queryForList(
            "SELECT status FROM contract WHERE customer_id=? AND contract_no=?",
            customerId,
            contractNo
        );
        if (!rows.isEmpty()) return !"DRAFT".equals(rows.get(0).get("status"));
        return (
            db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM project WHERE customer_id=? AND contract_no=? AND id<>?",
                Integer.class,
                customerId,
                contractNo,
                exceptProjectId
            ) >
            0
        );
    }

    /** 城市须在城市字典中（可填别名）；未填时按门店名称识别。 */
    private String city(ProjectInput p, String current) {
        String c = dictionary.city(p.city(), current);
        return c != null ? c : dictionary.detectCity(p.name());
    }

    private String projectType(String id) {
        String v = blank(id);
        if (v != null) db.one("SELECT id FROM project_type WHERE id=?", v);
        return v;
    }

    private void requireCustomer(String id) {
        db.one("SELECT id FROM partner WHERE id=? AND kind='CUSTOMER'", id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate date(Object v) {
        return v == null ? null : LocalDate.parse(v.toString().substring(0, 10));
    }

    private static String text(Object v) {
        if (v == null) return null;
        String s = v.toString();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) s = s.substring(0, 10);
        return s.isEmpty() ? null : s;
    }
}
