package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.master.TaxNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class PartnerInvoiceTest extends ApiTestSupport {

    static final String CODE = "91310000MA1FL7K3QJ";

    @Test
    void taxNumberNormalizedAndCheckDigitVerified() {
        assertThat(TaxNumbers.normalize(" " + CODE.toLowerCase().replace("", " ").trim() + " ")).isEqualTo(CODE);
        assertThat(TaxNumbers.normalize("")).isNull();
        // 改一位，校验位不再匹配
        String broken = CODE.substring(0, 17) + (CODE.charAt(17) == '0' ? '1' : '0');
        assertThatThrownBy(() -> TaxNumbers.normalize(broken)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TaxNumbers.normalize("12345")).isInstanceOf(ApiException.class);
        assertThat(TaxNumbers.normalize("110108551385082")).isEqualTo("110108551385082"); // 旧版 15 位
    }

    private Map<String, Object> customerInput(String taxNo, String bankAccount) {
        var m = new HashMap<String, Object>();
        m.put("name", "开票客户" + UUID.randomUUID());
        m.put("kind", "CUSTOMER");
        m.put("invoiceTitle", "上海开票客户有限公司");
        m.put("taxNo", taxNo);
        m.put("address", "上海市浦东新区示例路 1 号");
        m.put("invoicePhone", "021-12345678");
        m.put("bankName", "测试银行浦东支行");
        m.put("bankAccount", bankAccount);
        m.put("invoiceType", "SPECIAL");
        return m;
    }

    private JsonNode partner(String id) throws Exception {
        for (var p : getJson("/api/partners")) if (p.path("id").asText().equals(id)) return p;
        throw new AssertionError("partner not found");
    }

    @Test
    void invoiceInfoSavedValidatedAndUsedInContractCopy() throws Exception {
        String id = postJson("/api/partners", customerInput(CODE.toLowerCase(), "6222 0000 1111 2222"))
            .path("id")
            .asText();
        var p = partner(id);
        assertThat(p.path("tax_no").asText()).isEqualTo(CODE);
        assertThat(p.path("invoice_type").asText()).isEqualTo("SPECIAL");
        assertThat(p.path("bank_account").asText()).isEqualTo("6222 0000 1111 2222");

        String broken = CODE.substring(0, 17) + (CODE.charAt(17) == '0' ? '1' : '0');
        for (var bad : List.of(customerInput(broken, "6222"), customerInput(CODE, "ABC-123456")))
            mvc
                .perform(
                    post("/api/partners")
                        .with(csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(bad))
                )
                .andExpect(status().isBadRequest());

        // 更新时审计记录变更字段
        var update = customerInput(CODE, "6222000011113333");
        update.put("name", p.path("name").asText());
        update.put("address", "上海市新地址 8 号");
        mvc
            .perform(
                put("/api/partners/" + id)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(update))
            )
            .andExpect(status().isOk());
        String detail = jdbcTemplate.queryForObject(
            "SELECT detail FROM audit_log WHERE action='PARTNER_UPDATED' AND entity_id=?",
            String.class,
            id
        );
        assertThat(detail).contains("地址", "账号").doesNotContain("税号");

        // 合同（对客版本）带出客户开票信息
        String project = postJson("/api/projects", Map.of("name", "开票门店", "brand", "小象", "customerId", id))
            .path("id")
            .asText();
        String quote = postJson("/api/quotes", quoteInput(project, product(), "100")).path("id").asText();
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        var copy = getJson("/api/quotes/" + quote + "/customer-copy");
        assertThat(copy.path("customer_invoice_title").asText()).isEqualTo("上海开票客户有限公司");
        assertThat(copy.path("customer_tax_no").asText()).isEqualTo(CODE);
        assertThat(copy.path("customer_address").asText()).isEqualTo("上海市新地址 8 号");
        assertThat(copy.path("customer_bank_account").asText()).isEqualTo("6222000011113333");
    }
}
