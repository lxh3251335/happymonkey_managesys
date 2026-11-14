package cn.tianshang.commerce.auth;

import cn.tianshang.commerce.common.Database;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每次请求核对账户仍启用且角色未变：管理员停用账户或调整角色后，已登录会话立即失效，需重新登录。
 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final Database db;

    public ActiveUserFilter(Database db) {
        this.db = db;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            var rows = db.jdbc.queryForList(
                "SELECT role FROM app_user WHERE username=? AND enabled=TRUE",
                auth.getName()
            );
            boolean valid =
                !rows.isEmpty() &&
                auth
                    .getAuthorities()
                    .stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_" + rows.get(0).get("role")));
            if (!valid) {
                var session = req.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                res.setStatus(401);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"message\":\"账户已停用或权限已变更，请重新登录\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
