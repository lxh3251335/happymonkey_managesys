package cn.tianshang.commerce;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${TEST_DB_USER:sa}",
        "spring.datasource.password=${TEST_DB_PASSWORD:}",
        "app.admin-password=TestAdminPassword!",
        "app.import-source=false",
        "app.attachment-dir=target/test-attachments",
    }
)
@AutoConfigureMockMvc
@Transactional
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 会话校验要求账户在库中启用且角色一致，为测试中的模拟账户建档（随测试事务回滚）。 */
    @org.junit.jupiter.api.BeforeEach
    void seedMockUsers() {
        for (String sc : List.of("小象", "小象前置仓"))
            if (
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scenario WHERE name=?", Integer.class, sc) == 0
            ) jdbcTemplate.update("INSERT INTO scenario(id,name) VALUES(?,?)", UUID.randomUUID().toString(), sc);
        for (String[] u : new String[][] {
            { "reader", "VIEWER" },
            { "viewer1", "VIEWER" },
            { "finance", "FINANCE" },
            { "finance1", "FINANCE" },
            { "sales1", "SALES" },
            { "sales2", "SALES" },
            { "manager1", "MANAGER" },
        })
            if (
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?", Integer.class, u[0]) == 0
            ) jdbcTemplate.update(
                "INSERT INTO app_user(id,username,password_hash,display_name,role) VALUES(?,?,'x',?,?)",
                UUID.randomUUID().toString(),
                u[0],
                u[0],
                u[1]
            );
    }

    protected JsonNode postJson(String path, Object body) throws Exception {
        return read(
            mvc
                .perform(
                    post(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body))
                )
                .andExpect(status().isOk())
                .andReturn()
        );
    }

    protected JsonNode getJson(String path) throws Exception {
        return read(mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
    }

    protected JsonNode read(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return content.isBlank() ? mapper.nullNode() : mapper.readTree(content);
    }

    protected String customer() throws Exception {
        return postJson("/api/partners", Map.of("name", "测试客户" + UUID.randomUUID(), "kind", "CUSTOMER"))
            .path("id")
            .asText();
    }

    protected String project() throws Exception {
        return postJson("/api/projects", Map.of("name", "测试小象门店", "brand", "小象", "customerId", customer()))
            .path("id")
            .asText();
    }

    protected String product() throws Exception {
        return postJson(
            "/api/products",
            Map.of(
                "scenario",
                "小象",
                "name",
                "测试电箱" + UUID.randomUUID(),
                "specification",
                "650*850*250",
                "unit",
                "台"
            )
        )
            .path("id")
            .asText();
    }

    protected Map<String, Object> quoteInput(String project, String product, Object cost) {
        var line = new HashMap<String, Object>();
        line.put("productId", product);
        line.put("quantity", 2);
        line.put("unitPrice", "123.45");
        line.put("unitCost", cost);
        return Map.of(
            "projectId",
            project,
            "taxBasis",
            "INCLUDED",
            "taxRate",
            "0.13",
            "validUntil",
            java.time.LocalDate.now().plusDays(30).toString(),
            "items",
            List.of(line)
        );
    }
}
