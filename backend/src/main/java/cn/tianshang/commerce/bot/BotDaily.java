package cn.tianshang.commerce.bot;

import static cn.tianshang.commerce.bot.BotText.*;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.common.Roles;
import cn.tianshang.commerce.contract.ContractRecordService;
import cn.tianshang.commerce.todo.TodoService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 机器人里的“今日待办”和手机上审批：“今日”看今天要处理的事，“待审批”列出合同变更申请，
 * 回复“批准 1”或“驳回 1 原因”直接办理。审批走网页用的同一个服务，只有管理层和管理员能办，留痕一样。
 */
@Component
class BotDaily {

    static final Duration LIST_TTL = Duration.ofMinutes(30);
    private static final Pattern NUMBER_AND_REASON = Pattern.compile("^(\\d{1,3})\\s*(.*)$", Pattern.DOTALL);

    /** 各项待办在机器人里对应的指令；没有的到网页上处理。 */
    private static final Map<String, String> COMMAND_OF = Map.of(
        "CHANGES",
        "待审批",
        "OVERDUE",
        "逾期",
        "LATE_SHIP",
        "待发货"
    );

    private record Listed(Instant at, List<String> ids) {}

    private final TodoService todos;
    private final ContractRecordService records;
    /** 每个人最近一次“待审批”列出的申请编号顺序，“批准 1”按它找申请。 */
    private final Map<String, Listed> listed = new ConcurrentHashMap<>();

    BotDaily(TodoService todos, ContractRecordService records) {
        this.todos = todos;
        this.records = records;
    }

    /** 今日待办；没有待办时 empty 为 true。 */
    record Digest(String text, boolean empty) {}

    Digest today() {
        var items = todos.today();
        var d = LocalDate.now();
        var sb = new StringBuilder("**今日待办**（" + d.getMonthValue() + " 月 " + d.getDayOfMonth() + " 日）");
        if (items.isEmpty()) return new Digest(sb.append("\n今天没有需要处理的事。").toString(), true);
        for (var i : items) {
            sb.append("\n· ").append(i.label()).append(" ").append(i.count()).append(" ").append(i.unit());
            if (i.amount() != null) sb.append("，").append(money(i.amount())).append(" 元");
            String cmd = COMMAND_OF.get(i.key());
            if (cmd != null) sb.append("（发“").append(cmd).append("”查看）");
        }
        return new Digest(sb.toString(), false);
    }

    String pending(String key) {
        if (!Roles.canApprove()) return "只有管理层和管理员能审批合同变更。";
        var rows = records.pendingChanges();
        if (rows.isEmpty()) {
            listed.remove(key);
            return "目前没有待审批的合同变更。";
        }
        var ids = new ArrayList<String>();
        var sb = new StringBuilder("**待审批变更 " + rows.size() + " 条**");
        int n = 0;
        for (var r : rows) {
            ids.add(r.get("id").toString());
            String no = str(r.get("contract_no"));
            sb
                .append("\n\n")
                .append(++n)
                .append(". ")
                .append(no.startsWith("#") ? "未填合同号" : no)
                .append("　")
                .append(str(r.get("customer_name")))
                .append("\n申请人：")
                .append(str(r.get("requester_name")).isEmpty() ? str(r.get("requested_by")) : r.get("requester_name"))
                .append("　原因：")
                .append(str(r.get("reason")));
            if (r.get("changes") instanceof List<?> changes) for (Object o : changes)
                if (o instanceof Map<?, ?> c) sb
                    .append("\n· ")
                    .append(str(c.get("label")))
                    .append("：")
                    .append(c.get("before") == null ? "空" : str(c.get("before")))
                    .append(" → ")
                    .append(c.get("after") == null ? "空" : str(c.get("after")));
        }
        listed.put(key, new Listed(Instant.now(), ids));
        return sb.append("\n\n回复“批准 1”或“驳回 1 原因”办理。").toString();
    }

    String approve(String key, String arg) {
        String id = pick(key, arg).id();
        records.approve(id, new ContractRecordService.ReviewInput("经企业微信机器人审批"));
        return "已批准，合同已按申请修改。" + remaining();
    }

    String reject(String key, String arg) {
        var p = pick(key, arg);
        if (p.rest().isBlank()) throw new ApiException("驳回要写原因，例如：驳回 " + p.n() + " 金额和客户确认的不一致");
        records.reject(p.id(), new ContractRecordService.ReviewInput(p.rest()));
        return "已驳回。" + remaining();
    }

    private record Picked(int n, String id, String rest) {}

    /** 按最近一次列出的序号找到申请。列表过期或序号不对时说明怎么做。 */
    private Picked pick(String key, String arg) {
        if (!Roles.canApprove()) throw new ApiException("只有管理层和管理员能审批合同变更。");
        Matcher m = NUMBER_AND_REASON.matcher(arg.strip());
        if (!m.matches()) throw new ApiException("请带上序号，例如“批准 1”。先发“待审批”看序号。");
        var l = listed.get(key);
        if (l == null || l.at().plus(LIST_TTL).isBefore(Instant.now())) throw new ApiException(
            "请先发“待审批”看最新的申请和序号。"
        );
        int n = Integer.parseInt(m.group(1));
        if (n < 1 || n > l.ids().size()) throw new ApiException("没有第 " + n + " 条。请先发“待审批”看最新的序号。");
        return new Picked(n, l.ids().get(n - 1), m.group(2).strip());
    }

    private String remaining() {
        int left = records.pendingChanges().size();
        return left == 0 ? "待审批的都处理完了。" : "还有 " + left + " 条待审批，发“待审批”查看。";
    }
}
