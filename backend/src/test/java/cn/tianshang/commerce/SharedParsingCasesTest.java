package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.finance.ReceiptParser;
import cn.tianshang.commerce.project.DeliveryParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 粘贴识别的服务端实现必须和浏览器里的实现得到同样的结果：两边都跑 shared/parsing 下的同一份用例
 * （浏览器那边见 frontend/tests）。
 */
class SharedParsingCasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode cases(String name) throws Exception {
        var file = Path.of("..", "shared", "parsing", name);
        // 只带 backend 目录构建时（如 Docker 镜像）没有这份用例，跳过；完整检出的仓库和 CI 都会跑到
        Assumptions.assumeTrue(Files.exists(file), "没有 shared/parsing 用例，跳过");
        return MAPPER.readTree(file.toFile());
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static List<Map<String, Object>> rows(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    @Test
    void deliveryParserAgreesWithSharedCases() throws Exception {
        var cases = cases("delivery.json");
        var couriers = MAPPER.convertValue(cases.path("couriers"), new TypeReference<List<String>>() {});
        for (var c : cases.path("parse")) {
            var p = DeliveryParser.parse(c.path("input").asText(), couriers);
            var e = c.path("expect");
            String name = c.path("name").asText();
            assertThat(p.consignee()).as(name + " 收货人").isEqualTo(text(e.get("consignee")));
            assertThat(p.phone()).as(name + " 电话").isEqualTo(text(e.get("phone")));
            assertThat(p.address()).as(name + " 地址").isEqualTo(text(e.get("address")));
            assertThat(p.courier()).as(name + " 快递公司").isEqualTo(text(e.get("courier")));
            assertThat(p.trackingNo()).as(name + " 运单号").isEqualTo(text(e.get("trackingNo")));
            assertThat(p.courierGuessed()).as(name + " 推断").isEqualTo(e.path("courierGuessed").asBoolean(false));
        }
        for (var c : cases.path("courierOfTracking"))
            assertThat(DeliveryParser.courierOfTracking(c.path("input").asText()))
                .as(c.path("input").asText())
                .isEqualTo(text(c.get("expect")));
        for (var c : cases.path("lines")) {
            var lines = DeliveryParser.trackingLines(c.path("input").asText(), couriers);
            assertThat(lines).as(c.path("name").asText()).hasSize(c.path("expect").size());
            for (int i = 0; i < lines.size(); i++) {
                var e = c.path("expect").get(i);
                assertThat(lines.get(i).hint()).isEqualTo(e.path("hint").asText());
                assertThat(lines.get(i).courier()).isEqualTo(text(e.get("courier")));
                assertThat(lines.get(i).trackingNo()).isEqualTo(text(e.get("trackingNo")));
                assertThat(lines.get(i).courierGuessed()).isEqualTo(e.path("courierGuessed").asBoolean());
            }
        }
        var stores = rows(cases.path("stores"));
        for (var c : cases.path("match")) {
            var expected = MAPPER.convertValue(c.path("expect"), new TypeReference<List<String>>() {});
            assertThat(
                DeliveryParser.matchStores(c.path("hint").asText(), stores)
                    .stream()
                    .map(s -> s.get("code"))
                    .toList()
            )
                .as("对门店：" + c.path("hint").asText())
                .isEqualTo(expected);
        }
    }

    @Test
    void receiptParserAgreesWithSharedCases() throws Exception {
        var cases = cases("receipt.json");
        var today = LocalDate.parse(cases.path("today").asText());
        for (var c : cases.path("parse")) {
            var p = ReceiptParser.parse(c.path("input").asText(), today);
            var e = c.path("expect");
            String name = c.path("name").asText();
            if (e.has("amount")) assertThat(p.amount())
                .as(name + " 金额")
                .isEqualByComparingTo(e.get("amount").decimalValue());
            else assertThat(p.amount()).as(name + " 金额").isNull();
            assertThat(p.paidOn() == null ? null : p.paidOn().toString())
                .as(name + " 日期")
                .isEqualTo(text(e.get("paidOn")));
            assertThat(p.payer()).as(name + " 付款方").isEqualTo(text(e.get("payer")));
            assertThat(p.reference()).as(name + " 流水号").isEqualTo(text(e.get("reference")));
        }
        var customers = rows(cases.path("customers"));
        for (var c : cases.path("match")) {
            var m = ReceiptParser.matchCustomer(c.path("text").asText(), customers);
            assertThat(m == null ? null : m.get("id"))
                .as("对客户：" + c.path("text").asText())
                .isEqualTo(text(c.get("expect")));
        }
    }
}
