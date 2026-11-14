package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.contract.LegacyContractBackfill;
import cn.tianshang.commerce.importing.SourceImporter;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class ContractDashboardTest extends ApiTestSupport {

    @Autowired
    SourceImporter importer;

    @Autowired
    LegacyContractBackfill backfill;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path directory;

    private String projectWith(String customer, String brand, String contractNo, String shipping, String orderDate)
        throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", "门店" + UUID.randomUUID());
        body.put("brand", brand);
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        body.put("shippingStatus", shipping);
        body.put("orderDate", orderDate);
        return postJson("/api/projects", body).path("id").asText();
    }

    private String quote(String project, String product, String unitPrice, String unitCost) throws Exception {
        var line = new HashMap<String, Object>();
        line.put("productId", product);
        line.put("quantity", 1);
        line.put("unitPrice", unitPrice);
        line.put("unitCost", unitCost);
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
                LocalDate.now().plusDays(30).toString(),
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
        throw new AssertionError("contract not found");
    }

    private void expectBadRequest(String path, Object body) throws Exception {
        mvc
            .perform(post(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void contractConfirmReceiptAllocationRefundAndDashboard() throws Exception {
        String customer = customer(),
            product = product();
        String month = LocalDate.now().withDayOfMonth(1).toString();
        String a = projectWith(customer, "小象", "HT-001", "已发货", month),
            b = projectWith(customer, "快乐猴", "HT-001", "制作中", month);
        quote(a, product, "100.00", "60.00");
        quote(b, product, "200.00", "150.00");
        String draftProject = projectWith(customer, "小象", null, null, month);
        postJson("/api/quotes", quoteInput(draftProject, product, "1")); // 草稿且无合同号：不计入合同额

        var c = contract(customer);
        assertThat(c.path("contract_no").asText()).isEqualTo("HT-001");
        assertThat(c.path("projects").asInt()).isEqualTo(2);
        assertThat(c.path("amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(c.path("confirmation").asText()).isEqualTo("PENDING");
        assertThat(c.path("shipped").asInt()).isEqualTo(1);

        var receipt = new HashMap<String, Object>(
            Map.of(
                "customerId",
                customer,
                "contractKey",
                "HT-001",
                "kind",
                "RECEIPT",
                "amount",
                "100.00",
                "paidOn",
                LocalDate.now().toString(),
                "reference",
                "HT-BANK-1"
            )
        );
        expectBadRequest("/api/contracts/receipts", receipt); // 未确认合同不能收款

        String due = LocalDate.now().minusDays(40).toString();
        assertThat(
            postJson("/api/contracts/confirm", Map.of("customerId", customer, "contractKey", "HT-001", "dueDate", due))
                .path("confirmed")
                .asInt()
        ).isEqualTo(2);
        var allocated = postJson("/api/contracts/receipts", receipt);
        assertThat(allocated.path("allocations").asInt()).isEqualTo(2);
        c = contract(customer);
        assertThat(c.path("confirmation").asText()).isEqualTo("CONFIRMED");
        assertThat(c.path("received").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(c.path("outstanding").decimalValue()).isEqualByComparingTo("200.00");
        // 按剩余应收比例 100:200 分摊
        var items = c.path("items");
        var received = new ArrayList<BigDecimal>();
        items.forEach(i -> received.add(i.path("received").decimalValue()));
        assertThat(received)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactlyInAnyOrder(new BigDecimal("33.33"), new BigDecimal("66.67"));

        receipt.put("reference", "HT-BANK-2");
        receipt.put("amount", "200.01");
        expectBadRequest("/api/contracts/receipts", receipt); // 超过剩余应收

        String original = allocated.path("id").asText();
        var refund = Map.of(
            "customerId",
            customer,
            "contractKey",
            "HT-001",
            "kind",
            "REFUND",
            "amount",
            "10.00",
            "paidOn",
            LocalDate.now().toString(),
            "reference",
            "HT-REFUND-1",
            "reversesId",
            original
        );
        postJson("/api/contracts/receipts", refund);
        assertThat(contract(customer).path("received").decimalValue()).isEqualByComparingTo("90.00");

        var d = getJson("/api/dashboard");
        var totals = d.path("totals");
        assertThat(totals.path("contract_amount").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(totals.path("received").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(totals.path("receivable").decimalValue()).isEqualByComparingTo("210.00");
        assertThat(totals.path("overdue_amount").decimalValue()).isEqualByComparingTo("210.00");
        assertThat(totals.path("backlog_amount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(totals.path("profit").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(totals.path("gross_margin").decimalValue()).isEqualByComparingTo("0.30");
        String thisMonth = month.substring(0, 7);
        var monthRow = d.path("monthly").findValuesAsText("name");
        assertThat(monthRow).contains(thisMonth);
        for (var m : d.path("monthly"))
            if (m.path("name").asText().equals(thisMonth)) {
                assertThat(m.path("contract_amount").decimalValue()).isEqualByComparingTo("300.00");
                assertThat(m.path("received").decimalValue()).isEqualByComparingTo("90.00");
            }
        var brands = new HashMap<String, BigDecimal>();
        d.path("by_brand").forEach(x -> brands.put(x.path("name").asText(), x.path("contract_amount").decimalValue()));
        assertThat(brands.get("小象前置仓")).isEqualByComparingTo("100.00");
        assertThat(brands.get("快乐猴超市")).isEqualByComparingTo("200.00");
        assertThat(d.path("aging").get(2).path("contract_amount").decimalValue()).isEqualByComparingTo("210.00");
        assertThat(d.path("by_line").get(0).path("name").asText()).isEqualTo("电控柜");
    }

    @Test
    void historicalContractsCountWithoutReceivablesAndLegacyNotesBackfill() throws Exception {
        Path seed = directory.resolve("source.json");
        Files.writeString(
            seed,
            """
            {"batch":"contract-import","summary":{"records":2},"records":[
              {"customer":"历史合同客户","project":"小象A店","brand":"小象","contract":"TJ-1","shipping":"在制作",
               "orderDate":"2026-04-03","name":"物联网电控箱","specification":"s","model":"m","quantity":"1",
               "amount":"7000","cost":"2500","source":"f!2","rawSource":"r!2"},
              {"customer":"历史合同客户","project":"小象B店","brand":"小象","contract":"","shipping":"已发货",
               "orderDate":"2026-05-03","name":"冷冻一控一配电箱","specification":"s","model":"m","quantity":"1",
               "amount":"1000","cost":null,"source":"f!3","rawSource":"r!3"}
            ]}
            """
        );
        importer.importSeed(seed);
        var d = getJson("/api/dashboard");
        assertThat(d.path("totals").path("contract_amount").decimalValue()).isEqualByComparingTo("7000");
        assertThat(d.path("totals").path("pending_amount").decimalValue()).isEqualByComparingTo("7000");
        assertThat(d.path("totals").path("receivable").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.path("totals").path("backlog_amount").decimalValue()).isEqualByComparingTo("7000");
        assertThat(d.path("by_line").get(0).path("name").asText()).isEqualTo("物联网电箱");

        // 早期导入只在备注中保存合同号：启动补写后计入合同额
        jdbc.update(
            "UPDATE project SET contract_no=NULL,shipping_status=NULL,note='历史发货状态：已发货；原合同号：TJ-2' WHERE name='小象B店'"
        );
        backfill.run(null);
        var row = jdbc.queryForMap("SELECT contract_no,shipping_status FROM project WHERE name='小象B店'");
        assertThat(row.get("contract_no")).isEqualTo("TJ-2");
        assertThat(row.get("shipping_status")).isEqualTo("已发货");
        assertThat(
            getJson("/api/dashboard").path("totals").path("contract_amount").decimalValue()
        ).isEqualByComparingTo("8000");
    }
}
