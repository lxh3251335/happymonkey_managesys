package cn.tianshang.commerce.master;

import java.util.*;

/**
 * 从门店名称识别城市：先匹配城市名，再匹配区县/镇等别名（归到所属城市）。识别不到返回空，由人工补填。
 *
 * <p>城市与别名在“系统管理 → 数据字典 → 城市”中维护；这里的内置清单只用于首次初始化城市字典。
 */
public final class Cities {

    private Cities() {}

    /** 字典中的一个城市：名称 + 别名。 */
    public record Entry(String name, List<String> aliases) {}

    /** 内置城市清单：省份 → 城市。 */
    public static final Map<String, List<String>> BUILT_IN = new LinkedHashMap<>();
    /** 内置别名：城市 → 区县、镇、商圈。 */
    public static final Map<String, List<String>> BUILT_IN_ALIASES = new LinkedHashMap<>();

    static {
        BUILT_IN.put("北京", List.of("北京"));
        BUILT_IN.put("上海", List.of("上海"));
        BUILT_IN.put("天津", List.of("天津"));
        BUILT_IN.put("重庆", List.of("重庆"));
        BUILT_IN.put(
            "河北",
            List.of("石家庄", "唐山", "保定", "廊坊", "邯郸", "邢台", "沧州", "衡水", "承德", "张家口", "秦皇岛")
        );
        BUILT_IN.put("山西", List.of("太原", "大同", "晋中", "长治", "临汾", "运城"));
        BUILT_IN.put("内蒙古", List.of("呼和浩特", "包头"));
        BUILT_IN.put("辽宁", List.of("沈阳", "大连"));
        BUILT_IN.put("吉林", List.of("长春"));
        BUILT_IN.put("黑龙江", List.of("哈尔滨"));
        BUILT_IN.put("江苏", List.of("南京", "苏州", "无锡", "常州", "南通", "徐州", "扬州", "泰州", "镇江", "盐城"));
        BUILT_IN.put("浙江", List.of("杭州", "宁波", "温州", "嘉兴", "湖州", "绍兴", "金华", "台州"));
        BUILT_IN.put("安徽", List.of("合肥", "芜湖"));
        BUILT_IN.put("福建", List.of("福州", "厦门", "泉州"));
        BUILT_IN.put("江西", List.of("南昌"));
        BUILT_IN.put("山东", List.of("济南", "青岛", "烟台", "潍坊", "临沂", "淄博"));
        BUILT_IN.put("河南", List.of("郑州", "洛阳"));
        BUILT_IN.put("湖北", List.of("武汉"));
        BUILT_IN.put("湖南", List.of("长沙"));
        BUILT_IN.put("广东", List.of("广州", "深圳", "东莞", "佛山", "珠海", "惠州", "中山"));
        BUILT_IN.put("广西", List.of("南宁"));
        BUILT_IN.put("海南", List.of("海口", "三亚"));
        BUILT_IN.put("四川", List.of("成都", "绵阳", "德阳", "宜宾", "乐山", "南充"));
        BUILT_IN.put("贵州", List.of("贵阳"));
        BUILT_IN.put("云南", List.of("昆明"));
        BUILT_IN.put("西藏", List.of("拉萨"));
        BUILT_IN.put("陕西", List.of("西安", "咸阳"));
        BUILT_IN.put("甘肃", List.of("兰州"));
        BUILT_IN.put("青海", List.of("西宁"));
        BUILT_IN.put("宁夏", List.of("银川"));
        BUILT_IN.put("新疆", List.of("乌鲁木齐"));

        BUILT_IN_ALIASES.put(
            "北京",
            List.of("大兴", "丰台", "怀柔", "密云", "顺义", "通州", "昌平", "房山", "朝阳", "海淀", "小红门")
        );
        BUILT_IN_ALIASES.put("廊坊", List.of("燕郊", "三河", "香河", "固安", "大厂"));
        BUILT_IN_ALIASES.put("金华", List.of("义乌"));
        BUILT_IN_ALIASES.put("湖州", List.of("吴兴", "织里"));
        BUILT_IN_ALIASES.put("宁波", List.of("慈溪"));
        BUILT_IN_ALIASES.put("嘉兴", List.of("桐乡"));
        BUILT_IN_ALIASES.put("东莞", List.of("寮步"));
        BUILT_IN_ALIASES.put("广州", List.of("白云"));
        BUILT_IN_ALIASES.put("成都", List.of("温江"));
    }

    /** 内置清单（用于没有字典的场合，如单元测试）。 */
    public static List<Entry> builtIn() {
        var out = new ArrayList<Entry>();
        for (var cities : BUILT_IN.values())
            for (String c : cities) out.add(new Entry(c, BUILT_IN_ALIASES.getOrDefault(c, List.of())));
        return out;
    }

    public static String detect(String storeName) {
        return detect(storeName, builtIn());
    }

    /**
     * 城市名优先（如“天津宝坻”），避免区县别名误判；名称较长的城市先匹配（“张家口”先于“张家”类短名）。
     * 先去掉业务品牌词，避免品牌名中的字误判。
     */
    public static String detect(String storeName, List<Entry> cities) {
        if (storeName == null) return null;
        String n = storeName.replace("小象超市", "").replace("快乐猴", "").replace("小象", "");
        var byLength = new ArrayList<>(cities);
        byLength.sort(Comparator.comparingInt((Entry e) -> -e.name().length()));
        for (var c : byLength) if (!c.name().isEmpty() && n.contains(c.name())) return c.name();
        for (var c : cities) for (String a : c.aliases()) if (!a.isEmpty() && n.contains(a)) return c.name();
        return null;
    }

    /** 别名文本拆分：逗号、顿号、空格均可分隔。 */
    public static List<String> splitAliases(String text) {
        if (text == null || text.isBlank()) return List.of();
        var out = new LinkedHashSet<String>();
        for (String s : text.split("[,，、;；\\s]+")) if (!s.isBlank()) out.add(s.trim());
        return new ArrayList<>(out);
    }
}
