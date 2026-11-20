package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "admin", roles = "ADMIN")
class TemplateAndAttachmentTest extends ApiTestSupport {

    private static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);

    private Map<String, Object> template(String name, String company) {
        var t = new HashMap<String, Object>();
        t.put("name", name);
        t.put("companyName", company);
        t.put("title", "产品报价单");
        t.put("phone", "021-0000000");
        t.put("bankName", "测试银行");
        t.put("bankAccount", "6200000000");
        t.put("terms", "1. 含运输；2. 质保一年");
        t.put("active", true);
        return t;
    }

    @Test
    void issuedQuoteKeepsTemplateSnapshotAfterTemplateChanges() throws Exception {
        String templateId = postJson("/api/quotes/templates", template("测试模板" + UUID.randomUUID(), "甲公司"))
            .path("id")
            .asText();
        var input = new HashMap<>(quoteInput(project(), product(), "100"));
        input.put("templateId", templateId);
        String quote = postJson("/api/quotes", input).path("id").asText();
        assertThat(getJson("/api/quotes/" + quote).path("template_name").asText()).startsWith("测试模板");
        postJson("/api/quotes/" + quote + "/issue", Map.of());

        var changed = template("测试模板改" + UUID.randomUUID(), "乙公司");
        changed.put("terms", "新条款");
        mvc
            .perform(
                put("/api/quotes/templates/" + templateId)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(changed))
            )
            .andExpect(status().isOk());

        var copy = getJson("/api/quotes/" + quote + "/customer-copy");
        assertThat(copy.path("template").path("company_name").asText()).isEqualTo("甲公司");
        assertThat(copy.path("template").path("terms").asText()).contains("质保一年");
        assertThat(copy.path("template").path("bank_account").asText()).isEqualTo("6200000000");
        assertThat(copy.toString()).doesNotContain("unit_cost", "\"cost\"", "profit");
    }

    @Test
    void defaultTemplateRulesAndInactiveTemplateRejected() throws Exception {
        String templateId = postJson("/api/quotes/templates", template("停用模板" + UUID.randomUUID(), "丙公司"))
            .path("id")
            .asText();
        var inactive = template("停用模板" + UUID.randomUUID(), "丙公司");
        inactive.put("active", false);
        mvc
            .perform(
                put("/api/quotes/templates/" + templateId)
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(inactive))
            )
            .andExpect(status().isOk());
        var input = new HashMap<>(quoteInput(project(), product(), "100"));
        input.put("templateId", templateId);
        mvc
            .perform(
                post("/api/quotes")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(input))
            )
            .andExpect(status().isBadRequest());
        mvc
            .perform(post("/api/quotes/templates/" + templateId + "/default").with(csrf()))
            .andExpect(status().isBadRequest());

        // 未指定模板时使用默认模板；默认模板不能停用
        String quote = postJson("/api/quotes", quoteInput(project(), product(), "100")).path("id").asText();
        assertThat(getJson("/api/quotes/" + quote).path("template_name").asText()).isEqualTo("标准报价");
        var disableDefault = template("标准报价", "天商");
        disableDefault.put("active", false);
        mvc
            .perform(
                put("/api/quotes/templates/default-quote-template")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(disableDefault))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "sales1", roles = "SALES")
    void salesCannotEditTemplates() throws Exception {
        mvc
            .perform(
                post("/api/quotes/templates")
                    .with(csrf())
                    .contentType("application/json")
                    .content(mapper.writeValueAsString(template("越权模板", "丁公司")))
            )
            .andExpect(status().isForbidden());
    }

    private String[] contract() throws Exception {
        String customer = customer();
        var body = new HashMap<String, Object>();
        body.put("name", "附件门店" + UUID.randomUUID());
        body.put("brand", "小象");
        body.put("customerId", customer);
        body.put("contractNo", "HT-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("orderDate", LocalDate.now().toString());
        String project = postJson("/api/projects", body).path("id").asText();
        String quote = postJson("/api/quotes", quoteInput(project, product(), "100")).path("id").asText();
        postJson("/api/quotes/" + quote + "/issue", Map.of());
        return new String[] { customer, body.get("contractNo").toString() };
    }

    private org.springframework.test.web.servlet.ResultActions upload(String[] c, MockMultipartFile file)
        throws Exception {
        return mvc.perform(
            multipart("/api/contracts/attachments")
                .file(file)
                .param("customerId", c[0])
                .param("contractKey", c[1])
                .param("category", "CONTRACT")
                .param("note", "双方盖章")
                .with(csrf())
        );
    }

    @Test
    void attachmentUploadListDownloadAndSoftDelete() throws Exception {
        var c = contract();
        var file = new MockMultipartFile("file", "合同 扫描件.pdf", "application/pdf", PDF);
        String id = read(upload(c, file).andExpect(status().isOk()).andReturn()).path("id").asText();

        // 重复文件拒绝
        upload(c, file).andExpect(status().isBadRequest());

        var list = getJson("/api/contracts/attachments?customerId=" + c[0] + "&contractKey=" + c[1]);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("file_name").asText()).isEqualTo("合同 扫描件.pdf");
        assertThat(list.get(0).has("storage_path")).isFalse();
        boolean counted = false;
        for (var row : getJson("/api/contracts"))
            if (row.path("customer_id").asText().equals(c[0])) counted = row.path("attachments").asInt() == 1;
        assertThat(counted).isTrue();

        var res = mvc
            .perform(get("/api/contracts/attachments/" + id + "/file"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andReturn()
            .getResponse();
        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment").contains("UTF-8''");
        assertThat(res.getContentAsByteArray()).isEqualTo(PDF);

        mvc.perform(delete("/api/contracts/attachments/" + id).with(csrf())).andExpect(status().isOk());
        assertThat(getJson("/api/contracts/attachments?customerId=" + c[0] + "&contractKey=" + c[1])).isEmpty();
        mvc.perform(get("/api/contracts/attachments/" + id + "/file")).andExpect(status().isBadRequest());
    }

    @Test
    void attachmentRejectsDisguisedFilesAndUnknownContracts() throws Exception {
        var c = contract();
        upload(c, new MockMultipartFile("file", "virus.exe", "application/octet-stream", "MZ...".getBytes())).andExpect(
            status().isBadRequest()
        );
        upload(c, new MockMultipartFile("file", "fake.pdf", "application/pdf", "<html>".getBytes())).andExpect(
            status().isBadRequest()
        );
        upload(
            new String[] { c[0], "NOT-EXIST" },
            new MockMultipartFile("file", "a.pdf", "application/pdf", PDF)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void viewerCannotUploadAndOthersCannotDelete() throws Exception {
        var c = contract();
        String id = read(
            upload(c, new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                .andExpect(status().isOk())
                .andReturn()
        )
            .path("id")
            .asText();
        mvc
            .perform(
                multipart("/api/contracts/attachments")
                    .file(new MockMultipartFile("file", "b.pdf", "application/pdf", "%PDF-viewer".getBytes()))
                    .param("customerId", c[0])
                    .param("contractKey", c[1])
                    .param("category", "OTHER")
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
            )
            .andExpect(status().isForbidden());
        mvc
            .perform(delete("/api/contracts/attachments/" + id).with(csrf()).with(user("finance1").roles("FINANCE")))
            .andExpect(status().isForbidden());
    }
}
