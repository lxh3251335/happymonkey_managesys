package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.finance.InvoiceParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultMatcher;

/** 发票识别，以及一张发票分到多份合同。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class InvoiceEntryTest extends ApiTestSupport {

    private final String today = LocalDate.now().toString();

    /** 数电票（全电发票）按位置读出的文字：购买方与销售方在同一行。 */
    private static final String DIGITAL = """
        电子发票（增值税专用发票）
        发票号码：25117000000123456789
        开票日期：2026年11月06日
        购买方信息 名称：北京欣阳通力科技有限公司 销售方信息 名称：天津天商制冷设备有限公司
        统一社会信用代码/纳税人识别号：91110108MA01ABCD2X 统一社会信用代码/纳税人识别号：91120116MA05WXYZ3K
        项目名称 规格型号 单位 数量 单价 金额 税率/征收率 税额
        *配电控制设备*电控柜 650*850*250 台 1 15000.00 15000.00 13% 1950.00
        *配电控制设备*物联网电控箱 250*300*160 台 2 1000.00 2000.00 13% 260.00
        合 计 ¥17000.00 ¥2210.00
        价税合计（大写） 壹万玖仟贰佰壹拾圆整 （小写）¥19210.00
        备 注 合同号：TJ2026-BJXYTL-19、TJ2026-BJXYTL-20
        开票人：张三
        """;

    /** 旧版式电子普通发票：标签里有空格，金额用全角“￥”，购买方在上、销售方在下。 */
    private static final String LEGACY = """
        北京增值税电子普通发票
        发票代码: 011002100311 发票号码: 12345678
        开票日期: 2026年10月08日
        名        称: 上海海利中野制冷设备有限公司
        纳税人识别号: 913101157890123456
        货物或应税劳务、服务名称 规格型号 单位 数量 单价 金额 税率 税额
        *制冷设备*电控柜 台 1 5309.73 5309.73 13% 690.27
        合    计 ￥5309.73 ￥690.27
        价税合计(大写) 陆仟圆整 (小写) ￥6,000.00
        名        称: 天津天商制冷设备有限公司
        纳税人识别号: 91120116MA05WXYZ3K
        备
        注
        收款人: 李四 复核: 王五 开票人: 赵六
        """;

    @Test
    void parserReadsBothInvoiceLayouts() {
        var d = InvoiceParser.parse(DIGITAL);
        assertThat(d.invoiceNo()).isEqualTo("25117000000123456789");
        assertThat(d.issuedOn()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(d.amount()).isEqualByComparingTo("19210.00");
        assertThat(d.taxRate()).isEqualByComparingTo("0.13");
        assertThat(d.invoiceType()).isEqualTo("SPECIAL");
        assertThat(d.buyerName()).isEqualTo("北京欣阳通力科技有限公司");
        assertThat(d.buyerTaxNo()).isEqualTo("91110108MA01ABCD2X");
        assertThat(d.sellerName()).isEqualTo("天津天商制冷设备有限公司");
        assertThat(d.remark()).contains("TJ2026-BJXYTL-19", "TJ2026-BJXYTL-20");

        var l = InvoiceParser.parse(LEGACY);
        assertThat(l.invoiceNo()).isEqualTo("12345678");
        assertThat(l.issuedOn()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(l.amount()).isEqualByComparingTo("6000.00");
        assertThat(l.invoiceType()).isEqualTo("NORMAL");
        assertThat(l.buyerName()).isEqualTo("上海海利中野制冷设备有限公司");
        assertThat(l.sellerName()).isEqualTo("天津天商制冷设备有限公司");
        assertThat(l.remark()).isNull();

        // 没有文字层的扫描件、无关文字：什么都不认，不猜
        assertThat(InvoiceParser.parse("").empty()).isTrue();
        assertThat(InvoiceParser.parse("hello world 123").empty()).isTrue();
    }

    private String store(String customer, String contractNo) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", "门店" + UUID.randomUUID());
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        return postJson("/api/projects", body).path("id").asText();
    }

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

    private JsonNode contract(String customer, String contractNo) throws Exception {
        for (var c : getJson("/api/contracts"))
            if (
                c.path("customer_id").asText().equals(customer) && c.path("contract_key").asText().equals(contractNo)
            ) return c;
        throw new AssertionError("合同不在台账中");
    }

    private Map<String, Object> entry(
        String customer,
        String no,
        String amount,
        List<Map<String, Object>> allocations
    ) {
        var m = new HashMap<String, Object>();
        m.put("customerId", customer);
        m.put("invoiceNo", no);
        m.put("invoiceType", "SPECIAL");
        m.put("issuedOn", today);
        m.put("amount", amount);
        m.put("taxRate", "0.13");
        m.put("allocations", allocations);
        return m;
    }

    private static byte[] pdf(String line) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private org.springframework.test.web.servlet.ResultActions send(Object data, MockMultipartFile file)
        throws Exception {
        var req = multipart("/api/invoice-entries").file(
            new MockMultipartFile("data", "", "application/json", mapper.writeValueAsBytes(data))
        );
        if (file != null) req.file(file);
        return mvc.perform(req.with(csrf()));
    }

    private void rejected(Object data, ResultMatcher expected) throws Exception {
        send(data, null).andExpect(expected);
    }

    @Test
    void oneInvoiceCoversSeveralContractsAndKeepsThePdf() throws Exception {
        String product = product(),
            customer = customer();
        signedContract(customer, "IV-A", product, "500");
        signedContract(customer, "IV-B", product, "300");
        signedContract(customer, "IV-C", product, "200");

        // 建议：备注里点名的合同优先；没有点名时找正好凑齐的
        var named = getJson("/api/invoice-entries/suggest?customerId=" + customer + "&amount=300&hint=合同号 iv-b");
        assertThat(named.path("rule").asText()).isEqualTo("REMARK");
        assertThat(named.path("allocations").get(0).path("contractKey").asText()).isEqualTo("IV-B");
        var exact = getJson("/api/invoice-entries/suggest?customerId=" + customer + "&amount=800");
        assertThat(exact.path("rule").asText()).isEqualTo("EXACT");
        assertThat(exact.path("allocations")).hasSize(2);

        // 一张 800 的发票分到 A、B 两份合同，原件存进两份合同的附件
        var file = new MockMultipartFile("file", "发票-INV800.pdf", "application/pdf", pdf("Invoice INV800"));
        var created = read(
            send(
                entry(
                    customer,
                    "INV800",
                    "800",
                    List.of(
                        Map.of("contractKey", "IV-A", "amount", "500"),
                        Map.of("contractKey", "IV-B", "amount", "300")
                    )
                ),
                file
            )
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(created.path("contracts").asInt()).isEqualTo(2);
        assertThat(created.path("attached").asInt()).isEqualTo(2);
        assertThat(contract(customer, "IV-A").path("invoiced").decimalValue()).isEqualByComparingTo("500");
        assertThat(contract(customer, "IV-B").path("uninvoiced").decimalValue()).isEqualByComparingTo("0");
        assertThat(contract(customer, "IV-A").path("attachments").asInt()).isEqualTo(1);
        // 两份合同下各有一行，同一个发票号码
        int rows = 0;
        for (var i : getJson("/api/contracts/invoices")) if (i.path("invoice_no").asText().equals("INV800")) rows++;
        assertThat(rows).isEqualTo(2);

        // 这张发票已登记：再登记同一个号码被拒绝
        rejected(
            entry(customer, "INV800", "200", List.of(Map.of("contractKey", "IV-C", "amount", "200"))),
            status().isBadRequest()
        );
        // 各合同金额之和必须等于价税合计
        rejected(
            entry(customer, "INV900", "300", List.of(Map.of("contractKey", "IV-C", "amount", "200"))),
            status().isBadRequest()
        );
        // 超过合同还没开票的金额：整张不登记
        rejected(
            entry(customer, "INV901", "250", List.of(Map.of("contractKey", "IV-C", "amount", "250"))),
            status().isBadRequest()
        );
        assertThat(contract(customer, "IV-C").path("invoiced").decimalValue()).isEqualByComparingTo("0");

        // 只对一份合同的发票和以前一样登记
        read(
            send(entry(customer, "INV200", "200", List.of(Map.of("contractKey", "IV-C", "amount", "200"))), null)
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(contract(customer, "IV-C").path("uninvoiced").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void pdfWithoutInvoiceTextIsReadButNothingIsGuessed() throws Exception {
        var res = read(
            mvc
                .perform(
                    multipart("/api/invoice-entries/parse")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf("no invoice here")))
                        .with(csrf())
                )
                .andExpect(status().isOk())
                .andReturn()
        );
        assertThat(res.path("recognized").asBoolean()).isFalse();
        assertThat(res.path("invoiceNo").isNull()).isTrue();
        // 不是 PDF：直接说明
        mvc
            .perform(
                multipart("/api/invoice-entries/parse")
                    .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] { 1, 2, 3 }))
                    .with(csrf())
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "sales1", roles = "SALES")
    void salesCannotRegisterInvoices() throws Exception {
        rejected(entry("x", "INV1", "1", List.of(Map.of("contractKey", "k", "amount", "1"))), status().isForbidden());
    }
}
