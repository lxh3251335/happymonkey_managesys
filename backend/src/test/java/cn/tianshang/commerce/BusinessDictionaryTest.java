package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

@WithMockUser(username = "admin", roles = "ADMIN")
class BusinessDictionaryTest extends ApiTestSupport {

    private ResultActions send(String method, String path, Object body) throws Exception {
        var req = method.equals("PUT") ? put(path) : post(path);
        return mvc.perform(req.with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)));
    }

    private Map<String, Object> line(String group, String name, String shortName, boolean active) {
        var m = new HashMap<String, Object>();
        m.put("groupId", group);
        m.put("name", name);
        m.put("shortName", shortName);
        m.put("sort", 1);
        m.put("active", active);
        return m;
    }

    private String project(String brand) throws Exception {
        return postJson(
            "/api/projects",
            Map.of("name", "字典门店" + UUID.randomUUID(), "brand", brand, "customerId", customer())
        )
            .path("id")
            .asText();
    }

    private String brandOf(String projectId) {
        return jdbcTemplate.queryForObject("SELECT brand FROM project WHERE id=?", String.class, projectId);
    }

    @Test
    void seededMeituanLinesAndLegacyShortNames() throws Exception {
        JsonNode groups = getJson("/api/businesses");
        assertThat(groups.toString()).contains("美团", "小象前置仓", "快乐猴超市");
        // 旧简称仍可录入，保存为业务线名称
        assertThat(brandOf(project("小象"))).isEqualTo("小象前置仓");
        assertThat(brandOf(project("快乐猴超市"))).isEqualTo("快乐猴超市");
        assertThat(brandOf(project("未指定"))).isEqualTo("未指定");
        send("POST", "/api/projects", Map.of("name", "x", "brand", "不存在的业务", "customerId", customer())).andExpect(
            status().isBadRequest()
        );
    }

    @Test
    void newGroupAndLineRenameCascadeAndDeactivate() throws Exception {
        String jd = postJson("/api/businesses/groups", Map.of("name", "京东", "sort", 2, "active", true))
            .path("id")
            .asText();
        String line = postJson("/api/businesses/lines", line(jd, "京东折扣店", "京东折扣", true)).path("id").asText();
        String p1 = project("京东折扣");
        assertThat(brandOf(p1)).isEqualTo("京东折扣店");
        String type = postJson(
            "/api/project-types",
            Map.of("name", "京东门店" + UUID.randomUUID(), "brand", "京东折扣店", "active", true, "items", List.of())
        )
            .path("id")
            .asText();

        // 改名：项目与项目类型同步更新
        send("PUT", "/api/businesses/lines/" + line, line(jd, "京东七鲜折扣店", "京东折扣", true)).andExpect(
            status().isOk()
        );
        assertThat(brandOf(p1)).isEqualTo("京东七鲜折扣店");
        assertThat(
            jdbcTemplate.queryForObject("SELECT brand FROM project_type WHERE id=?", String.class, type)
        ).isEqualTo("京东七鲜折扣店");

        // 停用：不能新选，已有项目保留原值可继续修改
        send("PUT", "/api/businesses/lines/" + line, line(jd, "京东七鲜折扣店", "京东折扣", false)).andExpect(
            status().isOk()
        );
        send(
            "POST",
            "/api/projects",
            Map.of("name", "y", "brand", "京东七鲜折扣店", "customerId", customer())
        ).andExpect(status().isBadRequest());
        var keep = jdbcTemplate.queryForMap("SELECT name,customer_id FROM project WHERE id=?", p1);
        send(
            "PUT",
            "/api/projects/" + p1,
            Map.of("name", keep.get("name"), "brand", "京东七鲜折扣店", "customerId", keep.get("customer_id"))
        ).andExpect(status().isOk());

        // 经营总览按集团筛选
        var d = getJson("/api/dashboard?group=京东");
        assertThat(d.path("group").asText()).isEqualTo("京东");
    }

    @Test
    void namesMustBeUniqueAndReservedNamesRejected() throws Exception {
        String g = postJson(
            "/api/businesses/groups",
            Map.of("name", "盒马" + UUID.randomUUID(), "sort", 3, "active", true)
        )
            .path("id")
            .asText();
        send("POST", "/api/businesses/lines", line(g, "小象", null, true)).andExpect(status().isBadRequest()); // 与简称冲突
        send("POST", "/api/businesses/lines", line(g, "盒马鲜生", "快乐猴超市", true)).andExpect(
            status().isBadRequest()
        );
        send("POST", "/api/businesses/lines", line(g, "未指定", null, true)).andExpect(status().isBadRequest());
        send("POST", "/api/businesses/lines", line(g, "盒马鲜生", "盒马", true)).andExpect(status().isOk());
        assertThat(brandOf(project("盒马"))).isEqualTo("盒马鲜生");
    }

    @Test
    @WithMockUser(username = "sales1", roles = "SALES")
    void onlyAdminMaintainsDictionary() throws Exception {
        send("POST", "/api/businesses/groups", Map.of("name", "越权集团", "sort", 1, "active", true)).andExpect(
            status().isForbidden()
        );
        getJson("/api/businesses");
    }
}
