package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.finance.ReceiptAllocator;
import cn.tianshang.commerce.finance.ReceiptAllocator.Invoice;
import cn.tianshang.commerce.finance.ReceiptAllocator.Open;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultMatcher;

/** 回款单：一笔到账分到多份合同，余额待分配；以及分配建议。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class ReceiptBatchTest extends ApiTestSupport {

    private final String today = LocalDate.now().toString();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Open open(String key, String outstanding, String due) {
        return new Open(key, d(outstanding), due == null ? null : LocalDate.parse(due), LocalDate.parse("2026-01-01"));
    }

    @Test
    void suggestionPrefersInvoiceThenExactCombinationThenOldest() {
        var contracts = List.of(
            open("A", "500.00", "2026-03-01"),
            open("B", "300.00", "2026-02-01"),
            open("C", "200.00", null),
            open("D", "1000.00", "2026-01-15")
        );
        // 金额正好等于一张发票：记到那张发票的合同，即使别的组合也凑得出
        var byInvoice = ReceiptAllocator.suggest(
            d("300.00"),
            contracts,
            List.of(new Invoice("A", d("300.00"), LocalDate.parse("2026-02-10")))
        );
        assertThat(byInvoice.rule()).isEqualTo("INVOICE");
        assertThat(byInvoice.shares()).containsExactly(new ReceiptAllocator.Share("A", d("300.00")));

        // 正好等于一份合同的未收
        var one = ReceiptAllocator.suggest(d("500.00"), contracts, List.of());
        assertThat(one.rule()).isEqualTo("EXACT");
        assertThat(one.shares()).containsExactly(new ReceiptAllocator.Share("A", d("500.00")));

        // 正好等于两份合同之和（B 300 + A 500）
        var two = ReceiptAllocator.suggest(d("800.00"), contracts, List.of());
        assertThat(two.rule()).isEqualTo("EXACT");
        assertThat(two.shares()).extracting(ReceiptAllocator.Share::contractKey).containsExactlyInAnyOrder("A", "B");
        assertThat(two.unallocated()).isEqualByComparingTo("0");

        // 凑不出：从最早到期的开始冲抵（D → B），没有截止日的排最后
        var oldest = ReceiptAllocator.suggest(d("1150.00"), contracts, List.of());
        assertThat(oldest.rule()).isEqualTo("OLDEST");
        assertThat(oldest.shares()).containsExactly(
            new ReceiptAllocator.Share("D", d("1000.00")),
            new ReceiptAllocator.Share("B", d("150.00"))
        );

        // 超过全部未收：多出来的留作待分配
        var over = ReceiptAllocator.suggest(d("2100.00"), contracts, List.of());
        assertThat(over.shares()).hasSize(4);
        assertThat(over.unallocated()).isEqualByComparingTo("100.00");
    }

    private String store(String customer, String contractNo) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", "门店" + UUID.randomUUID());
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        return postJson("/api/projects", body).path("id").asText();
    }

    /** 只有合同号的门店即视为已签订，报价出具后直接有应收。 */
    private void signedContract(String customer, String contractNo, String product, String price) throws Exception {
        var line = new HashMap<String, Object>();
        line.put("productId", product);
        line.put("quantity", 1);
        line.put("unitPrice", price);
        line.put("unitCost", "10");
        String id = postJson(
            "/api/quotes",
            Map.of(
                "projectId",
                store(customer, contractNo),
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
    }

    private BigDecimal outstanding(String customer, String contractNo) throws Exception {
        for (var c : getJson("/api/contracts"))
            if (
                c.path("customer_id").asText().equals(customer) && c.path("contract_key").asText().equals(contractNo)
            ) return c.path("outstanding").decimalValue();
        throw new AssertionError("合同不在台账中");
    }

    private JsonNode batch(String id) throws Exception {
        for (var b : getJson("/api/receipt-batches")) if (b.path("id").asText().equals(id)) return b;
        throw new AssertionError("回款单不在列表中");
    }

    private Map<String, Object> input(
        String customer,
        String amount,
        String reference,
        List<Map<String, Object>> allocations
    ) {
        var m = new HashMap<String, Object>();
        m.put("customerId", customer);
        m.put("amount", amount);
        m.put("paidOn", today);
        m.put("reference", reference);
        m.put("payer", "测试付款单位");
        m.put("sourceText", "您账户收到人民币 " + amount + " 元");
        m.put("allocations", allocations);
        return m;
    }

    private void expect(String path, Object body, ResultMatcher expected) throws Exception {
        mvc
            .perform(post(path).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(expected);
    }

    @Test
    void onePaymentCoversSeveralContractsAndRemainderWaits() throws Exception {
        String product = product(),
            customer = customer();
        signedContract(customer, "RB-A", product, "500");
        signedContract(customer, "RB-B", product, "300");
        signedContract(customer, "RB-C", product, "200");

        // 建议：800 正好是 A + B
        var s = getJson("/api/receipt-batches/suggest?customerId=" + customer + "&amount=800");
        assertThat(s.path("rule").asText()).isEqualTo("EXACT");
        assertThat(s.path("allocations")).hasSize(2);

        // 一笔 800 分到 A、B 两份合同
        var created = postJson(
            "/api/receipt-batches",
            input(
                customer,
                "800",
                "BANK-800",
                List.of(Map.of("contractKey", "RB-A", "amount", "500"), Map.of("contractKey", "RB-B", "amount", "300"))
            )
        );
        assertThat(created.path("unallocated").decimalValue()).isEqualByComparingTo("0");
        assertThat(outstanding(customer, "RB-A")).isEqualByComparingTo("0");
        assertThat(outstanding(customer, "RB-B")).isEqualByComparingTo("0");
        assertThat(outstanding(customer, "RB-C")).isEqualByComparingTo("200");
        var b = batch(created.path("id").asText());
        assertThat(b.path("items")).hasSize(2);
        assertThat(b.path("allocated").decimalValue()).isEqualByComparingTo("800");
        assertThat(b.path("payer").asText()).isEqualTo("测试付款单位");
        // 各合同的回款出现在统一流水里，凭证号带序号
        var refs = new ArrayList<String>();
        for (var r : getJson("/api/cash/ledger")) refs.add(r.path("reference").asText());
        assertThat(refs).contains("BANK-800-1", "BANK-800-2");

        // 不填凭证号自动编号；只分一部分，余额待分配，不冲减任何合同
        var partial = postJson(
            "/api/receipt-batches",
            input(customer, "150", "", List.of(Map.of("contractKey", "RB-C", "amount", "50")))
        );
        assertThat(partial.path("reference").asText()).startsWith("HK" + today.replace("-", "") + "-");
        assertThat(partial.path("unallocated").decimalValue()).isEqualByComparingTo("100");
        assertThat(outstanding(customer, "RB-C")).isEqualByComparingTo("150");

        // 以后继续分：超过余额不行，分完为止
        String id = partial.path("id").asText();
        expect(
            "/api/receipt-batches/" + id + "/allocate",
            Map.of("allocations", List.of(Map.of("contractKey", "RB-C", "amount", "120"))),
            status().isBadRequest()
        );
        postJson(
            "/api/receipt-batches/" + id + "/allocate",
            Map.of("allocations", List.of(Map.of("contractKey", "RB-C", "amount", "100")))
        );
        assertThat(batch(id).path("unallocated").decimalValue()).isEqualByComparingTo("0");
        assertThat(batch(id).path("items")).hasSize(2);
        assertThat(outstanding(customer, "RB-C")).isEqualByComparingTo("50");
    }

    @Test
    void invalidBatchesAreRejectedAsAWhole() throws Exception {
        String product = product(),
            customer = customer();
        signedContract(customer, "RV-A", product, "500");
        signedContract(customer, "RV-B", product, "300");

        // 分配合计超过到账金额
        expect(
            "/api/receipt-batches",
            input(customer, "100", "RV-1", List.of(Map.of("contractKey", "RV-A", "amount", "200"))),
            status().isBadRequest()
        );
        // 其中一份超过该合同未收：整笔不入账，另一份合同也不变
        expect(
            "/api/receipt-batches",
            input(
                customer,
                "900",
                "RV-2",
                List.of(Map.of("contractKey", "RV-A", "amount", "500"), Map.of("contractKey", "RV-B", "amount", "400"))
            ),
            status().isBadRequest()
        );
        assertThat(outstanding(customer, "RV-A")).isEqualByComparingTo("500");
        assertThat(getJson("/api/receipt-batches").toString()).doesNotContain("RV-2");
        // 凭证号不能重复
        postJson("/api/receipt-batches", input(customer, "100", "RV-3", List.of()));
        expect("/api/receipt-batches", input(customer, "100", "RV-3", List.of()), status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "sales1", roles = "SALES")
    void salesCannotRegisterReceipts() throws Exception {
        expect(
            "/api/receipt-batches",
            Map.of("customerId", "x", "amount", "1", "paidOn", today, "allocations", List.of()),
            status().isForbidden()
        );
    }
}
