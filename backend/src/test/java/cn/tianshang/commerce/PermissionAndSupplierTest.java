package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

class PermissionAndSupplierTest extends ApiTestSupport {

    @Test
    void anonymousAndReadonlyCannotWrite() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc
            .perform(
                post("/api/projects")
                    .with(user("reader").roles("VIEWER"))
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"门店\",\"brand\":\"小象\",\"customerId\":\"x\"}")
            )
            .andExpect(status().isForbidden());
        mvc
            .perform(
                post("/api/partners")
                    .with(user("admin").roles("ADMIN"))
                    .contentType("application/json")
                    .content("{\"name\":\"供应商\",\"kind\":\"SUPPLIER\"}")
            )
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user("finance").roles("FINANCE"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void compareIncludesTaxFreightAndMinimumQuantity() throws Exception {
        String supplier = postJson("/api/partners", Map.of("name", "测试供应商", "kind", "SUPPLIER"))
            .path("id")
            .asText();
        String product = product();
        var offer = new HashMap<String, Object>();
        offer.put("supplierId", supplier);
        offer.put("productId", product);
        offer.put("unitPrice", "100");
        offer.put("taxBasis", "EXCLUDED");
        offer.put("taxRate", "0.13");
        offer.put("freight", "20");
        offer.put("minQuantity", 2);
        offer.put("leadDays", 5);
        offer.put("quotedOn", LocalDate.now().toString());
        offer.put("validUntil", LocalDate.now().plusDays(5).toString());
        postJson("/api/offers", offer);
        var compared = getJson("/api/offers/compare?productId=" + product + "&quantity=2").get(0);
        assertThat(compared.path("landed_total").decimalValue()).isEqualByComparingTo("246.00");
        assertThat(compared.path("landed_unit").decimalValue()).isEqualByComparingTo("123.00");
        assertThat(compared.path("eligible").asBoolean()).isTrue();
        assertThat(
            getJson("/api/offers/compare?productId=" + product + "&quantity=1").get(0).path("eligible").asBoolean()
        ).isFalse();
    }
}
