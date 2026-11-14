package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

@WithMockUser(username = "admin", roles = "ADMIN")
class AdminEditTest extends ApiTestSupport {

    private ResultActions putJson(String path, Object body) throws Exception {
        return mvc.perform(
            put(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))
        );
    }

    @Test
    void adminEditsPartnerAndProductWithoutChangingQuoteSnapshot() throws Exception {
        String customer = customer();
        putJson(
            "/api/partners/" + customer,
            Map.of("name", "改名客户" + UUID.randomUUID(), "kind", "CUSTOMER", "contact", "张三", "phone", "123")
        ).andExpect(status().isOk());
        putJson("/api/partners/" + customer, Map.of("name", "改类别", "kind", "SUPPLIER")).andExpect(
            status().isBadRequest()
        );

        String project = project(),
            product = product();
        String quote = postJson("/api/quotes", quoteInput(project, product, "50")).path("id").asText();
        putJson(
            "/api/products/" + product,
            Map.of("brand", "小象", "name", "新品名" + UUID.randomUUID(), "specification", "新规格", "unit", "套")
        ).andExpect(status().isOk());
        var item = getJson("/api/quotes/" + quote).path("items").get(0);
        assertThat(item.path("specification").asText()).isEqualTo("650*850*250");
        assertThat(item.path("unit").asText()).isEqualTo("台");
    }

    @Test
    void viewerCannotEditMasterData() throws Exception {
        String customer = customer();
        mvc
            .perform(
                put("/api/partners/" + customer)
                    .with(csrf())
                    .with(user("reader").roles("VIEWER"))
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("name", "x", "kind", "CUSTOMER")))
            )
            .andExpect(status().isForbidden());
    }

    @Test
    void adminManagesUsersAndDisabledSessionIsRejected() throws Exception {
        String id = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username='sales1'", String.class);
        putJson(
            "/api/admin/users/" + id,
            Map.of("displayName", "商务一", "role", "FINANCE", "enabled", true)
        ).andExpect(status().isOk());
        // 角色已变更：旧会话（仍为 SALES）被拒绝
        mvc.perform(get("/api/quotes").with(user("sales1").roles("SALES"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quotes").with(user("sales1").roles("FINANCE"))).andExpect(status().isOk());

        putJson(
            "/api/admin/users/" + id,
            Map.of("displayName", "商务一", "role", "FINANCE", "enabled", false)
        ).andExpect(status().isOk());
        mvc.perform(get("/api/quotes").with(user("sales1").roles("FINANCE"))).andExpect(status().isUnauthorized());

        mvc
            .perform(
                post("/api/admin/users/" + id + "/password")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("password", "short")))
            )
            .andExpect(status().isBadRequest());
        postJson("/api/admin/users/" + id + "/password", Map.of("password", "ResetPassword2026!"));
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        String self = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username='admin'", String.class);
        putJson(
            "/api/admin/users/" + self,
            Map.of("displayName", "管理员", "role", "SALES", "enabled", true)
        ).andExpect(status().isBadRequest());
        putJson(
            "/api/admin/users/" + self,
            Map.of("displayName", "管理员", "role", "ADMIN", "enabled", false)
        ).andExpect(status().isBadRequest());
        putJson(
            "/api/admin/users/" + self,
            Map.of("displayName", "系统管理员", "role", "ADMIN", "enabled", true)
        ).andExpect(status().isOk());
    }
}
