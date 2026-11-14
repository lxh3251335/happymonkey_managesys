package cn.tianshang.commerce.master;

import java.util.*;

/** 从门店名称识别城市：先匹配区县/镇等别名（归到所属城市），再匹配城市名。识别不到返回空，由人工补填。 */
public final class Cities {

    private Cities() {}

    /** 区县、镇、商圈 → 所属城市。 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static final List<String> CITIES = List.of(
        "乌鲁木齐",
        "呼和浩特",
        "石家庄",
        "张家口",
        "哈尔滨",
        "北京",
        "上海",
        "天津",
        "重庆",
        "唐山",
        "保定",
        "廊坊",
        "邯郸",
        "邢台",
        "沧州",
        "衡水",
        "承德",
        "秦皇岛",
        "太原",
        "大同",
        "晋中",
        "长治",
        "临汾",
        "运城",
        "包头",
        "沈阳",
        "大连",
        "长春",
        "南京",
        "苏州",
        "无锡",
        "常州",
        "南通",
        "徐州",
        "扬州",
        "泰州",
        "镇江",
        "盐城",
        "杭州",
        "宁波",
        "温州",
        "嘉兴",
        "湖州",
        "绍兴",
        "金华",
        "台州",
        "合肥",
        "芜湖",
        "福州",
        "厦门",
        "泉州",
        "南昌",
        "济南",
        "青岛",
        "烟台",
        "潍坊",
        "临沂",
        "淄博",
        "郑州",
        "洛阳",
        "武汉",
        "长沙",
        "广州",
        "深圳",
        "东莞",
        "佛山",
        "珠海",
        "惠州",
        "中山",
        "南宁",
        "海口",
        "三亚",
        "成都",
        "绵阳",
        "德阳",
        "宜宾",
        "乐山",
        "南充",
        "贵阳",
        "昆明",
        "西安",
        "咸阳",
        "兰州",
        "西宁",
        "银川",
        "拉萨"
    );

    static {
        for (String d : List.of(
            "大兴",
            "丰台",
            "怀柔",
            "密云",
            "顺义",
            "通州",
            "昌平",
            "房山",
            "朝阳",
            "海淀",
            "小红门"
        ))
            ALIASES.put(d, "北京");
        for (String d : List.of("燕郊", "三河", "香河", "固安", "大厂")) ALIASES.put(d, "廊坊");
        ALIASES.put("义乌", "金华");
        ALIASES.put("吴兴", "湖州");
        ALIASES.put("织里", "湖州");
        ALIASES.put("慈溪", "宁波");
        ALIASES.put("桐乡", "嘉兴");
        ALIASES.put("寮步", "东莞");
        ALIASES.put("白云", "广州");
        ALIASES.put("温江", "成都");
    }

    public static String detect(String storeName) {
        if (storeName == null) return null;
        // 城市名优先（如“天津宝坻”），避免区县别名误判；但先排除品牌词干扰
        String n = storeName.replace("小象超市", "").replace("快乐猴", "").replace("小象", "");
        for (String c : CITIES) if (n.contains(c)) return c;
        for (var e : ALIASES.entrySet()) if (n.contains(e.getKey())) return e.getValue();
        return null;
    }
}
