package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultMatcher;

/** 合同签订即有应收；已签合同的门店通过“确认并取代原报价”改价或增补。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class ContractChangeTest extends ApiTestSupport {

    private final String today = LocalDate.now().toString(),
        due = LocalDate.now().plusDays(30).toString();

    private String store(String customer, String contractNo) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", "门店" + UUID.randomUUID());
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        return postJson("/api/projects", body).path("id").asText();
    }

    /** 新建一版报价并出具；单价即报价金额（数量 1）。 */
    private String issued(String project, String product, String price) throws Exception {
        var line = new HashMap<String, Object>();
        line.put("productId", product);
        line.put("quantity", 1);
        line.put("unitPrice", price);
        line.put("unitCost", "10");
        String id = postJson(
            "/api/quotes",
            Map.of(
                "projectId",
                project,
                "taxBasis",
                "INCLUDED",
                "taxRate",
                "0.13",
                "validUntil",
                due,
                "items",
                List.of(line)
            )
        )
            .path("id")
            .asText();
        postJson("/api/quotes/" + id + "/issue", Map.of());
        return id;
    }

    private JsonNode contract(String customer) throws Exception {
        for (var c : getJson("/api/contracts")) if (c.path("customer_id").asText().equals(customer)) return c;
        throw new AssertionError("合同不在台账中");
    }

    private JsonNode quote(String id) throws Exception {
        for (var q : getJson("/api/quotes")) if (q.path("id").asText().equals(id)) return q;
        throw new AssertionError("报价不在列表中");
    }

    private void expect(String path, Object body, ResultMatcher expected) throws Exception {
        mvc
            .perform(post(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(expected);
    }

    private Map<String, Object> receipt(String customer, String key, String amount, String reference) {
        return Map.of(
            "customerId",
            customer,
            "contractKey",
            key,
            "kind",
            "RECEIPT",
            "amount",
            amount,
            "paidOn",
            today,
            "reference",
            reference
        );
    }

    @Test
    void signedContractHasReceivableButDraftDoesNot() throws Exception {
        String product = product();
        // 只有合同号、没有确认动作的门店（历史数据的常见形态）：视为已签订，直接有应收，可以登记回款
        String signedCustomer = customer();
        issued(store(signedCustomer, "SIGN-1"), product, "500");
        var signed = contract(signedCustomer);
        assertThat(signed.path("status").asText()).isEqualTo("SIGNED");
        assertThat(signed.path("outstanding").decimalValue()).isEqualByComparingTo("500");
        assertThat(signed.path("undated").asInt()).isEqualTo(1);
        postJson("/api/contracts/receipts", receipt(signedCustomer, "SIGN-1", "200", "CC-BANK-1"));
        assertThat(contract(signedCustomer).path("outstanding").decimalValue()).isEqualByComparingTo("300");

        // 合同还在拟定、客户尚未确认：没有应收，不能登记回款；办理确认并设截止日期后才有
        String draftCustomer = customer();
        var info = new HashMap<String, Object>();
        info.put("status", "DRAFT");
        info.put("archived", false);
        postJson("/api/contracts", Map.of("customerId", draftCustomer, "contractNo", "DRAFT-1", "info", info));
        issued(store(draftCustomer, "DRAFT-1"), product, "400");
        var draft = contract(draftCustomer);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(draft.path("confirmation").asText()).isEqualTo("PENDING");
        expect(
            "/api/contracts/receipts",
            receipt(draftCustomer, "DRAFT-1", "100", "CC-BANK-2"),
            status().isBadRequest()
        );
        postJson(
            "/api/contracts/confirm",
            Map.of("customerId", draftCustomer, "contractKey", "DRAFT-1", "dueDate", due)
        );
        assertThat(contract(draftCustomer).path("outstanding").decimalValue()).isEqualByComparingTo("400");
    }

    @Test
    void newVersionReplacesConfirmedQuoteAsRecordedContractChange() throws Exception {
        String customer = customer(),
            product = product(),
            project = store(customer, null);
        String v1 = issued(project, product, "1000");
        String key = postJson(
            "/api/contracts/from-quote/" + v1,
            Map.of("dueDate", due, "contractNo", "CHG-1", "signedOn", today)
        )
            .path("contractKey")
            .asText();
        postJson("/api/contracts/receipts", receipt(customer, key, "300", "CHG-BANK-1"));

        // 客户增补：新版本 1500。普通确认被拦下并提示改走取代；已签合同必须填原因
        String v2 = issued(project, product, "1500");
        assertThat(contract(customer).path("amount").decimalValue()).isEqualByComparingTo("1000"); // 出新版本不影响合同
        expect("/api/quotes/" + v2 + "/accept", Map.of("dueDate", due), status().isBadRequest());
        expect("/api/contracts/replace-quote/" + v2, Map.of(), status().isBadRequest());
        var done = postJson("/api/contracts/replace-quote/" + v2, Map.of("reason", "客户增补一台"));
        assertThat(done.path("signedAmountKept").asBoolean()).isFalse();

        // 新版本生效：合同金额与应收更新，到款保留，截止日期沿用；原版本保留备查
        var c = contract(customer);
        assertThat(c.path("amount").decimalValue()).isEqualByComparingTo("1500");
        assertThat(c.path("received").decimalValue()).isEqualByComparingTo("300");
        assertThat(c.path("outstanding").decimalValue()).isEqualByComparingTo("1200");
        assertThat(c.path("due_date").asText()).startsWith(due);
        assertThat(quote(v1).path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(quote(v1).path("stage").asText()).isEqualTo("ARCHIVED");
        assertThat(quote(v2).path("stage").asText()).isEqualTo("CONTRACTED");
        // 记入合同变更记录，带原因
        var detail = read(
            mvc
                .perform(get("/api/contracts/detail").param("customerId", customer).param("contractKey", key))
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(detail.path("changes").toString()).contains("客户增补一台", "V1 ¥1000.00", "V2 ¥1500.00");
        // 被取代的版本不能再确认、不能标记未成交，也不能再被用来取代
        expect("/api/quotes/" + v1 + "/lose", Map.of("reason", "x"), status().isBadRequest());
        expect("/api/contracts/replace-quote/" + v1, Map.of("reason", "x"), status().isBadRequest());

        // 减价不能低于已到款；开票后不能低于已开票
        String tooLow = issued(project, product, "200");
        expect("/api/contracts/replace-quote/" + tooLow, Map.of("reason", "减配"), status().isBadRequest());
        var invoice = new HashMap<String, Object>();
        invoice.put("customerId", customer);
        invoice.put("contractKey", key);
        invoice.put("kind", "INVOICE");
        invoice.put("invoiceNo", "CHG-INV-" + UUID.randomUUID());
        invoice.put("issuedOn", today);
        invoice.put("amount", "1400");
        invoice.put("taxRate", "0.13");
        postJson("/api/contracts/invoices", invoice);
        String belowInvoiced = issued(project, product, "1200");
        expect("/api/contracts/replace-quote/" + belowInvoiced, Map.of("reason", "减配"), status().isBadRequest());
        assertThat(contract(customer).path("amount").decimalValue()).isEqualByComparingTo("1500");
    }

    @Test
    void salesCannotReplaceOnSignedContractAndBasisIsPinned() throws Exception {
        // 只有合同号的历史门店：出新版本前，现有报价被固定为合同依据，新版本不影响合同金额
        String customer = customer(),
            product = product(),
            project = store(customer, "PIN-1");
        String v1 = issued(project, product, "800");
        String v2 = issued(project, product, "950");
        assertThat(quote(v1).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(contract(customer).path("amount").decimalValue()).isEqualByComparingTo("800");
        assertThat(quote(v2).path("stage").asText()).isEqualTo("ISSUED");
        // 业务员（即使是负责人）不能在已签合同上取代报价；管理层可以
        String sales = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username='sales1'", String.class);
        jdbcTemplate.update("UPDATE project SET owner_id=? WHERE id=?", sales, project);
        var body = mapper.writeValueAsString(Map.of("reason", "调价"));
        mvc
            .perform(
                post("/api/contracts/replace-quote/" + v2)
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                post("/api/contracts/replace-quote/" + v2)
                    .with(csrf())
                    .with(user("manager1").roles("MANAGER"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().isOk());
        assertThat(contract(customer).path("amount").decimalValue()).isEqualByComparingTo("950");
        // 历史合同原本没有合同记录：变更时补建，并留下变更记录
        var detail = read(
            mvc
                .perform(get("/api/contracts/detail").param("customerId", customer).param("contractKey", "PIN-1"))
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(detail.path("changes").toString()).contains("调价", "V1 ¥800.00", "V2 ¥950.00");
    }

    @Test
    void batchDueDatesFillOnlyMissingOnes() throws Exception {
        String product = product(),
            customer = customer();
        // 三份只有合同号的历史合同：一份下单日期在 40 天前，一份没有下单日期且已有截止日期，一份属于别人
        var old = new HashMap<String, Object>();
        old.put("name", "门店" + UUID.randomUUID());
        old.put("brand", "小象");
        old.put("customerId", customer);
        old.put("contractNo", "DUE-1");
        old.put("orderDate", LocalDate.now().minusDays(40).toString());
        issued(postJson("/api/projects", old).path("id").asText(), product, "500");
        issued(store(customer, "DUE-2"), product, "300");
        postJson("/api/contracts/confirm", Map.of("customerId", customer, "contractKey", "DUE-2", "dueDate", due));
        var refs = List.of(
            Map.of("customerId", customer, "contractKey", "DUE-1"),
            Map.of("customerId", customer, "contractKey", "DUE-2")
        );
        // 参数不全被拒绝
        expect("/api/contracts/due-dates", Map.of("contracts", refs, "mode", "FIXED"), status().isBadRequest());
        expect("/api/contracts/due-dates", Map.of("contracts", refs, "mode", "OFFSET"), status().isBadRequest());
        // 按下单日期 + 30 天推算：只补 DUE-1，结果早于今天，立即计为逾期；DUE-2 已有日期不动
        var r = postJson(
            "/api/contracts/due-dates",
            Map.of("contracts", refs, "mode", "OFFSET", "base", "ORDER", "days", 30)
        );
        assertThat(r.path("updated").asInt()).isEqualTo(1);
        assertThat(r.path("contracts").asInt()).isEqualTo(1);
        JsonNode one = null,
            two = null;
        for (var c : getJson("/api/contracts")) {
            if (c.path("contract_key").asText().equals("DUE-1")) one = c;
            if (c.path("contract_key").asText().equals("DUE-2")) two = c;
        }
        assertThat(one.path("due_date").asText()).startsWith(LocalDate.now().minusDays(10).toString());
        assertThat(one.path("undated").asInt()).isZero();
        assertThat(one.path("overdue").decimalValue()).isEqualByComparingTo("500");
        assertThat(two.path("due_date").asText()).startsWith(due);
        // 再跑一次没有可补的
        assertThat(
            postJson("/api/contracts/due-dates", Map.of("contracts", refs, "mode", "FIXED", "dueDate", due))
                .path("updated")
                .asInt()
        ).isZero();

        // 只读角色不能办理；业务员只会处理自己负责的门店，别人的合同原样不动
        String other = customer();
        issued(store(other, "DUE-3"), product, "200");
        var body = mapper.writeValueAsString(
            Map.of(
                "contracts",
                List.of(Map.of("customerId", other, "contractKey", "DUE-3")),
                "mode",
                "FIXED",
                "dueDate",
                due
            )
        );
        mvc
            .perform(
                post("/api/contracts/due-dates")
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
                    .contentType("application/json")
                    .content(body)
            )
            .andExpect(status().isForbidden());
        var bySales = read(
            mvc
                .perform(
                    post("/api/contracts/due-dates")
                        .with(csrf())
                        .with(user("sales1").roles("SALES"))
                        .contentType("application/json")
                        .content(body)
                )
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(bySales.path("updated").asInt()).isZero();
        assertThat(contract(other).path("undated").asInt()).isEqualTo(1);
    }
}
