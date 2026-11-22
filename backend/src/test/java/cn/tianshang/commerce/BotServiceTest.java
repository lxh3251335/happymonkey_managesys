package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import cn.tianshang.commerce.bot.BotBindingService;
import cn.tianshang.commerce.bot.BotMessage;
import cn.tianshang.commerce.bot.BotService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** 机器人：绑定、按角色查询、录入先预览后确认。消息直接交给 BotService，不经过企业微信。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class BotServiceTest extends ApiTestSupport {

    private static final String WECOM = BotBindingService.WECOM;

    @Autowired
    BotService bot;

    private int codes = 10000000;

    /** 给系统账号发一个绑定码，并让企业微信里的 who 用它完成绑定。 */
    private void bind(String who, String username) {
        String code = String.valueOf(++codes);
        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES(?,(SELECT id FROM app_user WHERE username=?),?)",
            code,
            username,
            LocalDateTime.now().plusMinutes(5)
        );
        assertThat(say(who, "绑定 " + code)).contains("绑定成功");
    }

    private String say(String who, String text) {
        return bot.handle(BotMessage.text(WECOM, who, text));
    }

    private String sayInGroup(String who, String text) {
        return bot.handle(new BotMessage(WECOM, who, "group-1", true, text, null, null));
    }

    private String store(String customer, String name, String contractNo) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", contractNo);
        return postJson("/api/projects", body).path("id").asText();
    }

    /** 有合同号的门店出具报价后即有应收。返回门店 ID。 */
    private String signedStore(String customer, String name, String contractNo, String price) throws Exception {
        String project = store(customer, name, contractNo);
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

    private String namedCustomer(String name) throws Exception {
        return postJson("/api/partners", Map.of("name", name, "kind", "CUSTOMER")).path("id").asText();
    }

    private Map<String, Object> project(String id) {
        return jdbcTemplate.queryForMap("SELECT * FROM project WHERE id=?", id);
    }

    @Test
    void strangersMustBindWithAOneTimeCodeFromTheWebsite() {
        assertThat(say("stranger", "应收")).contains("还没有绑定").contains("绑定 12345678");
        assertThat(say("stranger", "绑定 99999999")).contains("绑定码不对或已过期");

        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES('20000001',(SELECT id FROM app_user WHERE username='finance1'),?)",
            LocalDateTime.now().plusMinutes(5)
        );
        // 发在群里的码当场作废，群里别人拿去单聊也绑不上
        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES('20000000',(SELECT id FROM app_user WHERE username='finance1'),?)",
            LocalDateTime.now().plusMinutes(5)
        );
        assertThat(sayInGroup("stranger", "绑定 20000000")).contains("不要发在群里").contains("已经作废");
        assertThat(say("bystander", "绑定 20000000")).contains("绑定码不对或已过期");
        // 过期的码无效
        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES('20000002',(SELECT id FROM app_user WHERE username='finance1'),?)",
            LocalDateTime.now().minusMinutes(1)
        );
        assertThat(say("stranger", "绑定 20000002")).contains("绑定码不对或已过期");

        assertThat(say("stranger", "绑定 20000001")).contains("绑定成功").contains("财务");
        // 码用过即作废，别人拿去也没用
        assertThat(say("someone-else", "绑定 20000001")).contains("绑定码不对或已过期");
        assertThat(say("stranger", "帮助")).contains("应收").contains("回复“确认”");

        // 账号被停用后机器人不再认
        jdbcTemplate.update("UPDATE app_user SET enabled=FALSE WHERE username='finance1'");
        assertThat(say("stranger", "应收")).contains("还没有绑定");
        jdbcTemplate.update("UPDATE app_user SET enabled=TRUE WHERE username='finance1'");

        assertThat(say("stranger", "解绑")).contains("已解除绑定");
        assertThat(say("stranger", "应收")).contains("还没有绑定");
    }

    @Test
    void repeatedWrongCodesAreLockedOut() {
        for (int i = 0; i < 5; i++) assertThat(say("guesser", "绑定 3000000" + i)).contains("绑定码不对或已过期");
        jdbcTemplate.update(
            "INSERT INTO bot_bind_code(code,user_id,expires_at) VALUES('30000009',(SELECT id FROM app_user WHERE username='viewer1'),?)",
            LocalDateTime.now().plusMinutes(5)
        );
        assertThat(say("guesser", "绑定 30000009")).contains("连续输错太多次");
    }

    @Test
    void queriesFollowTheBoundAccountsRoleAndScope() throws Exception {
        String customer = namedCustomer("机器人测试客户甲");
        signedStore(customer, "LK-1 小象甲城一号站", "BOT-A-01", "500.00");
        signedStore(customer, "LK-2 小象甲城二号站", "BOT-A-02", "300.00");
        bind("u-finance", "finance1");
        bind("u-sales", "sales1");
        bind("u-viewer", "viewer1");

        assertThat(say("u-finance", "应收 机器人测试客户甲"))
            .contains("未回款 800.00 元")
            .contains("BOT-A-01")
            .contains("BOT-A-02");
        assertThat(say("u-finance", "应收")).contains("未回款合计").contains("机器人测试客户甲");
        assertThat(say("u-finance", "合同 BOT-A-01"))
            .contains("合同金额：500.00")
            .contains("未回款：500.00")
            .contains("毛利");
        assertThat(say("u-finance", "合同 机器人测试客户甲")).contains("找到 2 份合同");
        assertThat(say("u-finance", "本月")).contains("回款：").contains("目前未回款");
        assertThat(say("u-finance", "逾期")).doesNotContain("BOT-A-01");
        assertThat(say("u-finance", "待发货")).contains("甲城一号站").contains("甲城二号站");
        assertThat(say("u-finance", "发货 甲城一号站")).contains("LK-1 小象甲城一号站").contains("未发货");

        // 只读账号看得到合同，看不到毛利
        assertThat(say("u-viewer", "合同 BOT-A-01")).contains("合同金额：500.00").doesNotContain("毛利");
        // 业务员不是这些门店的负责人：查不到
        assertThat(say("u-sales", "应收 机器人测试客户甲")).contains("没有找到");
        assertThat(say("u-sales", "合同 BOT-A-01")).contains("没有找到");
        assertThat(say("u-sales", "发货 甲城一号站")).contains("没有找到");

        // 群里不回答金额，只办发货相关的事
        assertThat(sayInGroup("u-finance", "@机器人 应收 机器人测试客户甲"))
            .contains("请和我单聊")
            .doesNotContain("800");
        assertThat(sayInGroup("u-finance", "@机器人 合同 BOT-A-01")).contains("请和我单聊");
        assertThat(sayInGroup("u-finance", "@机器人 发货 甲城一号站")).contains("LK-1 小象甲城一号站");
        assertThat(say("u-finance", "今天天气怎么样")).contains("没看懂");
    }

    @Test
    void trackingNumbersAreRegisteredOnlyAfterConfirmation() throws Exception {
        String customer = namedCustomer("机器人测试客户乙");
        String one = signedStore(customer, "LK-3 小象乙城盛泰站", "BOT-B-01", "500.00");
        String two = signedStore(customer, "LK-4 小象乙城博林站", "BOT-B-02", "300.00");
        bind("u-admin", "admin");

        String preview = say(
            "u-admin",
            "乙城盛泰站 顺丰 SF1234567890123\n乙城博林站 DPK202611150001\n不存在的店 SF9999999999999"
        );
        assertThat(preview)
            .contains("将登记发货（2 个门店）")
            .contains("LK-3 小象乙城盛泰站：顺丰速运 SF1234567890123")
            .contains("德邦快递 DPK202611150001（快递公司按单号推断）")
            .contains("没有找到这个门店")
            .contains("回复“确认”");
        // 预览阶段什么都没写
        assertThat(project(one).get("tracking_no")).isNull();

        assertThat(say("u-admin", "确认")).contains("已为 2 个门店登记运单");
        assertThat(project(one)).containsEntry("tracking_no", "SF1234567890123").containsEntry("courier", "顺丰速运");
        assertThat(project(one).get("shipping_status")).isEqualTo("已发货");
        assertThat(project(two)).containsEntry("tracking_no", "DPK202611150001");
        // 确认过一次就没有了
        assertThat(say("u-admin", "确认")).contains("没有等待确认的内容");
        // 操作日志记在绑定的账号名下
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT actor FROM audit_log WHERE action='PROJECT_DELIVERY' AND entity_id=?",
                String.class,
                one
            )
        ).isEqualTo("admin");

        // 换单号：预览里说明会替换；取消则不动
        assertThat(say("u-admin", "发货 乙城盛泰站 SF0000000000001")).contains("将替换原运单 SF1234567890123");
        assertThat(say("u-admin", "取消")).contains("已取消");
        assertThat(project(one)).containsEntry("tracking_no", "SF1234567890123");
    }

    @Test
    void consigneeDetailsNeedAStoreNameAndKeepOtherFields() throws Exception {
        String customer = namedCustomer("机器人测试客户丙");
        String one = signedStore(customer, "LK-5 小象丙城一号站", "BOT-C-01", "500.00");
        bind("u-admin", "admin");
        jdbcTemplate.update("UPDATE project SET expected_ship_on=? WHERE id=?", LocalDate.now().plusDays(3), one);

        assertThat(say("u-admin", "张三 13800001111 成都市武侯区测试路1号")).contains("不知道是哪个门店");
        String preview = say(
            "u-admin",
            "丙城一号站\n收货人：李店长\n电话：138-0000-2222\n地址：浙江省杭州市临平区上环桥路12号1楼"
        );
        assertThat(preview)
            .contains("将更新 **LK-5 小象丙城一号站**")
            .contains("收货人：李店长")
            .contains("电话：13800002222");
        // “好的”不算确认，什么都不写
        assertThat(say("u-admin", "好的")).contains("回复“确认”");
        assertThat(project(one).get("consignee")).isNull();
        assertThat(say("u-admin", "确认")).contains("已更新");
        var p = project(one);
        assertThat(p).containsEntry("consignee", "李店长").containsEntry("consignee_phone", "13800002222");
        assertThat(p).containsEntry("ship_address", "浙江省杭州市临平区上环桥路12号1楼");
        // 没提到的内容不动：预计发货日期还在，也没有被标成已发货
        assertThat(p.get("expected_ship_on")).isNotNull();
        assertThat(p.get("shipping_status")).isNull();

        // 财务不能登记发货：确认时被服务拒绝，什么都不写
        bind("u-finance", "finance1");
        assertThat(say("u-finance", "丙城一号站 SF1234567890123")).contains("将登记发货");
        assertThat(say("u-finance", "确认")).contains("已为 0 个门店").contains("角色不能办理");
        assertThat(project(one).get("tracking_no")).isNull();
    }

    @Test
    void bankMessageBecomesAReceiptAcrossContractsAfterConfirmation() throws Exception {
        String customer = namedCustomer("机器人回款客户");
        signedStore(customer, "LK-6 小象丁城一号站", "BOT-D-01", "500.00");
        signedStore(customer, "LK-7 小象丁城二号站", "BOT-D-02", "300.00");
        bind("u-finance", "finance1");
        bind("u-sales", "sales1");
        String sms =
            "【招商银行】您账户8888于" +
            LocalDate.now().getMonthValue() +
            "月" +
            LocalDate.now().getDayOfMonth() +
            "日收到人民币800.00元，付方：上海机器人回款客户有限公司，流水号：BOT20261116001，余额1,234,567.89元。";

        // 业务员不能登记回款
        assertThat(say("u-sales", sms)).contains("角色不能办理");
        // 群里不处理金额
        assertThat(sayInGroup("u-finance", sms)).contains("请和我单聊");

        String preview = say("u-finance", sms);
        assertThat(preview)
            .contains("将登记回款")
            .contains("客户：机器人回款客户")
            .contains("到账：800.00 元")
            .contains("BOT-D-01　500.00")
            .contains("BOT-D-02　300.00")
            .contains("金额正好等于这些合同的未回款");
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_batch WHERE customer_id=?",
                Integer.class,
                customer
            )
        ).isZero();

        assertThat(say("u-finance", "确认")).contains("已登记回款 BOT20261116001").contains("分到 2 份合同");
        var batch = jdbcTemplate.queryForMap("SELECT * FROM receipt_batch WHERE customer_id=?", customer);
        assertThat(batch).containsEntry("created_by", "finance1").containsEntry("note", "经企业微信机器人登记");
        assertThat(say("u-finance", "应收 机器人回款客户")).contains("没有未回款");

        // 同一条短信再发一次：流水号已登记，不再给预览
        assertThat(say("u-finance", sms)).contains("已经登记过了");
        // 对不上客户时说明怎么补
        assertThat(say("u-finance", "您账户收到人民币100.00元，付方：某某不认识的公司")).contains("对不上系统里的客户");
        // 没有流水号的同额回款：提醒可能重复
        assertThat(say("u-finance", "收到人民币800.00元 客户 机器人回款客户")).contains(
            "同一天已经有一笔同样金额的回款"
        );
        assertThat(say("u-finance", "取消")).contains("已取消");
    }

    @Test
    void filesOtherThanReadableInvoicesAreExplained() throws Exception {
        bind("u-finance", "finance1");
        bind("u-sales", "sales1");
        var doc = new org.apache.pdfbox.pdmodel.PDDocument();
        doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
        var out = new java.io.ByteArrayOutputStream();
        doc.save(out);
        doc.close();
        byte[] blank = out.toByteArray();

        assertThat(
            bot.handle(new BotMessage(WECOM, "u-finance", "u-finance", false, null, "扫描件.pdf", blank))
        ).contains("没有认出发票内容");
        assertThat(
            bot.handle(new BotMessage(WECOM, "u-finance", "u-finance", false, null, "报价.xlsx", new byte[] { 1 }))
        ).contains("只能读 PDF");
        assertThat(bot.handle(new BotMessage(WECOM, "u-sales", "u-sales", false, null, "发票.pdf", blank))).contains(
            "角色不能办理"
        );
    }
}
