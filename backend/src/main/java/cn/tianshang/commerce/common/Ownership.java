package cn.tianshang.commerce.common;

import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 销售数据隔离：商务（SALES）角色只能查看和办理自己负责的门店项目、报价与合同；
 * 管理员、财务、只读账户查看全部。未分配负责人的历史数据只有后者可见，由管理员分配后销售才能看到。
 */
@Component
public class Ownership {

    private final Database db;

    public Ownership(Database db) {
        this.db = db;
    }

    public static String username() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    public static boolean hasRole(String role) {
        return Roles.has(role);
    }

    public static boolean isAdmin() {
        return Roles.isAdmin();
    }

    /** 当前用户是否只能看自己的数据。 */
    public boolean restricted() {
        return hasRole("SALES") && !hasRole("ADMIN");
    }

    /** 当前登录用户的用户 ID；未登录（启动任务）返回 null。 */
    public String userId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        var ids = db.jdbc.queryForList("SELECT id FROM app_user WHERE username=?", String.class, auth.getName());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 当前用户可见：不受限，或负责人是自己。 */
    public boolean visible(Object ownerId) {
        if (!restricted()) return true;
        String me = userId();
        return me != null && ownerId != null && me.equals(ownerId.toString());
    }

    public void checkProject(String projectId) {
        var rows = db.jdbc.queryForList("SELECT owner_id FROM project WHERE id=?", projectId);
        if (rows.isEmpty()) throw new ApiException("记录不存在");
        if (!visible(rows.get(0).get("owner_id"))) throw new AccessDeniedException("只能查看和办理自己负责的项目");
    }

    public void checkQuote(String quoteId) {
        var rows = db.jdbc.queryForList(
            "SELECT p.owner_id FROM quotation q JOIN project p ON p.id=q.project_id WHERE q.id=?",
            quoteId
        );
        if (rows.isEmpty()) throw new ApiException("记录不存在");
        if (!visible(rows.get(0).get("owner_id"))) throw new AccessDeniedException("只能查看和办理自己负责的报价");
    }

    /** 合同可见：合同负责人是自己，或（尚无合同记录时）其门店项目的负责人是自己。 */
    public void checkContract(String customerId, String contractNo) {
        if (!restricted()) return;
        String me = userId();
        var owners = db.jdbc.queryForList(
            "SELECT owner_id FROM contract WHERE customer_id=? AND contract_no=?",
            customerId,
            contractNo
        );
        boolean ok;
        if (!owners.isEmpty()) ok = me != null && me.equals(owners.get(0).get("owner_id"));
        else {
            var projects = db.jdbc.queryForList(
                "SELECT owner_id FROM project WHERE customer_id=? AND (contract_no=? OR '#'||code=?)",
                customerId,
                contractNo,
                contractNo
            );
            ok = !projects.isEmpty() && projects.stream().allMatch(p -> me != null && me.equals(p.get("owner_id")));
        }
        if (!ok) throw new AccessDeniedException("只能查看和办理自己负责的合同");
    }

    /** 用户 ID → 显示名称。 */
    public Map<String, String> names() {
        var out = new HashMap<String, String>();
        for (var u : db.jdbc.queryForList("SELECT id,display_name FROM app_user"))
            out.put(u.get("id").toString(), u.get("display_name").toString());
        return out;
    }
}
