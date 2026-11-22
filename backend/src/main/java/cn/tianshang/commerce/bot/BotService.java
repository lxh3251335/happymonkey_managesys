package cn.tianshang.commerce.bot;

import cn.tianshang.commerce.bot.BotBindingService.BoundUser;
import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Database;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 机器人的消息处理入口：一条消息进来，返回一段回复文字。与具体的聊天软件无关（连接见 wecom 包）。
 *
 * <p>规则：
 * <ul>
 *   <li>先认人：发消息的人要绑定系统账号，之后的每一步都以这个账号的身份和角色执行，和他登录网页时能做的事完全一样；</li>
 *   <li>群里所有人都看得到回复，所以群里只办理发货相关的事，金额一律请对方单聊；</li>
 *   <li>录入先回预览，对方回复“确认”才写入。</li>
 * </ul>
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final Pattern MENTIONS = Pattern.compile("^(@\\S+\\s+)+");
    private static final Pattern BIND = Pattern.compile("^绑定\\s*(\\d{6,10})$");
    // 只认明确的“确认”：“好”“对”“是”这类话在聊天里太常见，群里随口一句就可能把内容写进去
    private static final Pattern CONFIRM = Pattern.compile("^(确认|确定)[。.!！]?$");
    private static final Pattern CANCEL = Pattern.compile(
        "^(取消|不要|算了|否|no)[。.!！]?$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMMAND = Pattern.compile(
        "^(帮助|菜单|help|\\?|今日|今天|待办|待审批|审批|批准|驳回|应收|欠款|未回款|逾期|合同|发货|物流|待发货|未发货|本月|月报|解绑)\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final List<String> MONEY_COMMANDS = List.of(
        "今日",
        "今天",
        "待办",
        "待审批",
        "审批",
        "批准",
        "驳回",
        "应收",
        "欠款",
        "未回款",
        "逾期",
        "合同",
        "本月",
        "月报"
    );
    private static final String PRIVATE_ONLY = "金额相关的内容请和我单聊。群里可以查发货、登记运单和收货信息。";

    static final String HELP = """
        **今日**：今天要处理的事
        **审批**（管理层）：发“待审批”看申请，回复“批准 1”或“驳回 1 原因”
        **查询**
        · 应收　或　应收 客户名
        · 逾期
        · 合同 合同号或客户名
        · 发货 门店名称
        · 待发货
        · 本月
        **录入**（直接把内容转发给我，我先回预览，你回复“确认”才登记）
        · 运单：每行“门店名称 运单号”
        · 收货信息：第一行门店名称，第二行“收货人 电话 地址”
        · 回款：银行到账短信或回单上的文字
        · 发票：电子发票 PDF 文件
        群里只办理发货相关的事，金额请和我单聊。发“解绑”解除和系统账号的绑定。""";

    private final BotBindingService bindings;
    private final BotQueries queries;
    private final BotEntries entries;
    private final BotDaily daily;
    private final Database db;

    public BotService(BotBindingService bindings, BotQueries queries, BotEntries entries, BotDaily daily, Database db) {
        this.bindings = bindings;
        this.queries = queries;
        this.entries = entries;
        this.daily = daily;
        this.db = db;
    }

    /**
     * 每天早上推送给这个人的今日待办（按他绑定的系统账号计算）。没有绑定、账号停用或今天没有待办时返回 null。
     */
    public String morningDigest(String channel, String externalId) {
        var user = bindings.resolve(channel, externalId);
        if (user.isEmpty()) return null;
        try {
            var d = runAs(user.get(), daily::today);
            return d.empty() ? null : d.text() + "\n\n发“今日”可以随时再看。";
        } catch (RuntimeException e) {
            log.warn("生成今日待办失败", e);
            return null;
        }
    }

    /** 刚进入和机器人的会话时的问候。 */
    public String welcome(String channel, String externalId) {
        return bindings
            .resolve(channel, externalId)
            .map(u -> u.displayName() + "，你好。发“帮助”看我能做什么。")
            .orElse(UNBOUND);
    }

    private static final String UNBOUND =
        "你还没有绑定系统账号。请登录项目经营管理系统，在“系统管理 → 账户安全”里点“生成绑定码”，把得到的 8 位数字发给我，例如：\n绑定 12345678";

    public String handle(BotMessage m) {
        String text = clean(m.text());
        try {
            Matcher bind = BIND.matcher(text);
            if (bind.matches()) {
                if (m.group()) {
                    // 群里的人都看到了这个码，不能再用
                    bindings.revokeCode(bind.group(1));
                    return "绑定码不要发在群里，这个码已经作废。请在系统里重新生成一个，再和我单聊发送。";
                }
                var u = bindings.bind(m.channel(), m.externalId(), bind.group(1));
                return (
                    "绑定成功：" +
                    u.displayName() +
                    "（" +
                    BotText.ROLE_NAMES.getOrDefault(u.role(), u.role()) +
                    "）。发“帮助”看我能做什么。"
                );
            }
            var user = bindings.resolve(m.channel(), m.externalId());
            if (user.isEmpty()) return m.group() ? "你还没有绑定系统账号，请先和我单聊完成绑定。" : UNBOUND;
            return runAs(user.get(), () -> dispatch(m, text));
        } catch (RuntimeException e) {
            return reason(e);
        }
    }

    private String dispatch(BotMessage m, String text) {
        String key = m.channel() + "|" + m.externalId() + "|" + m.chatId();
        if (m.file() != null) {
            if (m.group()) return PRIVATE_ONLY;
            String name = m.fileName() == null ? "" : m.fileName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) return (
                "目前只能读 PDF 格式的电子发票。图片、表格或其他文件请到网页上登记。"
            );
            return entries.invoice(key, name, m.file());
        }
        if (text.isEmpty()) return HELP;
        if (CONFIRM.matcher(text).matches()) return entries.confirm(key);
        if (CANCEL.matcher(text).matches()) return entries.cancel(key);

        // 指令：一行、不长；带运单号的“发货 门店 单号”按录入处理
        Matcher c = COMMAND.matcher(text);
        if (
            !text.contains("\n") &&
            text.length() <= 60 &&
            c.matches() &&
            !BotEntries.looksLikeDelivery(text) &&
            isCommand(c.group(1), c.group(2).trim())
        ) {
            String cmd = c.group(1).toLowerCase(Locale.ROOT),
                arg = c.group(2).trim();
            if (m.group() && MONEY_COMMANDS.contains(cmd)) return PRIVATE_ONLY;
            return switch (cmd) {
                case "今日", "今天", "待办" -> daily.today().text();
                case "待审批", "审批" -> daily.pending(key);
                case "批准" -> daily.approve(key, arg);
                case "驳回" -> daily.reject(key, arg);
                case "应收", "欠款", "未回款" -> queries.receivable(arg);
                case "逾期" -> queries.overdue();
                case "合同" -> queries.contract(arg);
                case "发货", "物流" -> queries.shipping(arg);
                case "待发货", "未发货" -> queries.toShip();
                case "本月", "月报" -> queries.month();
                case "解绑" -> {
                    bindings.unbindExternal(m.channel(), m.externalId());
                    db.audit("BOT_UNBOUND", m.externalId(), "在企业微信里解除绑定");
                    yield "已解除绑定。以后要用，请在系统里重新生成绑定码。";
                }
                default -> HELP;
            };
        }

        // 转发来的内容：先看是不是到账短信，再看是不是运单或收货信息
        if (BotEntries.looksLikeReceipt(text)) return m.group() ? PRIVATE_ONLY : entries.receipt(key, text);
        // “发货 盛泰站 SF123…”“运单 …”开头的词不是门店名称
        String body = text.replaceFirst("^(发货|物流|运单|收货|登记)[\\s:：]+", "");
        if (BotEntries.looksLikeDelivery(body)) {
            String reply = entries.delivery(key, body);
            if (reply != null) return reply;
        }
        // 有等待确认的预览时，对方多半是在回应它：提示怎么确认，而不是说没看懂
        if (entries.waiting(key)) return "还有一份预览在等你确认。要登记请回复“确认”，放弃请回复“取消”。";
        return "没看懂这条消息。发“帮助”看我能做什么。";
    }

    /**
     * 这些词也常出现在普通的话里（“今天天气怎么样”“批准了吗”），只有后面不跟别的字、
     * 或“批准 / 驳回”后面跟着序号时才当指令。
     */
    private static boolean isCommand(String cmd, String arg) {
        return switch (cmd) {
            case "今日", "今天", "待办", "待审批", "审批" -> arg.isEmpty();
            case "批准", "驳回" -> arg.matches("^\\d.*");
            default -> true;
        };
    }

    /** 去掉群里 @机器人 的前缀和首尾空白。 */
    static String clean(String text) {
        return text == null ? "" : MENTIONS.matcher(text.strip()).replaceFirst("").strip();
    }

    /** 以绑定账号的身份执行：服务里的角色检查、数据范围和操作日志都按这个人来。 */
    private static <T> T runAs(BoundUser user, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                user.username(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))
            )
        );
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** 把出错原因说成对方看得懂的话；没预料到的错误只记日志，不把内部信息发出去。 */
    static String reason(RuntimeException e) {
        if (e instanceof ApiException) return e.getMessage();
        if (
            e instanceof AccessDeniedException
        ) return "你的账号角色不能办理这件事（登记回款和发票需要财务或管理员，登记发货需要业务员、管理层或管理员）。";
        log.warn("机器人处理消息失败", e);
        return "处理时出了问题，没有登记任何内容。请到网页上操作，或稍后再试。";
    }
}
