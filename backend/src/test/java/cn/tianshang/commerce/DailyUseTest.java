package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.bot.BotMessage;
import cn.tianshang.commerce.bot.BotService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

/** 今日待办、全局搜索、在企业微信里审批、每天早上推送。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class DailyUseTest extends ApiTestSupport {

    @Autowired
    BotService bot;

    @Autowired
    cn.tianshang.commerce.wecom.WecomMorningPush morningPush;

    private ResultActions as(String user, String role, String method, String path, Object body) throws Exception {
        var req = method.equals("PUT") ? put(path) : method.equals("GET") ? get(path) : post(path);
        if (body != null) req = req.contentType("application/json").content(mapper.writeValueAsString(body));
        return mvc.perform(req.with(csrf()).with(user(user).roles(role)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return read(r.andExpect(status().isOk()).andReturn());
    }

    private String namedCustomer(String name) throws Exception {
        return postJson("/api/partners", Map.of("name", name, "kind", "CUSTOMER")).path("id").asText();
    }

    /** 有合同号、报价已出具的门店（即已签订、有应收）。返回门店 ID。 */
    private String signedStore(String customer, String name, String contractNo, String price) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        String project = postJson("/api/projects", body).path("id").asText();
        var line = new HashMap<String, Object>();
        line.put("productId", product());
        line.put("quantity", 1);
        line.put("unitPrice", price);
        line.put("unitCost", "10");
        String quote = postJson(
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
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        return project;
    }

    private Map<String, JsonNode> todos(String user, String role) throws Exception {
        var out = new LinkedHashMap<String, JsonNode>();
        for (var i : ok(as(user, role, "GET", "/api/todos", null))) out.put(i.path("key").asText(), i);
        return out;
    }

    private void bind(String who, String username, String code) {
        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES(?,(SELECT id FROM app_user WHERE username=?),?)",
            code,
            username,
            LocalDateTime.now().plusMinutes(5)
        );
        assertThat(say(who, "绑定 " + code)).contains("绑定成功");
    }

    private String say(String who, String text) {
        return bot.handle(BotMessage.text(BotBindingService.WECOM, who, text));
    }

    @Test
    void todayListsWhatNeedsAttentionInRoleOrder() throws Exception {
        String customer = namedCustomer("待办客户");
        String late = signedStore(customer, "LK-1 待办超期站", "TODO-1", "500.00");
        signedStore(customer, "LK-2 待办准时站", "TODO-2", "300.00");
        jdbcTemplate.update("UPDATE project SET expected_ship_on=? WHERE id=?", LocalDate.now().minusDays(2), late);
        // TODO-1 已逾期
        jdbcTemplate.update("UPDATE quotation SET due_date=? WHERE project_id=?", LocalDate.now().minusDays(5), late);
        // 一份报价已过有效期、还在等客户确认
        String open = postJson(
            "/api/projects",
            Map.of("name", "LK-3 待办报价站", "brand", "小象", "customerId", customer)
        )
            .path("id")
            .asText();
        String quote = postJson("/api/quotes", quoteInput(open, product(), "10")).path("id").asText();
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        jdbcTemplate.update("UPDATE quotation SET valid_until=? WHERE id=?", LocalDate.now().minusDays(1), quote);

        var admin = todos("admin", "ADMIN");
        assertThat(admin.get("LATE_SHIP").path("count").asInt()).isEqualTo(1);
        assertThat(admin.get("LATE_SHIP").path("link").asText()).isEqualTo("/projects?quick=LATE");
        assertThat(admin.get("OVERDUE").path("count").asInt()).isEqualTo(1);
        assertThat(admin.get("OVERDUE").path("amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(admin.get("QUOTE_EXPIRED").path("count").asInt()).isEqualTo(1);
        // 数量为 0 的不列出
        assertThat(admin).doesNotContainKey("CHANGES");

        // 财务只看和钱有关的；业务员只数得到自己负责的
        assertThat(todos("finance1", "FINANCE").keySet()).containsExactly("OVERDUE", "UNDATED");
        assertThat(todos("sales1", "SALES")).isEmpty();

        // 已终止的合同不算
        jdbcTemplate.update(
            "INSERT INTO contract(id,customer_id,contract_no,status) VALUES(?,?,?,'TERMINATED')",
            UUID.randomUUID().toString(),
            customer,
            "TODO-1"
        );
        var after = todos("admin", "ADMIN");
        assertThat(after).doesNotContainKey("OVERDUE");
        assertThat(after).doesNotContainKey("LATE_SHIP");
    }

    @Test
    void searchFindsStoresContractsQuotesAndCustomersWithinScope() throws Exception {
        String customer = namedCustomer("搜索专用客户有限公司");
        String store = signedStore(customer, "LK-9 搜索盛泰站", "SEARCH-77", "500.00");
        jdbcTemplate.update("UPDATE project SET tracking_no='SF5550001112223' WHERE id=?", store);
        String code = jdbcTemplate.queryForObject("SELECT code FROM quotation WHERE project_id=?", String.class, store);

        var byTracking = ok(as("admin", "ADMIN", "GET", "/api/search?q=sf555", null));
        assertThat(byTracking.get(0).path("group").asText()).isEqualTo("门店");
        assertThat(byTracking.get(0).path("title").asText()).isEqualTo("LK-9 搜索盛泰站");
        assertThat(byTracking.get(0).path("link").asText()).startsWith("/projects?q=");

        var byContract = ok(as("admin", "ADMIN", "GET", "/api/search?q=search-77", null)).toString();
        assertThat(byContract).contains("\"group\":\"合同\"").contains("contract=SEARCH-77");
        assertThat(ok(as("admin", "ADMIN", "GET", "/api/search?q=" + code, null)).toString()).contains(
            "\"group\":\"报价\""
        );
        assertThat(ok(as("admin", "ADMIN", "GET", "/api/search?q=搜索专用", null)).toString()).contains(
            "\"group\":\"客户\""
        );
        // 业务员搜不到别人负责的门店和合同
        var sales = ok(as("sales1", "SALES", "GET", "/api/search?q=search-77", null)).toString();
        assertThat(sales).doesNotContain("门店").doesNotContain("合同");
        assertThat(ok(as("admin", "ADMIN", "GET", "/api/search?q=", null)).size()).isZero();
    }

    /** 业务员对已签订合同提交一条改名申请，返回申请 ID。 */
    private String changeRequest() throws Exception {
        String customer = namedCustomer("审批客户" + UUID.randomUUID());
        String project = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/projects",
                Map.of("name", "审批门店", "brand", "小象", "customerId", customer)
            )
        )
            .path("id")
            .asText();
        String quote = ok(as("sales1", "SALES", "POST", "/api/quotes", quoteInput(project, product(), "50")))
            .path("id")
            .asText();
        ok(as("sales1", "SALES", "POST", "/api/quotes/" + quote + "/issue", Map.of()));
        String key = ok(
            as(
                "sales1",
                "SALES",
                "POST",
                "/api/contracts/from-quote/" + quote,
                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "signedOn", LocalDate.now().toString())
            )
        )
            .path("contractKey")
            .asText();
        var info = new HashMap<String, Object>();
        info.put("status", "SIGNED");
        info.put("signedOn", LocalDate.now().toString());
        info.put("title", "机器人审批改名");
        info.put(
            "ownerId",
            jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username='sales1'", String.class)
        );
        return ok(
            as(
                "sales1",
                "SALES",
                "PUT",
                "/api/contracts/info?customerId=" + customer + "&contractKey=" + key,
                Map.of("info", info, "reason", "客户要求改名")
            )
        )
            .path("changeId")
            .asText();
    }

    @Test
    void managersApproveFromWeComByNumber() throws Exception {
        String change = changeRequest();
        bind("wx-manager", "manager1", "61000001");
        bind("wx-sales", "sales1", "61000002");

        assertThat(say("wx-manager", "今日")).contains("待审批变更 1 条").contains("发“待审批”查看");
        assertThat(say("wx-manager", "批准 1")).contains("请先发“待审批”");
        // 普通的话里带这些词不当指令
        assertThat(say("wx-manager", "今天天气怎么样")).contains("没看懂");
        assertThat(say("wx-manager", "批准了吗")).contains("没看懂");
        String list = say("wx-manager", "待审批");
        assertThat(list)
            .contains("待审批变更 1 条")
            .contains("1. ")
            .contains("客户要求改名")
            .contains("机器人审批改名");
        assertThat(say("wx-manager", "批准 2")).contains("没有第 2 条");
        assertThat(say("wx-manager", "驳回 1")).contains("驳回要写原因");
        assertThat(say("wx-manager", "批准 1")).contains("已批准").contains("待审批的都处理完了");
        assertThat(jdbcTemplate.queryForMap("SELECT status,reviewed_by FROM contract_change WHERE id=?", change))
            .containsEntry("status", "APPROVED")
            .containsEntry("reviewed_by", "manager1");
        // 业务员不能审批；群里不谈审批
        assertThat(say("wx-sales", "待审批")).contains("只有管理层和管理员");
        assertThat(
            bot.handle(new BotMessage(BotBindingService.WECOM, "wx-manager", "group-1", true, "待审批", null, null))
        ).contains("请和我单聊");

        String second = changeRequest();
        say("wx-manager", "待审批");
        assertThat(say("wx-manager", "驳回 1 名称和合同原件不一致")).contains("已驳回");
        assertThat(
            jdbcTemplate.queryForObject("SELECT review_note FROM contract_change WHERE id=?", String.class, second)
        ).isEqualTo("名称和合同原件不一致");
    }

    @Test
    void morningPushRunsOncePerDayAtTheConfiguredTime() throws Exception {
        changeRequest();
        bind("wx-manager", "manager1", "62000001");
        bind("wx-viewer", "viewer1", "62000002");
        // 推送内容按各人账号计算：管理层有待审批；只读账号今天没有待办，不推
        assertThat(bot.morningDigest(BotBindingService.WECOM, "wx-manager"))
            .contains("今日待办")
            .contains("待审批变更");
        assertThat(bot.morningDigest(BotBindingService.WECOM, "wx-viewer")).isNull();
        assertThat(bot.morningDigest(BotBindingService.WECOM, "nobody")).isNull();

        var day = LocalDate.of(2026, 11, 23);
        // 没启用：不推
        assertThat(morningPush.run(day, "09:00")).isEqualTo(-1);
        jdbcTemplate.update(
            "INSERT INTO bot_config(channel,bot_id,secret,enabled,push_enabled,push_time) VALUES('WECOM','b','s',TRUE,TRUE,'08:30')"
        );
        assertThat(morningPush.run(day, "09:00")).isEqualTo(-1);
        // 到点推一次（测试里机器人没连上企业微信，所以发出 0 条）；同一天不再推
        assertThat(morningPush.run(day, "08:30")).isEqualTo(0);
        assertThat(morningPush.run(day, "08:30")).isEqualTo(-1);
        assertThat(morningPush.run(day.plusDays(1), "08:30")).isEqualTo(0);
    }
}
