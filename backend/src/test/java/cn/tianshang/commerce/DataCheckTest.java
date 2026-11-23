package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.check.DataCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

/** 数据检查：缺失、对不上、疑似重复三类都能发现问题，带可打开的链接；能导出；业务员不能用。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class DataCheckTest extends ApiTestSupport {

    private JsonNode check(JsonNode report, String key) {
        for (var c : report.path("checks")) if (key.equals(c.path("key").asText())) return c;
        throw new AssertionError("no check " + key);
    }

    private static List<String> labels(JsonNode check) {
        var out = new ArrayList<String>();
        for (var f : check.path("findings")) out.add(f.path("label").asText());
        return out;
    }

    @Test
    void findsMissingMismatchedAndSuspectData() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // 两个只差空格与全角的客户名；电话格式不对；缺开票信息
        String customer = postJson(
            "/api/partners",
            Map.of(
                "name",
                "检查客户" + tag,
                "kind",
                "CUSTOMER",
                "phone",
                "王经理 138",
                "taxNo",
                ExcelImportExportTest.TAX
            )
        )
            .path("id")
            .asText();
        postJson("/api/partners", Map.of("name", "检查 客户" + tag, "kind", "CUSTOMER"));
        // 已成交门店：有合同号、没有报价明细、缺城市/项目类型/收货信息，下单日期在将来
        var project = new HashMap<String, Object>();
        project.put("name", "检查门店" + tag);
        project.put("brand", "小象");
        project.put("customerId", customer);
        project.put("contractNo", "HT-CHK-" + tag);
        project.put("orderDate", LocalDate.now().plusDays(3).toString());
        postJson("/api/projects", project);
        // 产品没有采购成本、没有图纸和电气件
        String product = product();

        var report = getJson("/api/data-check");
        assertThat(report.path("errors").asInt()).isGreaterThan(0);
        assertThat(report.path("total").asInt()).isEqualTo(
            report.path("errors").asInt() + report.path("warnings").asInt() + report.path("infos").asInt()
        );

        var noQuote = check(report, "dealt-no-quote");
        assertThat(noQuote.path("level").asText()).isEqualTo("ERROR");
        assertThat(noQuote.path("group").asText()).isEqualTo(DataCheckService.MISSING);
        assertThat(labels(noQuote)).contains("检查客户" + tag + " · 检查门店" + tag);
        var link = noQuote
            .path("findings")
            .get(labels(noQuote).indexOf("检查客户" + tag + " · 检查门店" + tag))
            .path("link");
        assertThat(link.asText()).startsWith("/projects?q=");

        var fields = check(report, "project-fields");
        var f = fields.path("findings");
        JsonNode mine = null;
        for (var x : f) if (x.path("label").asText().endsWith("检查门店" + tag)) mine = x;
        assertThat(mine).isNotNull();
        // 创建人自动成为负责人，所以这里不缺负责人
        assertThat(mine.path("detail").asText()).contains("城市").contains("项目类型").contains("收货人");

        assertThat(labels(check(report, "customer-invoice"))).contains("检查客户" + tag);
        assertThat(check(report, "project-contract-missing").path("group").asText()).isEqualTo(
            DataCheckService.MISMATCH
        );
        assertThat(labels(check(report, "project-contract-missing"))).contains("检查客户" + tag + " · HT-CHK-" + tag);

        var similar = check(report, "partner-similar");
        assertThat(similar.path("group").asText()).isEqualTo(DataCheckService.SUSPECT);
        assertThat(labels(similar)).anyMatch(l -> l.contains("检查客户" + tag) && l.contains("检查 客户" + tag));

        var future = check(report, "future-dates");
        assertThat(future.path("findings").toString()).contains("下单日期").contains("检查门店" + tag);

        assertThat(labels(check(report, "phones"))).contains("检查客户" + tag);
        assertThat(check(report, "product-cost").path("count").asInt()).isGreaterThan(0);
        assertThat(check(report, "product-drawing").path("count").asInt()).isGreaterThan(0);
        assertThat(product).isNotBlank();
    }

    @Test
    void exportsFindingsAsExcelAndRejectsSales() throws Exception {
        postJson(
            "/api/projects",
            Map.of("name", "导出检查门店", "brand", "小象", "customerId", customer(), "contractNo", "HT-EXP-1")
        );
        byte[] bytes = mvc
            .perform(get("/api/data-check/export"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("UTF-8''")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = wb.getSheet("数据检查");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("类别");
            boolean found = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                if (
                    row != null &&
                    row.getCell(3) != null &&
                    row.getCell(3).getStringCellValue().contains("导出检查门店")
                ) found = true;
            }
            assertThat(found).isTrue();
        }
        mvc.perform(get("/api/data-check").with(user("sales1").roles("SALES"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/data-check").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/data-check").with(user("finance1").roles("FINANCE"))).andExpect(status().isOk());
    }

    @Test
    void normalizesNamesAndPhones() {
        assertThat(DataCheckService.normalize(" 上海 中野 ")).isEqualTo(DataCheckService.normalize("上海中野"));
        assertThat(DataCheckService.normalize("ＡＢＣ公司")).isEqualTo(DataCheckService.normalize("abc公司"));
        assertThat(DataCheckService.badPhone("138 0000 0000")).isFalse();
        assertThat(DataCheckService.badPhone("021-12345678 转 123")).isFalse();
        assertThat(DataCheckService.badPhone("王经理 138")).isTrue();
        assertThat(DataCheckService.badPhone(null)).isFalse();
    }
}
