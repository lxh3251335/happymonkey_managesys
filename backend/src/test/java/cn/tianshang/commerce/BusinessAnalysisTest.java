package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.contract.Contracts;
import cn.tianshang.commerce.importing.SourceImporter;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class BusinessAnalysisTest extends ApiTestSupport {

    @Autowired
    SourceImporter importer;

    @TempDir
    Path directory;

    @Test
    void productLinesSplitCabinetAndIot() {
        assertThat(Contracts.productLine("物联网电控箱")).isEqualTo("物联网电箱");
        assertThat(Contracts.productLine("物联网电控箱（备货）")).isEqualTo("物联网电箱");
        assertThat(Contracts.productLine("水位物联网配电箱")).isEqualTo("物联网电箱");
        assertThat(Contracts.productLine("冷冻一控一配电箱")).isEqualTo("电控柜");
        assertThat(Contracts.productLine("总配电箱")).isEqualTo("电控柜");
        assertThat(Contracts.productLine("冷链系统总配电柜")).isEqualTo("电控柜");
        assertThat(Contracts.productLine("鲜肉冷藏库电箱1")).isEqualTo("电控柜");
        assertThat(Contracts.productLine("平台及触摸屏软件合计")).isEqualTo("软件及平台");
        assertThat(Contracts.productLine("温控器")).isEqualTo("其他");
    }

    private static JsonNode line(JsonNode lines, String name) {
        for (var l : lines) if (l.path("name").asText().equals(name)) return l;
        throw new AssertionError("line not found: " + name);
    }

    @Test
    void dashboardFiltersByBrandAndSplitsLineMargins() throws Exception {
        Path seed = directory.resolve("source.json");
        Files.writeString(
            seed,
            """
            {"batch":"business-analysis","summary":{"records":4},"records":[
              {"customer":"业务分析客户","project":"小象A店","brand":"小象","contract":"BA-1","shipping":"已发货",
               "orderDate":"2026-04-03","name":"物联网电控箱","specification":"s","model":"m","quantity":"1",
               "amount":"7000","cost":"2500","source":"f!2","rawSource":"r!2"},
              {"customer":"业务分析客户","project":"小象A店","brand":"小象","contract":"BA-1","shipping":"已发货",
               "orderDate":"2026-04-03","name":"冷冻一控一配电箱","specification":"s","model":"m","quantity":"1",
               "amount":"1000","cost":"900","source":"f!3","rawSource":"r!3"},
              {"customer":"业务分析客户","project":"快乐猴B店","brand":"快乐猴","contract":"BA-2","shipping":"已发货",
               "orderDate":"2026-04-05","name":"总配电箱","specification":"s","model":"m","quantity":"1",
               "amount":"2000","cost":"1800","source":"f!4","rawSource":"r!4"},
              {"customer":"业务分析客户","project":"快乐猴B店","brand":"快乐猴","contract":"BA-2","shipping":"已发货",
               "orderDate":"2026-04-05","name":"水位物联网配电箱","specification":"s","model":"m","quantity":"1",
               "amount":"500","cost":"200","source":"f!5","rawSource":"r!5"}
            ]}
            """
        );
        importer.importSeed(seed);

        var all = getJson("/api/dashboard");
        var lines = all.path("line_summary");
        assertThat(lines.get(0).path("name").asText()).isEqualTo("电控柜");
        assertThat(lines.get(1).path("name").asText()).isEqualTo("物联网电箱");
        assertThat(line(lines, "电控柜").path("contract_amount").decimalValue()).isEqualByComparingTo("3000");
        assertThat(line(lines, "电控柜").path("gross_margin").decimalValue()).isEqualByComparingTo("0.1");
        assertThat(line(lines, "物联网电箱").path("contract_amount").decimalValue()).isEqualByComparingTo("7500");
        assertThat(line(lines, "物联网电箱").path("gross_margin").decimalValue()).isEqualByComparingTo("0.64");

        var brandLines = all.path("brand_lines");
        assertThat(brandLines.get(0).path("brand").asText()).isEqualTo("小象前置仓");
        assertThat(brandLines.get(1).path("brand").asText()).isEqualTo("快乐猴超市");
        var monkeyIot = line(brandLines.get(1).path("lines"), "物联网电箱");
        assertThat(monkeyIot.path("contract_amount").decimalValue()).isEqualByComparingTo("500");
        assertThat(monkeyIot.path("gross_margin").decimalValue()).isEqualByComparingTo("0.6");

        for (var m : all.path("monthly"))
            if (m.path("name").asText().equals("2026-04")) assertThat(
                m.path("lines").path("电控柜").path("contract_amount").decimalValue()
            ).isEqualByComparingTo("3000");

        var xiaoxiang = getJson("/api/dashboard?brand=小象");
        assertThat(xiaoxiang.path("brand").asText()).isEqualTo("小象前置仓");
        assertThat(xiaoxiang.path("totals").path("contract_amount").decimalValue()).isEqualByComparingTo("8000");
        assertThat(xiaoxiang.path("by_customer").get(0).path("contract_amount").decimalValue()).isEqualByComparingTo(
            "8000"
        );
        var xl = xiaoxiang.path("line_summary");
        assertThat(line(xl, "电控柜").path("contract_amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(line(xl, "物联网电箱").path("profit").decimalValue()).isEqualByComparingTo("4500");
        // 品牌 × 产品线对比不随筛选变化
        assertThat(xiaoxiang.path("brand_lines")).hasSize(2);
    }
}
