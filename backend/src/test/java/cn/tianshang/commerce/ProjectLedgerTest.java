package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/** 项目台账：收货信息、预计发货、快递单号、权限与 Excel 导入导出。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class ProjectLedgerTest extends ApiTestSupport {

    private ResultActions as(String user, String role, String method, String path, Object body) throws Exception {
        var req = method.equals("PUT") ? put(path) : method.equals("GET") ? get(path) : post(path);
        if (body != null) req = req.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(req.with(csrf()).with(user(user).roles(role)));
    }

    private JsonNode project(String id) throws Exception {
        for (var p : getJson("/api/projects")) if (p.path("id").asText().equals(id)) return p;
        return null;
    }

    @Test
    void deliveryInfoTrackingAndPermissions() throws Exception {
        String customer = customer();
        var body = new HashMap<String, Object>();
        body.put("name", "物流门店" + UUID.randomUUID());
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put(
            "delivery",
            Map.of(
                "consignee",
                "张三",
                "consigneePhone",
                "138 0000 0000",
                "shipAddress",
                "北京市大兴区示例路 8 号",
                "expectedShipOn",
                LocalDate.now().plusDays(5).toString()
            )
        );
        String id = read(as("sales1", "SALES", "POST", "/api/projects", body).andExpect(status().isOk()).andReturn())
            .path("id")
            .asText();
        var p = project(id);
        assertThat(p.path("consignee").asText()).isEqualTo("张三");
        assertThat(p.path("ship_address").asText()).contains("大兴区");
        assertThat(p.path("expected_ship_on").asText()).startsWith(LocalDate.now().plusDays(5).toString());
        assertThat(p.path("shipping_status").isNull()).isTrue();
        // 填运单号即视为已发货，记录发货日期；全程留审计
        var d = new HashMap<String, Object>();
        d.put("courier", "顺丰速运");
        d.put("trackingNo", "SF1234567890");
        d.put("consignee", "张三");
        d.put("consigneePhone", "138 0000 0000");
        d.put("shipAddress", "北京市大兴区示例路 8 号");
        as("sales1", "SALES", "PUT", "/api/projects/" + id + "/delivery", d).andExpect(status().isOk());
        p = project(id);
        assertThat(p.path("shipping_status").asText()).isEqualTo("已发货");
        assertThat(p.path("shipped_on").asText()).startsWith(LocalDate.now().toString());
        assertThat(p.path("tracking_no").asText()).isEqualTo("SF1234567890");
        assertThat(
            jdbcTemplate
                .queryForList(
                    "SELECT detail FROM audit_log WHERE entity_id=? AND action='PROJECT_DELIVERY'",
                    String.class,
                    id
                )
                .toString()
        ).contains("运单号", "SF1234567890");
        // 运单号格式、他人项目、只读账户
        d.put("trackingNo", "顺丰单号");
        as("sales1", "SALES", "PUT", "/api/projects/" + id + "/delivery", d).andExpect(status().isBadRequest());
        d.put("trackingNo", "SF1234567890");
        as("sales2", "SALES", "PUT", "/api/projects/" + id + "/delivery", d).andExpect(status().isForbidden());
        as("reader", "VIEWER", "PUT", "/api/projects/" + id + "/delivery", d).andExpect(status().isForbidden());
        // 常用快递列表
        assertThat(getJson("/api/projects/couriers").toString()).contains("顺丰速运", "京东物流");
    }

    @Test
    void adminImportsAndExportsProjectLedger() throws Exception {
        String customerName = "导入客户" + UUID.randomUUID().toString().substring(0, 6);
        String customer = postJson("/api/partners", Map.of("name", customerName, "kind", "CUSTOMER"))
            .path("id")
            .asText();
        String existing = postJson("/api/projects", Map.of("name", "已有门店", "brand", "小象", "customerId", customer))
            .path("id")
            .asText();
        byte[] file = ExcelImportExportTest.xlsx(
            List.of(
                "门店名称",
                "客户",
                "业务线",
                "城市",
                "预计发货",
                "快递公司",
                "运单号",
                "收货人",
                "收货电话",
                "收货地址"
            ),
            List.of(
                List.of(
                    "已有门店",
                    customerName,
                    "小象",
                    "",
                    "2030/1/5",
                    "京东物流",
                    "JD0099887766",
                    "李四",
                    "13900000000",
                    "上海市浦东新区 1 号"
                ),
                List.of(
                    "新开门店",
                    customerName,
                    "快乐猴",
                    "通州",
                    "",
                    "",
                    "",
                    "王五",
                    "13700000000",
                    "北京市通州区 2 号"
                )
            )
        );
        var preview = upload("preview", file, "admin", "ADMIN");
        assertThat(preview.path("updated").asInt()).isEqualTo(1);
        assertThat(preview.path("created").asInt()).isEqualTo(1);
        upload("import", file, "admin", "ADMIN");
        var p = project(existing);
        assertThat(p.path("tracking_no").asText()).isEqualTo("JD0099887766");
        assertThat(p.path("shipping_status").asText()).isEqualTo("已发货");
        assertThat(p.path("expected_ship_on").asText()).startsWith("2030-01-05");
        JsonNode created = null;
        for (var x : getJson("/api/projects"))
            if (x.path("name").asText().equals("新开门店") && x.path("customer_id").asText().equals(customer)) created =
                x;
        assertThat(created).isNotNull();
        assertThat(created.path("city").asText()).isEqualTo("北京");
        assertThat(created.path("brand").asText()).isEqualTo("快乐猴超市");
        // 再导入同一文件：无变化
        assertThat(upload("preview", file, "admin", "ADMIN").path("unchanged").asInt()).isEqualTo(2);
        // 错误：城市不在字典
        byte[] bad = ExcelImportExportTest.xlsx(
            List.of("门店名称", "客户", "业务线", "城市"),
            List.of(List.of("错误门店", customerName, "小象", "火星"))
        );
        assertThat(upload("preview", bad, "admin", "ADMIN").path("errors").asInt()).isEqualTo(1);
        // 仅管理员可导入；导出含收货信息
        mvc
            .perform(
                multipart("/api/excel/projects/preview")
                    .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", file))
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
            )
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/excel/projects")).andExpect(status().isOk());
    }

    private JsonNode upload(String action, byte[] body, String user, String role) throws Exception {
        return read(
            mvc
                .perform(
                    multipart("/api/excel/projects/" + action)
                        .file(new MockMultipartFile("file", "data.xlsx", "application/octet-stream", body))
                        .with(csrf())
                        .with(user(user).roles(role))
                )
                .andExpect(status().isOk())
                .andReturn()
        );
    }
}
