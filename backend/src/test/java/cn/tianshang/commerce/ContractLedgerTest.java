package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/** 合同台账：数据字典、销售数据隔离、生成合同、变更审批、开票、成本与 Excel。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class ContractLedgerTest extends ApiTestSupport {

    private ResultActions as(String user, String role, String method, String path, Object body) throws Exception {
        var req = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path);
            default -> post(path);
        };
        if (body != null) req = req.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(req.with(csrf()).with(user(user).roles(role)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return read(r.andExpect(status().isOk()).andReturn());
    }

    private String userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, username);
    }

    /** sales1 登记门店并出具一份 1000 元（含税）报价；返回 [customerId, projectId, quoteId]。 */
    private String[] salesQuote(String user) throws Exception {
        String customer = customer(),
            product = product();
        String project = ok(
            as(
                user,
                "SALES",
                "POST",
                "/api/projects",
                Map.of("name", "合同门店" + UUID.randomUUID(), "brand", "小象", "customerId", customer, "city", "大兴")
            )
        )
            .path("id")
            .asText();
        var line = new HashMap<String, Object>();
        line.put("productId", product);
        line.put("quantity", 2);
        line.put("unitPrice", "500");
        line.put("unitCost", "300");
        var q = new HashMap<String, Object>(quoteInput(project, product, "300"));
        q.put("items", List.of(line));
        String quote = ok(as(user, "SALES", "POST", "/api/quotes", q)).path("id").asText();
        ok(as(user, "SALES", "POST", "/api/quotes/" + quote + "/issue", Map.of()));
        return new String[] { customer, project, quote };
    }

    private Map<String, Object> info(JsonNode c) {
        var m = new HashMap<String, Object>();
        m.put("status", c.path("status").asText());
        m.put("companyId", c.path("company_id").isNull() ? null : c.path("company_id").asText());
        m.put("title", c.path("title").asText(null));
        m.put("content", c.path("content").asText(null));
        m.put("signedOn", c.path("signed_on").isNull() ? null : c.path("signed_on").asText().substring(0, 10));
        m.put("ownerId", c.path("owner_id").isNull() ? null : c.path("owner_id").asText());
        m.put("archived", c.path("archived").asBoolean());
        return m;
    }

    private JsonNode contract(String user, String role, String customer, String key) throws Exception {
        return ok(
            mvc.perform(
                get("/api/contracts/detail")
                    .param("customerId", customer)
                    .param("contractKey", key)
                    .with(user(user).roles(role))
            )
        );
    }

    @Test
    void dictionariesResolveCityAliasesAndCascadeRenames() throws Exception {
        // 城市别名：大兴 → 北京；字典外的城市被拒绝
        String p = postJson(
            "/api/projects",
            Map.of("name", "城市门店", "brand", "小象", "customerId", customer(), "city", "大兴")
        )
            .path("id")
            .asText();
        assertThat(jdbcTemplate.queryForObject("SELECT city FROM project WHERE id=?", String.class, p)).isEqualTo(
            "北京"
        );
        as(
            "admin",
            "ADMIN",
            "POST",
            "/api/projects",
            Map.of("name", "x", "brand", "小象", "customerId", customer(), "city", "火星")
        ).andExpect(status().isBadRequest());
        // 应用场景改名同步到产品；字典外场景不能建产品
        String id = postJson("/api/dictionaries/scenarios", Map.of("name", "冷库场景", "sort", 5, "active", true))
            .path("id")
            .asText();
        String product = postJson(
            "/api/products",
            Map.of("scenario", "冷库场景", "name", "冷库电箱" + UUID.randomUUID(), "unit", "台")
        )
            .path("id")
            .asText();
        mvc
            .perform(
                put("/api/dictionaries/scenarios/" + id)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("name", "冷库", "sort", 5, "active", true)))
            )
            .andExpect(status().isOk());
        assertThat(
            jdbcTemplate.queryForObject("SELECT scenario FROM product WHERE id=?", String.class, product)
        ).isEqualTo("冷库");
        as(
            "admin",
            "ADMIN",
            "POST",
            "/api/products",
            Map.of("scenario", "未登记场景", "name", "y", "unit", "台")
        ).andExpect(status().isBadRequest());
        // 只有管理员能维护字典
        as(
            "sales1",
            "SALES",
            "POST",
            "/api/dictionaries/cities",
            Map.of("name", "新城", "sort", 1, "active", true)
        ).andExpect(status().isForbidden());
        // 签约主体：新增并设为默认后，原默认取消
        String co = postJson(
            "/api/dictionaries/companies",
            Map.of("name", "天商第二公司", "shortName", "天商二", "isDefault", true, "active", true, "sort", 2)
        )
            .path("id")
            .asText();
        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company WHERE is_default=TRUE", Integer.class)
        ).isEqualTo(1);
        assertThat(
            jdbcTemplate.queryForObject("SELECT is_default FROM company WHERE id=?", Boolean.class, co)
        ).isTrue();
    }

    @Test
    void salesSeeOnlyTheirOwnProjectsQuotesAndContracts() throws Exception {
        var s = salesQuote("sales1");
        assertThat(
            jdbcTemplate.queryForObject("SELECT owner_id FROM project WHERE id=?", String.class, s[1])
        ).isEqualTo(userId("sales1"));
        assertThat(ok(as("sales1", "SALES", "GET", "/api/projects", null)).toString()).contains(s[1]);
        assertThat(ok(as("sales2", "SALES", "GET", "/api/projects", null)).toString()).doesNotContain(s[1]);
        assertThat(ok(as("sales2", "SALES", "GET", "/api/quotes", null)).toString()).doesNotContain(s[2]);
        as("sales2", "SALES", "GET", "/api/quotes/" + s[2], null).andExpect(status().isForbidden());
        as("sales2", "SALES", "POST", "/api/quotes/" + s[2] + "/issue", Map.of()).andExpect(status().isForbidden());
        // 管理员、财务、只读看全部
        assertThat(ok(as("finance", "FINANCE", "GET", "/api/quotes", null)).toString()).contains(s[2]);
        assertThat(ok(as("reader", "VIEWER", "GET", "/api/projects", null)).toString()).contains(s[1]);
        // 生成合同后：合同只对负责人可见
        var c = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + s[2],
                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "signedOn", LocalDate.now().toString())
            )
        );
        String key = c.path("contractKey").asText();
        assertThat(ok(as("sales1", "SALES", "GET", "/api/contracts", null)).toString()).contains(key);
        assertThat(ok(as("sales2", "SALES", "GET", "/api/contracts", null)).toString()).doesNotContain(key);
        mvc
            .perform(
                get("/api/contracts/detail")
                    .param("customerId", s[0])
                    .param("contractKey", key)
                    .with(user("sales2").roles("SALES"))
            )
            .andExpect(status().isForbidden());
        // 销售业绩：经营总览按负责人汇总
        var dash = ok(as("admin", "ADMIN", "GET", "/api/dashboard", null));
        assertThat(dash.path("by_owner").toString()).contains("sales1");
        var mine = ok(as("sales2", "SALES", "GET", "/api/dashboard", null));
        assertThat(mine.path("by_owner").toString()).doesNotContain("sales1");
        // 管理员批量分配负责人
        ok(
            as(
                "admin",
                "ADMIN",
                "POST",
                "/api/projects/owner",
                Map.of("projectIds", List.of(s[1]), "ownerId", userId("sales2"), "includeContracts", true)
            )
        );
        assertThat(ok(as("sales2", "SALES", "GET", "/api/contracts", null)).toString()).contains(key);
    }

    @Test
    void signedContractChangesNeedAdminApprovalWithFullTrail() throws Exception {
        var s = salesQuote("sales1");
        // 未填签订日期：拟定中，负责人可直接修改
        String key = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + s[2],
                Map.of(
                    "dueDate",
                    LocalDate.now().plusDays(30).toString(),
                    "contractNo",
                    "HT-T-" + UUID.randomUUID().toString().substring(0, 6)
                )
            )
        )
            .path("contractKey")
            .asText();
        var c = contract("sales1", "SALES", s[0], key);
        assertThat(c.path("status").asText()).isEqualTo("DRAFT");
        assertThat(c.path("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(c.path("content").asText()).contains("×2");
        String infoPath = "/api/contracts/info?customerId=" + s[0] + "&contractKey=" + key;
        var draft = info(c);
        draft.put("title", "草稿改名");
        assertThat(
            ok(as("sales1", "SALES", "PUT", infoPath, Map.of("info", draft))).path("applied").asBoolean()
        ).isTrue();
        // 确认签订须填签订日期
        draft.put("status", "SIGNED");
        as("sales1", "SALES", "PUT", infoPath, Map.of("info", draft)).andExpect(status().isBadRequest());
        draft.put("signedOn", LocalDate.now().toString());
        ok(as("sales1", "SALES", "PUT", infoPath, Map.of("info", draft)));
        // 已签订：销售修改 → 变更申请，合同内容不变
        var signed = info(contract("sales1", "SALES", s[0], key));
        signed.put("title", "签订后改名");
        signed.put("signedAmount", "980");
        as("sales1", "SALES", "PUT", infoPath, Map.of("info", signed)).andExpect(status().isBadRequest()); // 缺原因
        var r = ok(as("sales1", "SALES", "PUT", infoPath, Map.of("info", signed, "reason", "客户议价")));
        assertThat(r.path("pending").asBoolean()).isTrue();
        assertThat(contract("sales1", "SALES", s[0], key).path("title").asText()).isEqualTo("草稿改名");
        // 同一合同不能重复提交；销售不能自行调整负责人
        as("sales1", "SALES", "PUT", infoPath, Map.of("info", signed, "reason", "再次")).andExpect(
            status().isBadRequest()
        );
        String changeId = r.path("changeId").asText();
        as("sales1", "SALES", "POST", "/api/contracts/changes/" + changeId + "/approve", Map.of()).andExpect(
            status().isForbidden()
        );
        ok(as("admin", "ADMIN", "POST", "/api/contracts/changes/" + changeId + "/approve", Map.of("note", "同意")));
        var after = contract("sales1", "SALES", s[0], key);
        assertThat(after.path("title").asText()).isEqualTo("签订后改名");
        assertThat(after.path("amount").decimalValue()).isEqualByComparingTo("980");
        assertThat(after.path("amount_gap").decimalValue()).isEqualByComparingTo("-20");
        // 全程追溯：草稿修改、签订、变更申请与批准，每条带原值和新值
        var changes = after.path("changes");
        assertThat(changes.size()).isGreaterThanOrEqualTo(4);
        JsonNode approved = null;
        for (var x : changes) if (x.path("status").asText().equals("APPROVED")) approved = x;
        assertThat(approved).isNotNull();
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("reviewed_by").asText()).isEqualTo("admin");
        assertThat(approved.path("changes").toString()).contains("草稿改名", "签订后改名", "签约金额");
        assertThat(after.path("events").toString()).contains("CONTRACT_CHANGE_APPROVED");
        // 驳回需要原因；管理员直接修改需要原因
        var again = info(after);
        again.put("title", "第三次");
        String second = ok(as("sales1", "SALES", "PUT", infoPath, Map.of("info", again, "reason", "改名")))
            .path("changeId")
            .asText();
        as("admin", "ADMIN", "POST", "/api/contracts/changes/" + second + "/reject", Map.of()).andExpect(
            status().isBadRequest()
        );
        ok(as("admin", "ADMIN", "POST", "/api/contracts/changes/" + second + "/reject", Map.of("note", "名称不变")));
        as("admin", "ADMIN", "PUT", infoPath, Map.of("info", again)).andExpect(status().isBadRequest());
        assertThat(
            ok(as("admin", "ADMIN", "PUT", infoPath, Map.of("info", again, "reason", "按客户来函")))
                .path("applied")
                .asBoolean()
        ).isTrue();
        // 已签订合同不能由销售再加门店
        as(
            "sales1",
            "SALES",
            "POST",
            "/api/projects",
            Map.of("name", "加店", "brand", "小象", "customerId", s[0], "contractNo", key)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void invoicesStayWithinContractAndCostsDriveMargin() throws Exception {
        var s = salesQuote("sales1");
        String key = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + s[2],
                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "signedOn", LocalDate.now().toString())
            )
        )
            .path("contractKey")
            .asText();
        String no = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var inv = new HashMap<String, Object>(
            Map.of(
                "customerId",
                s[0],
                "contractKey",
                key,
                "kind",
                "INVOICE",
                "invoiceNo",
                no,
                "issuedOn",
                LocalDate.now().toString(),
                "amount",
                "1200",
                "taxRate",
                "0.13"
            )
        );
        // 只有财务、管理员可开票；不能超过合同金额
        as("sales1", "SALES", "POST", "/api/contracts/invoices", inv).andExpect(status().isForbidden());
        as("finance", "FINANCE", "POST", "/api/contracts/invoices", inv).andExpect(status().isBadRequest());
        inv.put("amount", "600");
        String blue = ok(as("finance", "FINANCE", "POST", "/api/contracts/invoices", inv)).path("id").asText();
        as("finance", "FINANCE", "POST", "/api/contracts/invoices", inv).andExpect(status().isBadRequest()); // 号码重复
        var red = new HashMap<String, Object>(inv);
        red.put("kind", "RED");
        red.put("invoiceNo", no + "R");
        red.put("amount", "700");
        red.put("reversesId", blue);
        as("finance", "FINANCE", "POST", "/api/contracts/invoices", red).andExpect(status().isBadRequest());
        red.put("amount", "100");
        ok(as("finance", "FINANCE", "POST", "/api/contracts/invoices", red));
        var c = contract("finance", "FINANCE", s[0], key);
        assertThat(c.path("invoiced").decimalValue()).isEqualByComparingTo("500");
        assertThat(c.path("uninvoiced").decimalValue()).isEqualByComparingTo("500");
        assertThat(c.path("invoice_status").asText()).isEqualTo("PARTIAL");
        assertThat(c.path("invoiced_unreceived").decimalValue()).isEqualByComparingTo("500");
        // 有红冲的蓝字发票不能删除
        as(
            "finance",
            "FINANCE",
            "POST",
            "/api/contracts/invoices/" + blue + "/delete",
            Map.of("reason", "录错")
        ).andExpect(status().isBadRequest());
        // 预估成本来自报价（600），登记实际成本后按实际成本核算毛利
        assertThat(c.path("cost_source").asText()).isEqualTo("ESTIMATED");
        assertThat(c.path("profit").decimalValue()).isEqualByComparingTo("400");
        var cost = Map.of(
            "customerId",
            s[0],
            "contractKey",
            key,
            "category",
            "MATERIAL",
            "amount",
            "700",
            "incurredOn",
            LocalDate.now().toString()
        );
        as("sales2", "SALES", "POST", "/api/contracts/costs", cost).andExpect(status().isForbidden());
        ok(as("sales1", "SALES", "POST", "/api/contracts/costs", cost));
        c = contract("sales1", "SALES", s[0], key);
        assertThat(c.path("cost_source").asText()).isEqualTo("ACTUAL");
        assertThat(c.path("profit").decimalValue()).isEqualByComparingTo("300");
        assertThat(c.path("gross_margin").decimalValue()).isEqualByComparingTo(new BigDecimal("0.3"));
        assertThat(c.path("events").toString()).contains("CONTRACT_INVOICE", "CONTRACT_COST");
    }

    @Test
    void contractLedgerAndInvoicesRoundTripThroughExcel() throws Exception {
        var s = salesQuote("sales1");
        String key = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + s[2],
                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "signedOn", LocalDate.now().toString())
            )
        )
            .path("contractKey")
            .asText();
        String customerName = jdbcTemplate.queryForObject("SELECT name FROM partner WHERE id=?", String.class, s[0]);
        // 导出包含合同与统计列
        var exported = mvc.perform(get("/api/excel/contracts")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(exported.getContentAsByteArray().length).isGreaterThan(1000);
        // 导入：更新合同名称、签约金额；新增一份框架合同
        String frame = "KJ-" + UUID.randomUUID().toString().substring(0, 6);
        byte[] file = ExcelImportExportTest.xlsx(
            List.of("客户", "合同号", "合同名称", "签约金额", "签订日期", "合同状态", "门店数"),
            List.of(
                List.of(
                    customerName,
                    key,
                    "导入改名",
                    "1000",
                    LocalDate.now().toString().replace("-", "/"),
                    "已签订",
                    "99"
                ),
                List.of(customerName, frame, "年度框架合同", "50000", "", "拟定中", "")
            )
        );
        var preview = upload("contracts", "preview", file, "admin", "ADMIN");
        assertThat(preview.path("updated").asInt()).isEqualTo(1);
        assertThat(preview.path("created").asInt()).isEqualTo(1);
        upload("contracts", "import", file, "admin", "ADMIN");
        var c = contract("admin", "ADMIN", s[0], key);
        assertThat(c.path("title").asText()).isEqualTo("导入改名");
        assertThat(c.path("changes").toString()).contains("Excel 批量导入");
        var f = contract("admin", "ADMIN", s[0], frame);
        assertThat(f.path("amount").decimalValue()).isEqualByComparingTo("50000");
        assertThat(f.path("projects").asInt()).isZero();
        // 合同导入仅限管理员
        mvc
            .perform(
                multipart("/api/excel/contracts/preview")
                    .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", file))
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
            )
            .andExpect(status().isForbidden());
        // 开票记录导入：同批蓝字 + 红字；超额报错
        String no = "INV" + UUID.randomUUID().toString().substring(0, 8);
        byte[] inv = ExcelImportExportTest.xlsx(
            List.of("客户", "合同号", "发票号码", "类型", "原发票号码", "开票日期", "价税合计", "税率"),
            List.of(
                List.of(customerName, key, no, "蓝字", "", LocalDate.now().toString(), "800", "13%"),
                List.of(customerName, key, no + "R", "红字", no, LocalDate.now().toString(), "300", "13%")
            )
        );
        assertThat(upload("contract-invoices", "preview", inv, "finance", "FINANCE").path("created").asInt()).isEqualTo(
            2
        );
        upload("contract-invoices", "import", inv, "finance", "FINANCE");
        assertThat(contract("admin", "ADMIN", s[0], key).path("invoiced").decimalValue()).isEqualByComparingTo("500");
        byte[] over = ExcelImportExportTest.xlsx(
            List.of("客户", "合同号", "发票号码", "开票日期", "价税合计"),
            List.of(List.of(customerName, key, no + "X", LocalDate.now().toString(), "600"))
        );
        assertThat(upload("contract-invoices", "preview", over, "finance", "FINANCE").path("errors").asInt()).isEqualTo(
            1
        );
        // 再次导入同一文件：已登记的发票为无变化
        assertThat(
            upload("contract-invoices", "preview", inv, "finance", "FINANCE").path("unchanged").asInt()
        ).isEqualTo(2);
    }

    private JsonNode upload(String dataset, String action, byte[] body, String user, String role) throws Exception {
        return ok(
            mvc.perform(
                multipart("/api/excel/" + dataset + "/" + action)
                    .file(new MockMultipartFile("file", "data.xlsx", "application/octet-stream", body))
                    .with(csrf())
                    .with(user(user).roles(role))
            )
        );
    }
}
