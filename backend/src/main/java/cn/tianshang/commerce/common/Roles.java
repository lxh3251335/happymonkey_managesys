package cn.tianshang.commerce.common;

import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 角色与权限口径（集中定义，避免散落在各服务里）。
 *
 * <ul>
 *   <li>ADMIN 系统管理员：全部操作，含系统配置（用户、字典、模板、签约主体）；</li>
 *   <li>MANAGER 管理层：查看全部经营与成本数据，审批合同变更，直接修改已签订合同（须填原因），分配负责人；不做系统配置；</li>
 *   <li>FINANCE 财务：开票、回款、成本修正，查看全部经营与成本数据；</li>
 *   <li>SALES 业务员：只看和办理自己负责的门店、报价、合同；看不到供应商成本与毛利；</li>
 *   <li>VIEWER 只读：查看经营数据，看不到成本与毛利。</li>
 * </ul>
 */
public final class Roles {

    public static final String ADMIN = "ADMIN",
        MANAGER = "MANAGER",
        FINANCE = "FINANCE",
        SALES = "SALES",
        VIEWER = "VIEWER";
    public static final Set<String> ALL = Set.of(ADMIN, MANAGER, FINANCE, SALES, VIEWER);

    private Roles() {}

    public static boolean has(String role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return (
            auth != null &&
            auth
                .getAuthorities()
                .stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role))
        );
    }

    public static boolean isAdmin() {
        return has(ADMIN);
    }

    /** 审批与直接修改已建立的合同、调整负责人：系统管理员或管理层。 */
    public static boolean canApprove() {
        return has(ADMIN) || has(MANAGER);
    }

    /**
     * 可见成本与毛利：系统管理员、管理层、财务。未登录（启动任务、内部调用）视为可见。
     * 其他角色的接口响应由 {@link CostRedaction} 去掉成本字段。
     */
    public static boolean canSeeCost() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return true;
        return has(ADMIN) || has(MANAGER) || has(FINANCE);
    }
}
