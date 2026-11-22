package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class ProductLibraryTest extends ApiTestSupport {

    private static final byte[] DWG = "AC1032\0\0\0\0drawing".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] DXF = "  0\r\nSECTION\r\n  2\r\nHEADER\r\n".getBytes(StandardCharsets.US_ASCII);

    private String supplier() throws Exception {
        return postJson("/api/partners", Map.of("name", "图纸供应商" + UUID.randomUUID(), "kind", "SUPPLIER"))
            .path("id")
            .asText();
    }

    private JsonNode find(String id) throws Exception {
        for (var p : getJson("/api/products")) if (p.path("id").asText().equals(id)) return p;
        throw new AssertionError("product not found");
    }

    private Map<String, Object> input(String supplierId, boolean mainstream) {
        var m = new HashMap<String, Object>();
        m.put("scenario", "小象前置仓");
        m.put("name", "物联网电控箱" + UUID.randomUUID());
        m.put("specification", "250*300*160");
        m.put("unit", "台");
        m.put("supplierId", supplierId);
        m.put("mainstream", mainstream);
        return m;
    }

    @Test
    void productHasScenarioSupplierAndMainstreamTag() throws Exception {
        String sup = supplier();
        String id = postJson("/api/products", input(sup, true)).path("id").asText();
        var p = find(id);
        assertThat(p.path("scenario").asText()).isEqualTo("小象前置仓");
        assertThat(p.path("supplier_name").asText()).startsWith("图纸供应商");
        assertThat(p.path("mainstream").asBoolean()).isTrue();
        assertThat(p.path("drawings").asInt()).isZero();

        mvc
            .perform(
                put("/api/products/" + id + "/mainstream")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"mainstream\":false}")
            )
            .andExpect(status().isOk());
        assertThat(find(id).path("mainstream").asBoolean()).isFalse();

        // 供应商字段只能选供应商
        mvc
            .perform(
                post("/api/products")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(input(customer(), false)))
            )
            .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions upload(String productId, String name, byte[] body)
        throws Exception {
        return mvc.perform(
            multipart("/api/products/" + productId + "/drawings")
                .file(new MockMultipartFile("file", name, "application/octet-stream", body))
                .param("note", "V1")
                .with(csrf())
        );
    }

    @Test
    void drawingsUploadDownloadAndSoftDelete() throws Exception {
        String id = postJson("/api/products", input(supplier(), false)).path("id").asText();
        String dwg = read(upload(id, "电控箱 总装图.dwg", DWG).andExpect(status().isOk()).andReturn())
            .path("id")
            .asText();
        upload(id, "接线图.dxf", DXF).andExpect(status().isOk());
        upload(id, "电控箱 总装图.dwg", DWG).andExpect(status().isBadRequest()); // 重复
        upload(id, "fake.dwg", "MZ-not-a-drawing".getBytes()).andExpect(status().isBadRequest());
        upload(id, "readme.txt", "hello".getBytes()).andExpect(status().isBadRequest());
        assertThat(find(id).path("drawings").asInt()).isEqualTo(2);
        assertThat(getJson("/api/products/" + id + "/drawings")).hasSize(2);

        var res = mvc
            .perform(get("/api/products/drawings/" + dwg + "/file").param("inline", "true"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment"); // DWG 不内联
        assertThat(res.getContentAsByteArray()).isEqualTo(DWG);

        mvc
            .perform(delete("/api/products/drawings/" + dwg).with(csrf()).with(user("finance1").roles("FINANCE")))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/drawings/" + dwg).with(csrf())).andExpect(status().isOk());
        assertThat(find(id).path("drawings").asInt()).isEqualTo(1);
    }

    @Test
    void viewerCannotUploadDrawings() throws Exception {
        String id = postJson("/api/products", input(supplier(), false)).path("id").asText();
        mvc
            .perform(
                multipart("/api/products/" + id + "/drawings")
                    .file(new MockMultipartFile("file", "a.dwg", "application/octet-stream", DWG))
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
            )
            .andExpect(status().isForbidden());
    }
}
