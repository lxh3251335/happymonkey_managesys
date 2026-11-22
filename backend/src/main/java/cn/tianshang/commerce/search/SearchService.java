package cn.tianshang.commerce.search;

import cn.tianshang.commerce.contract.ContractService;
import cn.tianshang.commerce.master.MasterService;
import cn.tianshang.commerce.project.ProjectService;
import cn.tianshang.commerce.quote.QuoteService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 顶栏的全局搜索：输入门店名、项目编号、运单号、合同号、报价编号或客户名，直接跳到对应的地方。
 *
 * <p>数据取自网页用的同一批服务，业务员只搜得到自己负责的门店、合同和报价。每类最多返回几条，链接打开的是对应的详情或筛选结果。
 */
@Service
public class SearchService {

    static final int PER_GROUP = 5;

    /** 一条搜索结果：group 门店 / 合同 / 报价 / 客户；sub 是第二行的说明。 */
    public record Hit(String group, String title, String sub, String link) {}

    private final ProjectService projects;
    private final ContractService contracts;
    private final QuoteService quotes;
    private final MasterService masters;

    public SearchService(
        ProjectService projects,
        ContractService contracts,
        QuoteService quotes,
        MasterService masters
    ) {
        this.projects = projects;
        this.contracts = contracts;
        this.quotes = quotes;
        this.masters = masters;
    }

    public List<Hit> search(String query) {
        String q = key(query);
        if (q.isEmpty()) return List.of();
        var out = new ArrayList<Hit>();

        // 门店：名称、项目编号、合同号、运单号
        int n = 0;
        for (var p : projects.list()) {
            if (!matches(q, p.get("name"), p.get("code"), p.get("contract_no"), p.get("tracking_no"))) continue;
            boolean dealt =
                !"NONE".equals(p.get("contract_state")) ||
                (p.get("confirmed_quotes") instanceof Number c && c.intValue() > 0);
            String sub = join(
                p.get("customer_name"),
                p.get("code"),
                text(p.get("shipping_status")).isEmpty() ? "未发货" : p.get("shipping_status")
            );
            out.add(
                new Hit(
                    "门店",
                    text(p.get("name")),
                    sub,
                    "/projects?q=" + enc(text(p.get("name"))) + (dealt ? "" : "&scope=quoting")
                )
            );
            if (++n >= PER_GROUP) break;
        }

        // 合同：合同号、合同名称
        n = 0;
        for (var c : contracts.contracts()) {
            String key = text(c.get("contract_key"));
            if (!matches(q, key, c.get("title"))) continue;
            out.add(
                new Hit(
                    "合同",
                    key.startsWith("#") ? "未填合同号" : key,
                    join(c.get("customer_name"), c.get("title"), c.get("projects") + " 个门店"),
                    "/cash?tab=contracts&customer=" + enc(text(c.get("customer_id"))) + "&contract=" + enc(key)
                )
            );
            if (++n >= PER_GROUP) break;
        }

        // 报价：报价编号
        n = 0;
        for (var r : quotes.list()) {
            if (!matches(q, r.get("code"))) continue;
            out.add(
                new Hit(
                    "报价",
                    text(r.get("code")),
                    join(r.get("project_name"), r.get("customer_name"), "V" + r.get("version")),
                    "/quotes?detail=" + enc(text(r.get("id")))
                )
            );
            if (++n >= PER_GROUP) break;
        }

        // 客户：名称、开票抬头。打开该客户的合同
        n = 0;
        for (var c : masters.partners()) {
            if (!"CUSTOMER".equals(c.get("kind")) || !matches(q, c.get("name"), c.get("invoice_title"))) continue;
            out.add(
                new Hit(
                    "客户",
                    text(c.get("name")),
                    text(c.get("invoice_title")),
                    "/cash?tab=contracts&cust=" + enc(text(c.get("name")))
                )
            );
            if (++n >= PER_GROUP) break;
        }
        return out;
    }

    private static String key(Object v) {
        return text(v).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String q, Object... values) {
        for (Object v : values) if (key(v).contains(q)) return true;
        return false;
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static String join(Object... parts) {
        var out = new ArrayList<String>();
        for (Object p : parts) if (!text(p).isEmpty()) out.add(text(p));
        return String.join(" · ", out);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
