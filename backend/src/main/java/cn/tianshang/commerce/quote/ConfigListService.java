package cn.tianshang.commerce.quote;

import cn.tianshang.commerce.common.ApiException;
import cn.tianshang.commerce.master.MasterService;
import java.io.IOException;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 按客户的配置清单报价：读出清单里的每一行，对到产品库里的产品。对上的直接带入报价，
 * 对不上或有几个相近的，列出候选让人选；产品库里没有的，可以当场加进产品库。只是建议，不写任何数据。
 */
@Service
public class ConfigListService {

    static final long MAX_BYTES = 10L * 1024 * 1024;

    private final MasterService masters;

    public ConfigListService(MasterService masters) {
        this.masters = masters;
    }

    /** 读清单并对产品。返回：工作表名、每行（原文 + 对上的产品 + 候选）。 */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','SALES')")
    public Map<String, Object> read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("请选择客户的配置清单");
        if (file.getSize() > MAX_BYTES) throw new ApiException("配置清单不能超过 10 MB");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) throw new ApiException(
            "目前只能读 Excel 格式的配置清单（.xlsx 或 .xls）"
        );
        ConfigListReader.Result result;
        try (var in = file.getInputStream()) {
            result = ConfigListReader.read(in);
        } catch (IOException e) {
            throw new ApiException("读取配置清单失败");
        }
        var products = masters.products();
        var rows = new ArrayList<Map<String, Object>>();
        for (var l : result.lines()) rows.add(match(l, products));
        var out = new LinkedHashMap<String, Object>();
        out.put("sheet", result.sheet());
        out.put("rows", rows);
        return out;
    }

    /** 名称、规格统一写法后比较：去空格、全角转半角、乘号统一为 *、去掉 mm。 */
    static String key(Object v) {
        if (v == null) return "";
        var sb = new StringBuilder();
        for (char c : v.toString().toCharArray()) sb.append(c >= '！' && c <= '～' ? (char) (c - 0xfee0) : c);
        return sb
            .toString()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\u3000]+", "")
            .replaceAll("[×xX✕]", "*")
            .replace("mm", "");
    }

    static Map<String, Object> match(ConfigListReader.Line l, List<Map<String, Object>> products) {
        String name = key(l.name()),
            spec = key(l.specification()),
            model = key(l.model());
        record Scored(Map<String, Object> p, int score, boolean exact) {}
        var scored = new ArrayList<Scored>();
        for (var p : products) {
            String pn = key(p.get("name")),
                ps = key(p.get("specification")),
                pm = key(p.get("model"));
            int s = 0;
            if (pn.equals(name)) s += 4;
            else if (!pn.isEmpty() && (pn.contains(name) || name.contains(pn))) s += 2;
            else continue; // 名称完全不沾边的不算候选
            if (!spec.isEmpty() && ps.equals(spec)) s += 4;
            else if (!spec.isEmpty() && !ps.isEmpty() && (ps.contains(spec) || spec.contains(ps))) s += 1;
            else if (spec.isEmpty() && ps.isEmpty()) s += 2;
            if (!model.isEmpty() && pm.equals(model)) s += 2;
            boolean exact = pn.equals(name) && (ps.equals(spec) || (spec.isEmpty() && ps.isEmpty()));
            scored.add(new Scored(p, s, exact));
        }
        scored.sort((a, b) -> b.score() - a.score());
        var exact = scored.stream().filter(Scored::exact).toList();
        String productId = null,
            how = "NONE";
        if (exact.size() == 1) {
            productId = exact.get(0).p().get("id").toString();
            how = "EXACT";
        } else if (exact.isEmpty() && !scored.isEmpty() && scored.get(0).score() >= 6) {
            // 名称相近、规格一致，且明显比第二个更像
            if (scored.size() == 1 || scored.get(0).score() > scored.get(1).score()) {
                productId = scored.get(0).p().get("id").toString();
                how = "SIMILAR";
            }
        }
        var candidates = new ArrayList<Map<String, Object>>();
        for (var s : scored.subList(0, Math.min(5, scored.size())))
            candidates.add(Map.of("id", s.p().get("id"), "label", label(s.p())));
        var row = new LinkedHashMap<String, Object>();
        row.put("row", l.row());
        row.put("name", l.name());
        row.put("specification", l.specification());
        row.put("model", l.model());
        row.put("quantity", l.quantity());
        row.put("unit", l.unit());
        row.put("note", l.note());
        row.put("productId", productId);
        row.put("match", how);
        row.put("candidates", candidates);
        return row;
    }

    private static String label(Map<String, Object> p) {
        String model = Objects.toString(p.get("model"), "");
        return (
            p.get("scenario") +
            " / " +
            p.get("name") +
            " / " +
            p.get("specification") +
            (model.isEmpty() ? "" : " / " + model)
        );
    }
}
