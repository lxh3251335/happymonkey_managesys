package cn.tianshang.commerce.common;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 成本可见性：对看不到成本的角色（业务员、只读），在所有 JSON 响应中去掉成本与毛利字段。
 *
 * <p>集中在响应出口处理，新接口自动受保护；字段名约定见 {@link #SENSITIVE}。Excel 导出另由 ExcelService 处理。
 */
@RestControllerAdvice
public class CostRedaction implements ResponseBodyAdvice<Object> {

    /** 成本、毛利相关字段（各接口统一使用这些名称）。 */
    public static final Set<String> SENSITIVE = Set.of(
        "cost",
        "unit_cost",
        "material_cost",
        "labor_cost",
        "finance_cost",
        "reference_cost",
        "known_cost",
        "actual_cost_contracts",
        "estimated_cost",
        "actual_cost",
        "cost_source",
        "profit",
        "gross_margin",
        "cost_margin",
        "margin_coverage",
        "unitCost",
        "materialCost",
        "laborCost",
        "financeCost"
    );

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        if (body == null || Roles.canSeeCost()) return body;
        if (!(body instanceof Map<?, ?>) && !(body instanceof Collection<?>)) return body;
        return strip(body);
    }

    /** 深度复制并去掉敏感字段（不修改原对象）。 */
    static Object strip(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet())
                if (!SENSITIVE.contains(String.valueOf(e.getKey()))) out.put(e.getKey(), strip(e.getValue()));
            return out;
        }
        if (value instanceof Collection<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (var v : list) out.add(strip(v));
            return out;
        }
        return value;
    }
}
