package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class QuoteWorkflowTest extends ApiTestSupport {

    @Test
    void quoteReceiptRefundAndNoCostLeak() throws Exception {
        String project = project(),
            product = product();
        String id = postJson("/api/quotes", quoteInput(project, product, "80.005")).path("id").asText();
        var quote = getJson("/api/quotes/" + id);
        assertThat(quote.path("amount").decimalValue()).isEqualByComparingTo("246.90");
        assertThat(quote.path("cost").decimalValue()).isEqualByComparingTo("160.01");
        assertThat(quote.path("profit").decimalValue()).isEqualByComparingTo("86.89");
        assertThat(getJson("/api/cash/ledger").size()).isZero();
        postJson("/api/quotes/" + id + "/issue", Map.of());
        var copy = getJson("/api/quotes/" + id + "/customer-copy");
        assertThat(copy.has("cost")).isFalse();
        assertThat(copy.has("profit")).isFalse();
        assertThat(copy.path("items").get(0).has("unit_cost")).isFalse();
        assertThat(copy.path("items").get(0).has("source")).isFalse();
        postJson("/api/quotes/" + id + "/accept", Map.of("dueDate", LocalDate.now().minusDays(1).toString()));
        // 回款统一按合同登记：未填合同号的门店，合同键为 “#项目编号”
        String customer = jdbcTemplate.queryForObject(
            "SELECT customer_id FROM project WHERE id=?",
            String.class,
            project
        );
        String key = "#" + jdbcTemplate.queryForObject("SELECT code FROM project WHERE id=?", String.class, project);
        var receipt = new HashMap<String, Object>(
            Map.of(
                "customerId",
                customer,
                "contractKey",
                key,
                "kind",
                "RECEIPT",
                "amount",
                "100.00",
                "paidOn",
                LocalDate.now().toString(),
                "reference",
                "BANK-01"
            )
        );
        String entry = postJson("/api/contracts/receipts", receipt).path("id").asText();
        // 凭证号重复、超过剩余应收
        mvc
            .perform(
                post("/api/contracts/receipts")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(receipt))
            )
            .andExpect(status().isBadRequest());
        receipt.put("reference", "BANK-02");
        receipt.put("amount", "147.00");
        mvc
            .perform(
                post("/api/contracts/receipts")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(receipt))
            )
            .andExpect(status().isBadRequest());
        receipt.put("kind", "REFUND");
        receipt.put("amount", "20.00");
        receipt.put("reference", "REFUND-01");
        receipt.put("reversesId", entry);
        postJson("/api/contracts/receipts", receipt);
        JsonNode contract = null;
        for (var c : getJson("/api/contracts")) if (c.path("contract_key").asText().equals(key)) contract = c;
        assertThat(contract.path("received").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(contract.path("outstanding").decimalValue()).isEqualByComparingTo("166.90");
        assertThat(contract.path("overdue").decimalValue()).isEqualByComparingTo("166.90");
        // 统一回款流水：两条合同流水，回款分摊到门店
        var ledger = getJson("/api/cash/ledger");
        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.toString()).contains("BANK-01#1", "REFUND-01");
        // 旧的按门店登记回款入口已取消
        mvc
            .perform(post("/api/cash").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().is4xxClientError());
        String second = postJson("/api/quotes", quoteInput(project, product, "90")).path("id").asText();
        postJson("/api/quotes/" + second + "/issue", Map.of());
        mvc
            .perform(
                post("/api/quotes/" + second + "/accept")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"dueDate\":\"2026-12-31\"}")
            )
            .andExpect(status().isConflict());
    }

    @Test
    void legacyStoreEntriesStayVisibleAndRefundable() throws Exception {
        String project = project(),
            product = product();
        String id = postJson("/api/quotes", quoteInput(project, product, "50")).path("id").asText();
        postJson("/api/quotes/" + id + "/issue", Map.of());
        postJson("/api/quotes/" + id + "/accept", Map.of("dueDate", LocalDate.now().plusDays(10).toString()));
        // 早期按门店登记的回款（未关联合同收款）
        String legacy = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO cash_entry(id,quotation_id,kind,amount,paid_on,reference) VALUES(?,?,'RECEIPT',?,?,?)",
            legacy,
            id,
            new java.math.BigDecimal("60.00"),
            LocalDate.now(),
            "OLD-" + legacy.substring(0, 6)
        );
        var row = getJson("/api/cash/ledger").get(0);
        assertThat(row.path("source").asText()).isEqualTo("STORE");
        assertThat(row.path("contract_key").asText()).startsWith("#");
        var refund = new HashMap<String, Object>(
            Map.of(
                "reversesId",
                legacy,
                "amount",
                "70.00",
                "paidOn",
                LocalDate.now().toString(),
                "reference",
                "OLD-R-" + legacy.substring(0, 6)
            )
        );
        mvc
            .perform(
                post("/api/cash/legacy-refund")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(refund))
            )
            .andExpect(status().isBadRequest());
        refund.put("amount", "10.00");
        postJson("/api/cash/legacy-refund", refund);
        assertThat(getJson("/api/cash/ledger").size()).isEqualTo(2);
    }

    @Test
    void missingCostDoesNotBecomeZero() throws Exception {
        String id = postJson("/api/quotes", quoteInput(project(), product(), null)).path("id").asText();
        var quote = getJson("/api/quotes/" + id);
        assertThat(quote.path("complete").asBoolean()).isFalse();
        assertThat(quote.path("cost").isNull()).isTrue();
        assertThat(quote.path("profit").isNull()).isTrue();
        assertThat(quote.path("gross_margin").isNull()).isTrue();
    }
}
