package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.master.Cities;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class CatalogQuoteFlowTest extends ApiTestSupport {

    @Test
    void citiesDetectedFromStoreNames() {
        assertThat(Cities.detect("LK-260007 小象石家庄北焦双塔站")).isEqualTo("石家庄");
        assertThat(Cities.detect("LK-260008 小象山西太原开化寺站")).isEqualTo("太原");
        assertThat(Cities.detect("快乐猴燕郊三河京航城店")).isEqualTo("廊坊");
        assertThat(Cities.detect("LK-260058 快乐猴大兴兴盛园店")).isEqualTo("北京");
        assertThat(Cities.detect("小象义乌江滨路站")).isEqualTo("金华");
        assertThat(Cities.detect("LK-260022 快乐猴大东商贸")).isNull();
    }

    private String supplier() throws Exception {
        return postJson("/api/partners", Map.of("name", "测试供应商" + UUID.randomUUID(), "kind", "SUPPLIER"))
            .path("id")
            .asText();
    }

    private String productNamed(String name) throws Exception {
        return postJson(
            "/api/products",
            Map.of("scenario", "小象前置仓", "name", name + UUID.randomUUID(), "specification", "s", "unit", "台")
        )
            .path("id")
            .asText();
    }

    /** 建一个项目类型（两项配置）和一个供应商成本模板（只覆盖第一项）。 */
    private String[] setup() throws Exception {
        String box = productNamed("物联网电控箱"),
            main = productNamed("总配电箱"),
            sup = supplier();
        String type = postJson(
            "/api/project-types",
            Map.of(
                "name",
                "测试前置仓" + UUID.randomUUID(),
                "brand",
                "小象",
                "active",
                true,
                "items",
                List.of(
                    Map.of("productId", box, "quantity", 1, "unitPrice", "5500"),
                    Map.of("productId", main, "quantity", 2, "unitPrice", "3438")
                )
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
        return new String[] { type, sup, box, main };
    }

    @Test
    void prefillBringsTypePricesAndSupplierCosts() throws Exception {
        var s = setup();
        var pre = getJson("/api/project-types/" + s[0] + "/prefill?supplierId=" + s[1]);
        var items = pre.path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("unit_price").decimalValue()).isEqualByComparingTo("5500");
        assertThat(items.get(0).path("unit_cost").decimalValue()).isEqualByComparingTo("1500");
        assertThat(items.get(1).path("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(items.get(1).path("unit_cost").isNull()).isTrue();
        // 没有模板的供应商
        mvc
            .perform(get("/api/project-types/" + s[0] + "/prefill").param("supplierId", supplier()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void quoteWithNewStoreSplitsCostsAndAccountantCanCorrect() throws Exception {
        var s = setup();
        String customer = customer();
        var line1 = new HashMap<String, Object>(
            Map.of("productId", s[2], "quantity", 1, "unitPrice", "5500", "materialCost", "1200", "laborCost", "200")
        );
        line1.put("financeCost", "100");
        var line2 = new HashMap<String, Object>(Map.of("productId", s[3], "quantity", 2, "unitPrice", "3500"));
        var body = new HashMap<String, Object>();
        body.put(
            "newProject",
            Map.of("customerId", customer, "name", "小象天津丰泽站", "projectTypeId", s[0], "contractNo", "HT-FZ-1")
        );
        body.put("supplierId", s[1]);
        body.put("taxBasis", "INCLUDED");
        body.put("taxRate", "0.13");
        body.put("validUntil", LocalDate.now().plusDays(30).toString());
        body.put("items", List.of(line1, line2));
        String quote = postJson("/api/quotes", body).path("id").asText();

        var q = getJson("/api/quotes/" + quote);
        assertThat(q.path("city").asText()).isEqualTo("天津");
        assertThat(q.path("brand").asText()).isEqualTo("小象");
        assertThat(q.path("supplier_name").asText()).startsWith("测试供应商");
        assertThat(q.path("project_type_name").asText()).startsWith("测试前置仓");
        assertThat(q.path("amount").decimalValue()).isEqualByComparingTo("12500");
        var item1 = q.path("items").get(0);
        assertThat(item1.path("unit_cost").decimalValue()).isEqualByComparingTo("1500");
        assertThat(item1.path("labor_cost").decimalValue()).isEqualByComparingTo("200");
        assertThat(q.path("cost").isNull()).isTrue(); // 第二行缺成本，利润不可计算

        // 会计补成本
        String item2 = q.path("items").get(1).path("id").asText();
        var fix = Map.of(
            "reason",
            "供应商对账",
            "items",
            List.of(Map.of("itemId", item2, "materialCost", "3000", "laborCost", "100", "financeCost", "50"))
        );
        mvc
            .perform(
                put("/api/quotes/" + quote + "/costs")
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(fix))
            )
            .andExpect(status().isForbidden());
        var res = read(
            mvc
                .perform(
                    put("/api/quotes/" + quote + "/costs")
                        .with(csrf())
                        .with(user("finance1").roles("FINANCE"))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(fix))
                )
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(res.path("changed").asInt()).isEqualTo(1);
        q = getJson("/api/quotes/" + quote);
        assertThat(q.path("cost").decimalValue()).isEqualByComparingTo("7800"); // 1500 + 2*3150
        assertThat(q.path("profit").decimalValue()).isEqualByComparingTo("4700");
        assertThat(q.path("amount").decimalValue()).isEqualByComparingTo("12500");
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='QUOTE_COST_CORRECTED' AND entity_id=?",
                Integer.class,
                quote
            )
        ).isEqualTo(1);

        postJson("/api/quotes/" + quote + "/issue", Map.of());
        var copy = getJson("/api/quotes/" + quote + "/customer-copy");
        assertThat(copy.path("city").asText()).isEqualTo("天津");
        assertThat(copy.path("contract_no").asText()).isEqualTo("HT-FZ-1");
        assertThat(copy.path("template").path("contract_terms").asText()).contains("质保");
        assertThat(copy.toString()).doesNotContain("labor_cost", "material_cost", "unit_cost", "profit");

        boolean tianjin = false;
        for (var c : getJson("/api/dashboard").path("by_city")) tianjin |= c.path("name").asText().equals("天津");
        assertThat(tianjin).isTrue();
    }

    @Test
    void quoteNeedsExactlyOneProjectSource() throws Exception {
        var body = new HashMap<String, Object>(quoteInput(project(), product(), "1"));
        body.put("newProject", Map.of("customerId", customer(), "name", "x", "projectTypeId", "type-kuailehou"));
        mvc
            .perform(
                post("/api/quotes")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(body))
            )
            .andExpect(status().isBadRequest());
        body.remove("newProject");
        body.remove("projectId");
        mvc
            .perform(
                post("/api/quotes")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(body))
            )
            .andExpect(status().isBadRequest());
    }
}
