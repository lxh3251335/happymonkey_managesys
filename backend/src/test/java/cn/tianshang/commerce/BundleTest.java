package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

/** 标准品的电气件清单、标准组合，以及报价和项目类型里按组合分组的明细。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class BundleTest extends ApiTestSupport {

    private String supplierId;

    /** 本测试里的产品都属于同一家供应商。 */
    private String product(String name, String spec) throws Exception {
        if (supplierId == null) supplierId = testSupplier();
        return postJson(
            "/api/products",
            Map.of("supplierId", supplierId, "scenario", "小象", "name", name, "specification", spec, "unit", "台")
        )
            .path("id")
            .asText();
    }

    private Map<String, Object> bundleInput(String name, List<Map<String, Object>> items) {
        var m = new HashMap<String, Object>();
        m.put("scenario", "小象");
        m.put("supplierId", supplierId);
        m.put("name", name);
        m.put("active", true);
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> line(String productId, Object qty) {
        return Map.of("productId", productId, "quantity", qty);
    }

    private JsonNode bundle(String id) throws Exception {
        for (var b : getJson("/api/bundles")) if (b.path("id").asText().equals(id)) return b;
        throw new AssertionError("组合不在列表里");
    }

    @Test
    void productsKeepTheirElectricalComponents() throws Exception {
        String box = product("物联网电控箱", "250*300*160");
        mvc
            .perform(
                put("/api/products/" + box + "/components")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    Map.of(
                                        "name",
                                        "断路器",
                                        "model",
                                        "DZ47-63 C16",
                                        "brand",
                                        "正泰",
                                        "quantity",
                                        2,
                                        "unit",
                                        "个"
                                    ),
                                    Map.of("name", "交流接触器", "model", "CJX2-1810", "quantity", 1)
                                )
                            )
                        )
                    )
            )
            .andExpect(status().isOk());
        var list = getJson("/api/products/" + box + "/components");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("name").asText()).isEqualTo("断路器");
        assertThat(list.get(0).path("brand").asText()).isEqualTo("正泰");
        assertThat(list.get(1).path("brand").isNull()).isTrue();
        for (var p : getJson("/api/products"))
            if (p.path("id").asText().equals(box)) assertThat(p.path("components").asInt()).isEqualTo(2);
        // 只读账号不能改
        mvc
            .perform(
                put("/api/products/" + box + "/components")
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
                    .contentType("application/json")
                    .content("{\"items\":[]}")
            )
            .andExpect(status().isForbidden());
    }

    @Test
    void productsBelongToASupplierAndBundlesToOne() throws Exception {
        String main = product("总配电箱", "650*850*250");
        // 没有供应商不能建产品
        mvc
            .perform(
                post("/api/products")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of("scenario", "小象", "name", "无主产品", "specification", "1", "unit", "台")
                        )
                    )
            )
            .andExpect(status().isBadRequest());
        // 同一家供应商同样的产品不能重复；换一家供应商可以再建一条
        mvc
            .perform(
                post("/api/products")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "supplierId",
                                supplierId,
                                "scenario",
                                "小象",
                                "name",
                                "总配电箱",
                                "specification",
                                "650*850*250",
                                "unit",
                                "台"
                            )
                        )
                    )
            )
            .andExpect(status().isBadRequest());
        String other = testSupplier();
        String otherMain = postJson(
            "/api/products",
            Map.of(
                "supplierId",
                other,
                "scenario",
                "小象",
                "name",
                "总配电箱",
                "specification",
                "650*850*250",
                "unit",
                "台",
                "purchaseCost",
                "2800"
            )
        )
            .path("id")
            .asText();
        assertThat(otherMain).isNotEqualTo(main);
        // 组合只能放同一家供应商的产品
        mvc
            .perform(
                post("/api/bundles")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(bundleInput("混装", List.of(line(main, 1), line(otherMain, 1)))))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("同一家供应商")));
        // 组合列表带供应商和采购成本；业务员看不到成本
        var otherInput = bundleInput("另一家的组合", List.of(line(otherMain, 2)));
        otherInput.put("supplierId", other);
        String id = postJson("/api/bundles", otherInput).path("id").asText();
        var b = bundle(id);
        assertThat(b.path("supplier_name").asText()).startsWith("测试供应商");
        assertThat(b.path("reference_cost").decimalValue()).isEqualByComparingTo("5600");
        assertThat(b.path("cost_complete").asBoolean()).isTrue();
        String asSales = read(
            mvc.perform(get("/api/bundles").with(user("sales1").roles("SALES"))).andExpect(status().isOk()).andReturn()
        ).toString();
        assertThat(asSales).doesNotContain("reference_cost").doesNotContain("purchase_cost");
    }

    @Test
    void bundlesPackProductsPerScenario() throws Exception {
        String main = product("总配电箱", "650*850*250");
        String one = product("一控三配电箱", "550*700*250");
        String id = postJson("/api/bundles", bundleInput("冷柜配电组合", List.of(line(main, 1), line(one, 3))))
            .path("id")
            .asText();
        var b = bundle(id);
        assertThat(b.path("items").size()).isEqualTo(2);
        assertThat(b.path("items").get(1).path("quantity").decimalValue()).isEqualByComparingTo("3");
        // 还没报过价：参考价不完整
        assertThat(b.path("price_complete").asBoolean()).isFalse();

        // 同一场景下不能重名；同一产品不能出现两次
        mvc
            .perform(
                post("/api/bundles")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(bundleInput("冷柜配电组合", List.of(line(main, 1)))))
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(
                post("/api/bundles")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(bundleInput("重复组合", List.of(line(main, 1), line(main, 2)))))
            )
            .andExpect(status().isBadRequest());

        // 停用后仍在列表里（历史报价还引用它）
        var off = bundleInput("冷柜配电组合", List.of(line(main, 1), line(one, 3)));
        off.put("active", false);
        mvc
            .perform(
                put("/api/bundles/" + id)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(off))
            )
            .andExpect(status().isOk());
        assertThat(bundle(id).path("active").asBoolean()).isFalse();
    }

    @Test
    void quoteLinesAndProjectTypesRememberTheirBundle() throws Exception {
        String main = product("总配电箱", "650*850*250");
        String one = product("一控三配电箱", "550*700*250");
        String extra = product("物联网电控箱", "250*300*160");
        String bundleId = postJson("/api/bundles", bundleInput("冷柜配电组合", List.of(line(main, 1), line(one, 3))))
            .path("id")
            .asText();

        // 报价：2 套组合（每套 1 + 3）+ 1 个单品
        var group = Map.of("no", 1, "bundleId", bundleId, "sets", 2);
        var items = new ArrayList<Map<String, Object>>();
        items.add(Map.of("productId", main, "quantity", 2, "unitPrice", "3000", "group", group));
        items.add(Map.of("productId", one, "quantity", 6, "unitPrice", "2000", "group", group));
        items.add(Map.of("productId", extra, "quantity", 1, "unitPrice", "6500"));
        String quote = postJson(
            "/api/quotes",
            Map.of(
                "projectId",
                project(),
                "taxBasis",
                "INCLUDED",
                "taxRate",
                "0.13",
                "validUntil",
                LocalDate.now().plusDays(30).toString(),
                "items",
                items
            )
        )
            .path("id")
            .asText();
        var detail = getJson("/api/quotes/" + quote);
        var lines = detail.path("items");
        assertThat(lines.get(0).path("bundle_name").asText()).isEqualTo("冷柜配电组合");
        assertThat(lines.get(0).path("bundle_sets").decimalValue()).isEqualByComparingTo("2");
        assertThat(lines.get(1).path("bundle_group").asInt()).isEqualTo(1);
        assertThat(lines.get(2).path("bundle_id").isNull()).isTrue();
        assertThat(detail.path("amount").decimalValue()).isEqualByComparingTo("24500");
        // 对客报价单也带组合信息（不带成本）
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        var copy = getJson("/api/quotes/" + quote + "/customer-copy").path("items");
        assertThat(copy.get(0).path("bundle_name").asText()).isEqualTo("冷柜配电组合");
        assertThat(copy.get(0).has("cost")).isFalse();

        // 同一组里组合或套数不一致：拒绝
        var bad = new ArrayList<>(items);
        bad.set(
            1,
            Map.of(
                "productId",
                one,
                "quantity",
                6,
                "unitPrice",
                "2000",
                "group",
                Map.of("no", 1, "bundleId", bundleId, "sets", 3)
            )
        );
        mvc
            .perform(
                post("/api/quotes")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "projectId",
                                project(),
                                "taxBasis",
                                "INCLUDED",
                                "taxRate",
                                "0.13",
                                "validUntil",
                                LocalDate.now().plusDays(30).toString(),
                                "items",
                                bad
                            )
                        )
                    )
            )
            .andExpect(status().isBadRequest());

        // 项目类型：组合里和组合外可以有同一个产品
        var typeItems = List.of(
            Map.of(
                "productId",
                main,
                "quantity",
                1,
                "unitPrice",
                "3000",
                "group",
                Map.of("no", 1, "bundleId", bundleId, "sets", 1)
            ),
            Map.of(
                "productId",
                one,
                "quantity",
                3,
                "unitPrice",
                "2000",
                "group",
                Map.of("no", 1, "bundleId", bundleId, "sets", 1)
            ),
            Map.of("productId", main, "quantity", 1, "unitPrice", "3000")
        );
        String type = postJson(
            "/api/project-types",
            Map.of("name", "组合测试类型", "brand", "小象", "active", true, "items", typeItems)
        )
            .path("id")
            .asText();
        var prefill = getJson("/api/project-types/" + type + "/prefill").path("items");
        assertThat(prefill.size()).isEqualTo(3);
        assertThat(prefill.get(0).path("bundle_name").asText()).isEqualTo("冷柜配电组合");
        assertThat(prefill.get(2).path("bundle_id").isNull()).isTrue();
    }
}
