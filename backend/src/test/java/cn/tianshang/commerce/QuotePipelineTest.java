package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.quote.QuoteStage;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultMatcher;

/** 报价跟进（阶段、未成交、恢复跟进）与客户报价模板。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class QuotePipelineTest extends ApiTestSupport {

    private final String due = LocalDate.now().plusDays(30).toString();

    private JsonNode quoteRow(String id) throws Exception {
        for (var q : getJson("/api/quotes")) if (q.path("id").asText().equals(id)) return q;
        throw new AssertionError("报价不在列表中：" + id);
    }

    private JsonNode projectRow(String id) throws Exception {
        for (var p : getJson("/api/projects")) if (p.path("id").asText().equals(id)) return p;
        throw new AssertionError("项目不在列表中：" + id);
    }

    private void expect(String path, Object body, ResultMatcher expected) throws Exception {
        mvc
            .perform(post(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(expected);
    }

    @Test
    void stageRules() {
        assertThat(QuoteStage.of("DRAFT", "NONE", 1, null)).isEqualTo("DRAFT");
        assertThat(QuoteStage.of("ISSUED", "NONE", 1, null)).isEqualTo("ISSUED");
        // 历史报价、门店没有合同：仍需客户确认
        assertThat(QuoteStage.of("HISTORICAL", "NONE", 1, null)).isEqualTo("ISSUED");
        assertThat(QuoteStage.of("ACCEPTED", "NONE", 1, 1)).isEqualTo("ACCEPTED");
        assertThat(QuoteStage.of("ACCEPTED", "DRAFT", 1, 1)).isEqualTo("CONTRACTED");
        // 历史门店只有合同号、没有确认动作：报价就是合同依据
        assertThat(QuoteStage.of("HISTORICAL", "SIGNED", 1, null)).isEqualTo("CONTRACTED");
        // 已签门店上管理层另出的新版本仍在跟进；被已确认版本取代的旧版本归档
        assertThat(QuoteStage.of("ISSUED", "SIGNED", 3, 2)).isEqualTo("ISSUED");
        assertThat(QuoteStage.of("ISSUED", "SIGNED", 1, 2)).isEqualTo("ARCHIVED");
        assertThat(QuoteStage.of("LOST", "SIGNED", 1, 2)).isEqualTo("LOST");
    }

    @Test
    void lostQuoteLeavesPipelineAndCanBeReopened() throws Exception {
        String project = project(),
            quote = postJson("/api/quotes", quoteInput(project, product(), "50")).path("id").asText();
        assertThat(quoteRow(quote).path("stage").asText()).isEqualTo("DRAFT");
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        assertThat(quoteRow(quote).path("stage").asText()).isEqualTo("ISSUED");

        // 必须填写原因
        expect("/api/quotes/" + quote + "/lose", Map.of("reason", " "), status().isBadRequest());
        postJson("/api/quotes/" + quote + "/lose", Map.of("reason", "价格未谈拢"));
        var lost = quoteRow(quote);
        assertThat(lost.path("stage").asText()).isEqualTo("LOST");
        assertThat(lost.path("lost_reason").asText()).isEqualTo("价格未谈拢");
        // 未成交的报价不能再标记、不能生成合同、不能作为参考
        expect("/api/quotes/" + quote + "/lose", Map.of("reason", "x"), status().isBadRequest());
        expect("/api/contracts/from-quote/" + quote, Map.of("dueDate", due), status().isBadRequest());
        mvc.perform(get("/api/quotes/reference").param("projectId", project)).andExpect(status().isBadRequest());
        assertThat(
            jdbcTemplate.queryForList("SELECT action FROM audit_log WHERE entity_id=? AND action='QUOTE_LOST'", quote)
        ).hasSize(1);

        // 恢复跟进：回到关闭前的状态
        postJson("/api/quotes/" + quote + "/reopen", Map.of());
        var reopened = quoteRow(quote);
        assertThat(reopened.path("stage").asText()).isEqualTo("ISSUED");
        assertThat(reopened.path("lost_reason").isNull()).isTrue();
        expect("/api/quotes/" + quote + "/reopen", Map.of(), status().isBadRequest());
    }

    @Test
    void confirmedQuoteCanBeLostOnlyBeforeContract() throws Exception {
        // 客户已确认、还没生成合同：可以标记未成交，撤销应收，恢复后需重新确认
        String p1 = project(),
            q1 = postJson("/api/quotes", quoteInput(p1, product(), "50")).path("id").asText();
        postJson("/api/quotes/" + q1 + "/issue", Map.of());
        postJson("/api/quotes/" + q1 + "/accept", Map.of("dueDate", due));
        assertThat(quoteRow(q1).path("stage").asText()).isEqualTo("ACCEPTED");
        assertThat(projectRow(p1).path("confirmed_quotes").asInt()).isEqualTo(1);
        postJson("/api/quotes/" + q1 + "/lose", Map.of("reason", "项目取消或延期"));
        assertThat(projectRow(p1).path("confirmed_quotes").asInt()).isZero();
        var row = jdbcTemplate.queryForMap("SELECT accepted_project,due_date,lost_from FROM quotation WHERE id=?", q1);
        assertThat(row.get("accepted_project")).isNull();
        assertThat(row.get("due_date")).isNull();
        assertThat(row.get("lost_from")).isEqualTo("ISSUED");
        postJson("/api/quotes/" + q1 + "/reopen", Map.of());
        assertThat(quoteRow(q1).path("stage").asText()).isEqualTo("ISSUED");

        // 已生成合同：报价转入合同台账，不能再标记未成交
        String p2 = project(),
            q2 = postJson("/api/quotes", quoteInput(p2, product(), "50")).path("id").asText();
        postJson("/api/quotes/" + q2 + "/issue", Map.of());
        postJson("/api/contracts/from-quote/" + q2, Map.of("dueDate", due, "contractNo", "TJ-PIPE-01"));
        assertThat(quoteRow(q2).path("stage").asText()).isEqualTo("CONTRACTED");
        assertThat(projectRow(p2).path("contract_state").asText()).isEqualTo("DRAFT");
        expect("/api/quotes/" + q2 + "/lose", Map.of("reason", "x"), status().isBadRequest());
    }

    @Test
    void salesCanOnlyCloseOwnQuotesAndViewerCannot() throws Exception {
        String quote = postJson("/api/quotes", quoteInput(project(), product(), "50")).path("id").asText();
        var body = mapper.writeValueAsString(Map.of("reason", "价格未谈拢"));
        mvc
            .perform(
                post("/api/quotes/" + quote + "/lose")
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().isForbidden());
        // 门店不归该业务员负责
        mvc
            .perform(
                post("/api/quotes/" + quote + "/lose")
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().is4xxClientError());
        assertThat(quoteRow(quote).path("stage").asText()).isEqualTo("DRAFT");
    }

    // ---------- 草稿的修改与删除 ----------

    private boolean quoteExists(String id) throws Exception {
        for (var q : getJson("/api/quotes")) if (q.path("id").asText().equals(id)) return true;
        return false;
    }

    private boolean projectExists(String id) throws Exception {
        for (var p : getJson("/api/projects")) if (p.path("id").asText().equals(id)) return true;
        return false;
    }

    @Test
    void draftCanBeEditedButIssuedQuoteCannot() throws Exception {
        String project = project(),
            first = product(),
            second = product();
        String quote = postJson("/api/quotes", quoteInput(project, first, "50")).path("id").asText();
        // 改草稿：明细整体替换，版本号不变
        var line = new HashMap<String, Object>();
        line.put("productId", second);
        line.put("quantity", 3);
        line.put("unitPrice", "200");
        line.put("unitCost", "80");
        var changed = new HashMap<>(quoteInput(project, first, "50"));
        changed.put("items", List.of(line));
        changed.put("note", "改过的备注");
        mvc
            .perform(
                put("/api/quotes/" + quote)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(changed))
            )
            .andExpect(status().isOk());
        var detail = getJson("/api/quotes/" + quote);
        assertThat(detail.path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.path("amount").decimalValue()).isEqualByComparingTo("600");
        assertThat(detail.path("note").asText()).isEqualTo("改过的备注");
        assertThat(detail.path("items").size()).isEqualTo(1);
        assertThat(detail.path("items").get(0).path("product_id").asText()).isEqualTo(second);
        // 只读角色不能改；不负责该门店的业务员也不能改
        for (var who : List.of(new String[] { "viewer1", "VIEWER" }, new String[] { "sales1", "SALES" }))
            mvc
                .perform(
                    put("/api/quotes/" + quote)
                        .with(csrf())
                        .with(user(who[0]).roles(who[1]))
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(changed))
                )
                .andExpect(status().is4xxClientError());
        // 出具之后不能再改、不能删
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        mvc
            .perform(
                put("/api/quotes/" + quote)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(changed))
            )
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/quotes/" + quote).with(csrf())).andExpect(status().isBadRequest());
        assertThat(getJson("/api/quotes/" + quote).path("amount").decimalValue()).isEqualByComparingTo("600");
    }

    @Test
    void draftCanBeDeletedWithItsUnusedStore() throws Exception {
        String product = product();
        // 门店只有这一份草稿：可以连门店一起删，留下删除日志
        String project = project(),
            quote = postJson("/api/quotes", quoteInput(project, product, "50")).path("id").asText();
        mvc
            .perform(delete("/api/quotes/" + quote).with(csrf()).with(user("viewer1").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/quotes/" + quote).param("withStore", "true").with(csrf())).andExpect(status().isOk());
        assertThat(quoteExists(quote)).isFalse();
        assertThat(projectExists(project)).isFalse();
        assertThat(
            jdbcTemplate.queryForList(
                "SELECT action FROM audit_log WHERE entity_id=? AND action='QUOTE_DELETED'",
                quote
            )
        ).hasSize(1);

        // 门店还有别的报价：不能连门店一起删，整个操作不生效；只删草稿可以，门店保留
        String busy = project(),
            kept = postJson("/api/quotes", quoteInput(busy, product, "50")).path("id").asText();
        postJson("/api/quotes/" + kept + "/issue", Map.of());
        String draft = postJson("/api/quotes", quoteInput(busy, product, "50")).path("id").asText();
        mvc
            .perform(delete("/api/quotes/" + draft).param("withStore", "true").with(csrf()))
            .andExpect(status().isBadRequest());
        assertThat(quoteExists(draft)).isTrue();
        mvc.perform(delete("/api/quotes/" + draft).with(csrf())).andExpect(status().isOk());
        assertThat(quoteExists(draft)).isFalse();
        assertThat(quoteExists(kept)).isTrue();
        assertThat(projectExists(busy)).isTrue();
    }

    // ---------- 客户报价模板 ----------

    private String type(String a, String b) throws Exception {
        return postJson(
            "/api/project-types",
            Map.of(
                "name",
                "模板测试类型" + UUID.randomUUID(),
                "brand",
                "小象",
                "active",
                true,
                "items",
                List.of(
                    Map.of("productId", a, "quantity", 1, "unitPrice", "5500"),
                    Map.of("productId", b, "quantity", 2, "unitPrice", "3000")
                )
            )
        )
            .path("id")
            .asText();
    }

    private Map<String, Object> template(String customer, String type, boolean active, List<?> items) {
        return Map.of("customerId", customer, "projectTypeId", type, "active", active, "items", items);
    }

    @Test
    void customerTemplateOverridesStandardSetup() throws Exception {
        String a = product(),
            b = product(),
            extra = product(),
            type = type(a, b),
            vip = customer(),
            other = customer();
        // 该客户的协议：第一项降价，不要第二项，另加一项
        var items = List.of(
            Map.of("productId", a, "quantity", 1, "unitPrice", "5200"),
            Map.of("productId", extra, "quantity", 3, "unitPrice", "800")
        );
        postJson("/api/customer-templates", template(vip, type, true, items));

        var forVip = getJson("/api/project-types/" + type + "/prefill?customerId=" + vip);
        assertThat(forVip.path("source").asText()).isEqualTo("CUSTOMER");
        assertThat(forVip.path("items").size()).isEqualTo(2);
        assertThat(forVip.path("items").get(0).path("unit_price").decimalValue()).isEqualByComparingTo("5200");
        assertThat(forVip.path("items").get(1).path("product_id").asText()).isEqualTo(extra);
        // 没有模板的客户、未指定客户：仍按项目类型标准配置
        var forOther = getJson("/api/project-types/" + type + "/prefill?customerId=" + other);
        assertThat(forOther.path("source").asText()).isEqualTo("TYPE");
        assertThat(forOther.path("items").get(0).path("unit_price").decimalValue()).isEqualByComparingTo("5500");
        assertThat(getJson("/api/project-types/" + type + "/prefill").path("source").asText()).isEqualTo("TYPE");

        // 同一客户同一类型只有一份：再次保存整体替换；停用后不再带出
        postJson(
            "/api/customer-templates",
            template(vip, type, false, List.of(Map.of("productId", a, "quantity", 1, "unitPrice", "5100")))
        );
        int count = 0;
        for (var t : getJson("/api/customer-templates"))
            if (t.path("customer_id").asText().equals(vip)) {
                count++;
                assertThat(t.path("items").size()).isEqualTo(1);
                assertThat(t.path("active").asBoolean()).isFalse();
            }
        assertThat(count).isEqualTo(1);
        assertThat(
            getJson("/api/project-types/" + type + "/prefill?customerId=" + vip).path("source").asText()
        ).isEqualTo("TYPE");
    }

    @Test
    void customerTemplateValidationAndRoles() throws Exception {
        String a = product(),
            b = product(),
            type = type(a, b),
            customer = customer();
        var one = List.of(Map.of("productId", a, "quantity", 1, "unitPrice", "5200"));
        // 重复产品、空明细、把供应商当客户：拒绝
        expect(
            "/api/customer-templates",
            template(customer, type, true, List.of(one.get(0), one.get(0))),
            status().isBadRequest()
        );
        expect("/api/customer-templates", template(customer, type, true, List.of()), status().isBadRequest());
        String supplier = postJson("/api/partners", Map.of("name", "供应商" + UUID.randomUUID(), "kind", "SUPPLIER"))
            .path("id")
            .asText();
        expect("/api/customer-templates", template(supplier, type, true, one), status().isBadRequest());
        // 业务员可以维护；只读、财务不行
        var body = mapper.writeValueAsString(template(customer, type, true, one));
        for (var who : List.of(new String[] { "viewer1", "VIEWER" }, new String[] { "finance1", "FINANCE" }))
            mvc
                .perform(
                    post("/api/customer-templates")
                        .with(csrf())
                        .with(user(who[0]).roles(who[1]))
                        .contentType("application/json")
                        .content(body)
                )
                .andExpect(status().isForbidden());
        mvc
            .perform(
                post("/api/customer-templates")
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().isOk());
        // 模板不含成本字段
        assertThat(getJson("/api/customer-templates").toString()).doesNotContain("cost");
    }

    @Test
    void templateDraftFromCustomerHistory() throws Exception {
        String a = product(),
            b = product(),
            type = type(a, b),
            customer = customer();
        mvc
            .perform(
                get("/api/customer-templates/from-history").param("customerId", customer).param("projectTypeId", type)
            )
            .andExpect(status().isBadRequest());
        // 该客户此类型的一份已确认报价、一份更新但未成交的报价：取已确认的
        var store = new HashMap<String, Object>();
        store.put("customerId", customer);
        store.put("name", "历史门店" + UUID.randomUUID());
        store.put("projectTypeId", type);
        var input = new HashMap<String, Object>();
        input.put("newProject", store);
        input.put("taxBasis", "INCLUDED");
        input.put("taxRate", "0.13");
        input.put("validUntil", due);
        input.put("items", List.of(Map.of("productId", a, "quantity", 4, "unitPrice", "5000")));
        String confirmed = postJson("/api/quotes", input).path("id").asText();
        postJson("/api/quotes/" + confirmed + "/issue", Map.of());
        postJson("/api/quotes/" + confirmed + "/accept", Map.of("dueDate", due));
        store.put("name", "丢单门店" + UUID.randomUUID());
        input.put("items", List.of(Map.of("productId", b, "quantity", 9, "unitPrice", "1")));
        String lost = postJson("/api/quotes", input).path("id").asText();
        postJson("/api/quotes/" + lost + "/lose", Map.of("reason", "重复报价"));

        var draft = getJson("/api/customer-templates/from-history?customerId=" + customer + "&projectTypeId=" + type);
        assertThat(draft.path("confirmed").asBoolean()).isTrue();
        assertThat(draft.path("items").size()).isEqualTo(1);
        assertThat(draft.path("items").get(0).path("product_id").asText()).isEqualTo(a);
        assertThat(draft.path("items").get(0).path("quantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(draft.toString()).doesNotContain("cost");
    }
}
