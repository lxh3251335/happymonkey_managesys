package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

/** 按客户配置清单报价：读 Excel、对产品库；门店附件（清单、设计图）。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class ConfigListTest extends ApiTestSupport {

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

    /** 客户常见的清单：上面有标题和门店信息，表头在第 3 行，有合并单元格和合计行。 */
    private static byte[] workbook(Workbook wb) throws Exception {
        var sheet = wb.createSheet("配置清单");
        sheet.createRow(0).createCell(0).setCellValue("小象成都盛泰站 设备配置清单");
        sheet.createRow(1).createCell(0).setCellValue("门店地址：成都市武侯区某某路 1 号");
        String[] head = { "序号", "设备名称", "规格型号", "数量", "单位", "备注" };
        var h = sheet.createRow(2);
        for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
        Object[][] rows = {
            { 1, "物联网电控箱", "250×300×160", 2, "台", "" },
            { 2, "总配电箱", "650*850*250", "1台", "台", "含防雷" },
            { 3, "一控三配电箱", "550*700*250", 3, "台", "" },
            { 4, null, "550*700*300", 1, "台", "" },
            { null, null, null, null, null, null },
            { 5, "风幕机控制器", "定制", 1, "个", "需设计" },
            { null, "合计", "", 7, "", "" },
        };
        for (int r = 0; r < rows.length; r++) {
            var row = sheet.createRow(3 + r);
            for (int c = 0; c < rows[r].length; c++) {
                Object v = rows[r][c];
                if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                else if (v != null) row.createCell(c).setCellValue(v.toString());
            }
        }
        // 两种一控三配电箱的名称合并成一格，合并区域还多拖了一行空行（客户表格里常见）
        sheet.addMergedRegion(new CellRangeAddress(5, 7, 1, 1));
        var out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    private JsonNode read(String name, byte[] bytes) throws Exception {
        return read(
            mvc
                .perform(
                    multipart("/api/quotes/config-list")
                        .file(new MockMultipartFile("file", name, null, bytes))
                        .with(csrf())
                )
                .andExpect(status().isOk())
                .andReturn()
        );
    }

    @Test
    void readsCustomerListsAndMatchesTheProductLibrary() throws Exception {
        String iot = product("物联网电控箱", "250*300*160");
        String main = product("总配电箱", "650*850*250");
        product("一控三配电箱", "550*700*250");
        product("一控三配电箱", "550*700*300");

        for (var wb : List.<Workbook>of(new XSSFWorkbook(), new HSSFWorkbook())) {
            String file = wb instanceof XSSFWorkbook ? "清单.xlsx" : "清单.xls";
            var r = read(file, workbook(wb));
            assertThat(r.path("sheet").asText()).isEqualTo("配置清单");
            var rows = r.path("rows");
            // 空行、合计行不算；合并单元格里的名称两行都读到，合并区域拖出来的空行不算
            assertThat(rows.size()).as(file).isEqualTo(5);

            // 乘号写法不同（×）也算同一规格
            assertThat(rows.get(0).path("match").asText()).isEqualTo("EXACT");
            assertThat(rows.get(0).path("productId").asText()).isEqualTo(iot);
            assertThat(rows.get(0).path("quantity").asInt()).isEqualTo(2);
            // “1台”读成 1
            assertThat(rows.get(1).path("productId").asText()).isEqualTo(main);
            assertThat(rows.get(1).path("quantity").asInt()).isEqualTo(1);
            assertThat(rows.get(1).path("note").asText()).isEqualTo("含防雷");
            assertThat(rows.get(1).path("row").asInt()).isEqualTo(5);
            // 合并格：两行同名、规格不同，各自对上不同的产品
            assertThat(rows.get(2).path("match").asText()).isEqualTo("EXACT");
            assertThat(rows.get(3).path("name").asText()).isEqualTo("一控三配电箱");
            assertThat(rows.get(3).path("specification").asText()).isEqualTo("550*700*300");
            assertThat(rows.get(3).path("match").asText()).isEqualTo("EXACT");
            assertThat(rows.get(3).path("productId").asText()).isNotEqualTo(rows.get(2).path("productId").asText());
            // 库里没有的：不猜，没有候选
            assertThat(rows.get(4).path("name").asText()).isEqualTo("风幕机控制器");
            assertThat(rows.get(4).path("match").asText()).isEqualTo("NONE");
            assertThat(rows.get(4).path("productId").isNull()).isTrue();
        }
    }

    @Test
    void unreadableFilesAreExplained() throws Exception {
        var wb = new XSSFWorkbook();
        wb.createSheet("x").createRow(0).createCell(0).setCellValue("随便写点什么");
        var out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        mvc
            .perform(
                multipart("/api/quotes/config-list")
                    .file(new MockMultipartFile("file", "a.xlsx", null, out.toByteArray()))
                    .with(csrf())
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("名称")));
        mvc
            .perform(
                multipart("/api/quotes/config-list")
                    .file(new MockMultipartFile("file", "a.pdf", null, new byte[] { 1, 2 }))
                    .with(csrf())
            )
            .andExpect(status().isBadRequest());
        // 只读账号不能用
        mvc
            .perform(
                multipart("/api/quotes/config-list")
                    .file(new MockMultipartFile("file", "a.xlsx", null, out.toByteArray()))
                    .with(csrf())
                    .with(user("viewer1").roles("VIEWER"))
            )
            .andExpect(status().isForbidden());
    }

    @Test
    void storeKeepsTheConfigListAndDesignDrawings() throws Exception {
        String project = project();
        var file = new MockMultipartFile("file", "客户清单.xlsx", null, workbook(new XSSFWorkbook()));
        mvc
            .perform(
                multipart("/api/projects/" + project + "/attachments")
                    .file(file)
                    .param("category", "CONFIG_LIST")
                    .with(csrf())
            )
            .andExpect(status().isOk());
        // 同一个文件不重复存
        mvc
            .perform(
                multipart("/api/projects/" + project + "/attachments")
                    .file(file)
                    .param("category", "CONFIG_LIST")
                    .with(csrf())
            )
            .andExpect(status().isBadRequest());
        var design = new MockMultipartFile("file", "设计图.pdf", null, "%PDF-1.4 test".getBytes());
        mvc
            .perform(
                multipart("/api/projects/" + project + "/attachments")
                    .file(design)
                    .param("category", "DESIGN")
                    .with(csrf())
            )
            .andExpect(status().isOk());
        var list = getJson("/api/projects/" + project + "/attachments");
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.toString()).contains("CONFIG_LIST").contains("DESIGN").contains("客户清单.xlsx");

        String id = list.get(0).path("id").asText();
        mvc.perform(get("/api/project-attachments/" + id + "/file")).andExpect(status().isOk());
        // 业务员看不到别人负责的门店的附件，也不能删
        mvc
            .perform(get("/api/projects/" + project + "/attachments").with(user("sales1").roles("SALES")))
            .andExpect(status().isForbidden());
        mvc
            .perform(delete("/api/project-attachments/" + id).with(csrf()).with(user("sales1").roles("SALES")))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/project-attachments/" + id).with(csrf())).andExpect(status().isOk());
        assertThat(getJson("/api/projects/" + project + "/attachments").size()).isEqualTo(1);
    }
}
