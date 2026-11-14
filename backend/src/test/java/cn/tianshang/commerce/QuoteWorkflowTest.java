package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
        assertThat(getJson("/api/cash/balances").size()).isZero();
        postJson("/api/quotes/" + id + "/issue", Map.of());
        var copy = getJson("/api/quotes/" + id + "/customer-copy");
        assertThat(copy.has("cost")).isFalse();
        assertThat(copy.has("profit")).isFalse();
        assertThat(copy.path("items").get(0).has("unit_cost")).isFalse();
        assertThat(copy.path("items").get(0).has("source")).isFalse();
        postJson("/api/quotes/" + id + "/accept", Map.of("dueDate", LocalDate.now().minusDays(1).toString()));
        var receipt = Map.of(
            "quotationId",
            id,
            "kind",
            "RECEIPT",
            "amount",
            "100.00",
            "paidOn",
            LocalDate.now().toString(),
            "reference",
            "BANK-01"
        );
        String entry = postJson("/api/cash", receipt).path("id").asText();
        mvc
            .perform(
                post("/api/cash")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(receipt))
            )
            .andExpect(status().isConflict());
        var excessive = Map.of(
            "quotationId",
            id,
            "kind",
            "RECEIPT",
            "amount",
            "147.00",
            "paidOn",
            LocalDate.now().toString(),
            "reference",
            "BANK-02"
        );
        mvc
            .perform(
                post("/api/cash")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(excessive))
            )
            .andExpect(status().isBadRequest());
        postJson(
            "/api/cash",
            Map.of(
                "quotationId",
                id,
                "kind",
                "REFUND",
                "amount",
                "20.00",
                "paidOn",
                LocalDate.now().toString(),
                "reference",
                "REFUND-01",
                "reversesId",
                entry
            )
        );
        var balance = getJson("/api/cash/balances").get(0);
        assertThat(balance.path("received").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(balance.path("outstanding").decimalValue()).isEqualByComparingTo("166.90");
        assertThat(balance.path("overdue").asBoolean()).isTrue();
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
    void missingCostDoesNotBecomeZero() throws Exception {
        String id = postJson("/api/quotes", quoteInput(project(), product(), null)).path("id").asText();
        var quote = getJson("/api/quotes/" + id);
        assertThat(quote.path("complete").asBoolean()).isFalse();
        assertThat(quote.path("cost").isNull()).isTrue();
        assertThat(quote.path("profit").isNull()).isTrue();
        assertThat(quote.path("gross_margin").isNull()).isTrue();
    }
}
