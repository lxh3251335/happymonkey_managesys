package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/** 角色：管理层审批与查看成本；业务员、只读看不到成本与毛利；业务员报价成本取自供应商模板。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class RoleAccessTest extends ApiTestSupport {

    private static final List<String> COST_FIELDS = List.of(
        "\"cost\"",
        "\"unit_cost\"",
        "\"material_cost\"",
        "\"profit\"",
        "\"gross_margin\"",
        "\"reference_cost\"",
        "\"known_cost\""
    );

    private ResultActions as(String user, String role, String method, String path, Object body) throws Exception {
        var req = method.equals("PUT") ? put(path) : method.equals("GET") ? get(path) : post(path);
        if (body != null) req = req.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(req.with(csrf()).with(user(user).roles(role)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return read(r.andExpect(status().isOk()).andReturn());
    }

    /** 项目类型 + 供应商成本模板（电控箱：材料 1200 + 人工 200 + 财务 100）。 */
    private String[] catalog() throws Exception {
        String box = postJson(
            "/api/products",
            Map.of(
                "supplierId",
                testSupplier(),
                "scenario",
                "小象前置仓",
                "name",
                "电控箱" + UUID.randomUUID(),
                "specification",
                "s",
                "unit",
                "台"
            )
        )
            .path("id")
            .asText();
        String sup = postJson("/api/partners", Map.of("name", "角色供应商" + UUID.randomUUID(), "kind", "SUPPLIER"))
            .path("id")
            .asText();
        String type = postJson(
            "/api/project-types",
            Map.of(
                "name",
                "角色类型" + UUID.randomUUID(),
                "brand",
                "小象",
                "active",
                true,
                "items",
                List.of(Map.of("productId", box, "quantity", 1, "unitPrice", "5500"))
            )
        )
            .path("id")
            .asText();
        postJson(
            "/api/supplier-templates",
            Map.of(
                "supplierId",
                sup,
                "projectTypeId",
                type,
                "active",
                true,
                "items",
                List.of(Map.of("productId", box, "materialCost", "1200", "laborCost", "200", "financeCost", "100"))
            )
        );
        return new String[] { type, sup, box };
    }

    @Test
    void salesQuoteCostsComeFromTemplateAndAreHiddenFromSales() throws Exception {
        var c = catalog();
        String customer = customer();
        String project = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/projects",
                Map.of("name", "角色门店", "brand", "小象", "customerId", customer)
            )
        )
            .path("id")
            .asText();
        // 业务员提交的成本被忽略，按模板取 1500
        var line = new HashMap<String, Object>();
        line.put("productId", c[2]);
        line.put("quantity", 1);
        line.put("unitPrice", "5500");
        line.put("materialCost", "1");
        var q = new HashMap<String, Object>(quoteInput(project, c[2], null));
        q.put("items", List.of(line));
        q.put("projectTypeId", c[0]);
        q.put("supplierId", c[1]);
        String quote = ok(as("sales1", "SALES", "POST", "/api/quotes", q)).path("id").asText();
        var full = ok(as("finance", "FINANCE", "GET", "/api/quotes/" + quote, null));
        assertThat(full.path("items").get(0).path("unit_cost").decimalValue()).isEqualByComparingTo("1500");
        assertThat(full.path("gross_margin").isNull()).isFalse();
        // 业务员与只读看不到成本与毛利
        for (String[] u : new String[][] { { "sales1", "SALES" }, { "reader", "VIEWER" } }) {
            String detail = ok(as(u[0], u[1], "GET", "/api/quotes/" + quote, null)).toString();
            String list = ok(as(u[0], u[1], "GET", "/api/quotes", null)).toString();
            String products = ok(as(u[0], u[1], "GET", "/api/products", null)).toString();
            String prefill = ok(
                as(u[0], u[1], "GET", "/api/project-types/" + c[0] + "/prefill?supplierId=" + c[1], null)
            ).toString();
            String dash = ok(as(u[0], u[1], "GET", "/api/dashboard", null)).toString();
            for (String f : COST_FIELDS) {
                assertThat(detail).doesNotContain(f);
                assertThat(list).doesNotContain(f);
                assertThat(products).doesNotContain(f);
                assertThat(prefill).doesNotContain(f);
                assertThat(dash).doesNotContain(f);
            }
            assertThat(detail).contains("\"unit_price\"");
            as(u[0], u[1], "GET", "/api/offers", null).andExpect(status().isForbidden());
            mvc
                .perform(get("/api/excel/supplier-templates").with(user(u[0]).roles(u[1])))
                .andExpect(status().isForbidden());
        }
        // 管理层、财务看得到
        assertThat(ok(as("manager1", "MANAGER", "GET", "/api/quotes/" + quote, null)).toString()).contains(
            "\"unit_cost\""
        );
        assertThat(ok(as("manager1", "MANAGER", "GET", "/api/dashboard", null)).toString()).contains(
            "\"gross_margin\""
        );
        as("manager1", "MANAGER", "GET", "/api/offers", null).andExpect(status().isOk());
    }

    @Test
    void contractExcelExportDropsCostColumnsForSales() throws Exception {
        byte[] admin = mvc.perform(get("/api/excel/contracts")).andReturn().getResponse().getContentAsByteArray();
        byte[] sales = mvc
            .perform(get("/api/excel/contracts").with(user("sales1").roles("SALES")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertThat(headers(admin)).contains("成本", "毛利率");
        assertThat(headers(sales)).doesNotContain("成本", "毛利率").contains("合同金额");
    }

    private static List<String> headers(byte[] xlsx) throws Exception {
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var out = new ArrayList<String>();
            for (var cell : wb.getSheetAt(0).getRow(0)) out.add(cell.getStringCellValue().replace("*", ""));
            return out;
        }
    }

    @Test
    void managerApprovesChangesButCannotConfigureSystem() throws Exception {
        String customer = customer(),
            product = product();
        String project = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/projects",
                Map.of("name", "审批门店", "brand", "小象", "customerId", customer)
            )
        )
            .path("id")
            .asText();
        String quote = ok(as("sales1", "SALES", "POST", "/api/quotes", quoteInput(project, product, "50")))
            .path("id")
            .asText();
        ok(as("sales1", "SALES", "POST", "/api/quotes/" + quote + "/issue", Map.of()));
        String key = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + quote,
                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "signedOn", LocalDate.now().toString())
            )
        )
            .path("contractKey")
            .asText();
        String path = "/api/contracts/info?customerId=" + customer + "&contractKey=" + key;
        var info = new HashMap<String, Object>();
        info.put("status", "SIGNED");
        info.put("signedOn", LocalDate.now().toString());
        info.put("title", "改名申请");
        info.put(
            "ownerId",
            jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username='sales1'", String.class)
        );
        String change = ok(as("sales1", "SALES", "PUT", path, Map.of("info", info, "reason", "客户要求")))
            .path("changeId")
            .asText();
        // 管理层看到待审批并批准
        assertThat(ok(as("manager1", "MANAGER", "GET", "/api/contracts/changes/pending", null)).toString()).contains(
            change
        );
        ok(as("manager1", "MANAGER", "POST", "/api/contracts/changes/" + change + "/approve", Map.of("note", "同意")));
        assertThat(
            jdbcTemplate.queryForObject("SELECT title FROM contract WHERE contract_no=?", String.class, key)
        ).isEqualTo("改名申请");
        // 财务不能审批；管理层不能做系统配置
        as("finance", "FINANCE", "POST", "/api/contracts/changes/" + change + "/approve", Map.of()).andExpect(
            status().isForbidden()
        );
        as("manager1", "MANAGER", "GET", "/api/admin/users", null).andExpect(status().isForbidden());
        as(
            "manager1",
            "MANAGER",
            "POST",
            "/api/dictionaries/cities",
            Map.of("name", "新城", "sort", 1, "active", true)
        ).andExpect(status().isForbidden());
        // 管理员可创建管理层账户
        ok(
            as(
                "admin",
                "ADMIN",
                "POST",
                "/api/admin/users",
                Map.of(
                    "username",
                    "boss_" + UUID.randomUUID().toString().substring(0, 6),
                    "displayName",
                    "总经理",
                    "role",
                    "MANAGER",
                    "password",
                    "ManagerPass2026!"
                )
            )
        );
    }
}
