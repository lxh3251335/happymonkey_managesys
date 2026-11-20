package cn.tianshang.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.ResultActions;

@WithMockUser(username = "admin", roles = "ADMIN")
class ExcelImportExportTest extends ApiTestSupport {

    static final String TAX = "91310000MA1FL7K3QJ";

    /** 用字符串单元格生成 xlsx：第一行表头。 */
    static byte[] xlsx(List<String> header, List<List<Object>> rows) throws IOException {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var s = wb.createSheet("数据");
            var h = s.createRow(0);
            for (int i = 0; i < header.size(); i++) h.createCell(i).setCellValue(header.get(i));
            for (int r = 0; r < rows.size(); r++) {
                var row = s.createRow(r + 1);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    Object v = rows.get(r).get(c);
                    if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
                    else row.createCell(c).setCellValue(Objects.toString(v, ""));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ResultActions send(String dataset, String action, byte[] body) throws Exception {
        return mvc.perform(
            multipart("/api/excel/" + dataset + "/" + action)
                .file(new MockMultipartFile("file", "data.xlsx", "application/octet-stream", body))
                .with(csrf())
        );
    }

    private JsonNode ok(String dataset, String action, byte[] body) throws Exception {
        return read(send(dataset, action, body).andExpect(status().isOk()).andReturn());
    }

    private Workbook exported(String dataset) throws Exception {
        byte[] bytes = mvc
            .perform(get("/api/excel/" + dataset))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("UTF-8''")))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    private static int col(Sheet s, String header) {
        for (var c : s.getRow(0)) if (c.getStringCellValue().replace("*", "").equals(header)) return c.getColumnIndex();
        throw new AssertionError("no column " + header);
    }

    @Test
    void customersRoundTripUpdatesByIdAndAddsNewRows() throws Exception {
        String name = "导出客户" + UUID.randomUUID();
        var body = new HashMap<String, Object>(
            Map.of("name", name, "kind", "CUSTOMER", "taxNo", TAX, "bankAccount", "6222000011112222")
        );
        String id = postJson("/api/partners", body).path("id").asText();

        // 导出：税号、账号为文本单元格，含系统编号
        var wb = exported("customers");
        var s = wb.getSheetAt(0);
        assertThat(wb.getSheet("填写说明")).isNotNull();
        int idCol = col(s, "系统编号"),
            nameCol = col(s, "名称"),
            taxCol = col(s, "纳税人识别号"),
            addrCol = col(s, "注册地址");
        Row mine = null;
        for (var r : s) if (r.getRowNum() > 0 && r.getCell(idCol).getStringCellValue().equals(id)) mine = r;
        assertThat(mine).isNotNull();
        assertThat(mine.getCell(taxCol).getCellType()).isEqualTo(CellType.STRING);
        assertThat(mine.getCell(taxCol).getStringCellValue()).isEqualTo(TAX);

        // 修改地址、追加一个新客户
        mine.createCell(addrCol).setCellValue("上海市导入路 1 号");
        var added = s.createRow(s.getLastRowNum() + 1);
        added.createCell(nameCol).setCellValue("新导入客户" + UUID.randomUUID());
        var out = new ByteArrayOutputStream();
        wb.write(out);

        var preview = ok("customers", "preview", out.toByteArray());
        assertThat(preview.path("created").asInt()).isEqualTo(1);
        assertThat(preview.path("updated").asInt()).isEqualTo(1);
        assertThat(preview.path("errors").asInt()).isZero();
        // 预览不写库
        assertThat(jdbcTemplate.queryForObject("SELECT address FROM partner WHERE id=?", String.class, id)).isNull();

        var result = ok("customers", "import", out.toByteArray());
        assertThat(result.path("updated").asInt()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT address FROM partner WHERE id=?", String.class, id)).isEqualTo(
            "上海市导入路 1 号"
        );
        assertThat(jdbcTemplate.queryForObject("SELECT tax_no FROM partner WHERE id=?", String.class, id)).isEqualTo(
            TAX
        );
        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='EXCEL_IMPORT'", Integer.class)
        ).isEqualTo(1);

        // 再导入同一文件：全部无变化
        var again = ok("customers", "preview", out.toByteArray());
        assertThat(again.path("updated").asInt()).isZero();
    }

    @Test
    void errorsBlockTheWholeBatch() throws Exception {
        String good = "批量客户A" + UUID.randomUUID();
        byte[] file = xlsx(
            List.of("名称*", "纳税人识别号", "发票类型"),
            List.of(
                List.of(good, "", "专票"),
                List.of("批量客户B" + UUID.randomUUID(), "91310000MA1FL7K3QX", "普票"),
                List.of(good, "", "电子票")
            )
        );
        var preview = ok("customers", "preview", file);
        assertThat(preview.path("errors").asInt()).isEqualTo(2);
        assertThat(preview.toString()).contains("校验位", "重复", "发票类型");
        send("customers", "import", file).andExpect(status().isBadRequest());
        assertThat(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM partner WHERE name=?", Integer.class, good)
        ).isZero();

        // 缺必填列
        send("customers", "preview", xlsx(List.of("联系人"), List.of(List.of("张三")))).andExpect(
            status().isBadRequest()
        );
        // 超长数字被 Excel 存成数值
        send(
            "customers",
            "preview",
            xlsx(List.of("名称", "银行账号"), List.of(List.of("数字客户", 6222000011112222333d)))
        ).andExpect(status().isBadRequest());
    }

    @Test
    void productsTypesAndSupplierTemplatesImportInOrder() throws Exception {
        String sup = "导入供应商" + UUID.randomUUID();
        ok("suppliers", "import", xlsx(List.of("名称"), List.of(List.of(sup))));
        String scenario = "导入场景" + UUID.randomUUID().toString().substring(0, 6);
        jdbcTemplate.update("INSERT INTO scenario(id,name) VALUES(?,?)", UUID.randomUUID().toString(), scenario);

        byte[] products = xlsx(
            List.of("应用场景", "产品名称", "规格", "型号", "供应商", "主流配置"),
            List.of(
                List.of(scenario, "物联网电控箱", "250*300*160", "mini", sup, "是"),
                List.of(scenario, "总配电箱", "650*850*250", "", sup, "否"),
                List.of(scenario, "平台及触摸屏软件", "", "", "", "否") // 软件类无规格
            )
        );
        assertThat(ok("products", "import", products).path("created").asInt()).isEqualTo(3);
        assertThat(ok("products", "preview", products).path("unchanged").asInt()).isEqualTo(3);
        var badSupplier = xlsx(
            List.of("应用场景", "产品名称", "规格", "供应商"),
            List.of(List.of(scenario, "X", "Y", "不存在的厂"))
        );
        assertThat(ok("products", "preview", badSupplier).path("errors").asInt()).isEqualTo(1);

        String type = "导入类型" + UUID.randomUUID().toString().substring(0, 6);
        byte[] types = xlsx(
            List.of("项目类型", "品牌", "产品应用场景", "产品名称", "规格", "型号", "数量", "标准售价"),
            List.of(
                List.<Object>of(type, "小象", scenario, "物联网电控箱", "250*300*160", "mini", 1, 5500),
                List.<Object>of(type, "小象", scenario, "总配电箱", "650*850*250", "", 2, "3,438")
            )
        );
        var t = ok("project-types", "import", types);
        assertThat(t.path("created").asInt()).isEqualTo(1);
        assertThat(t.path("results").get(0).path("rows").asText()).isEqualTo("第 2–3 行");

        byte[] costs = xlsx(
            List.of(
                "供应商",
                "项目类型",
                "产品应用场景",
                "产品名称",
                "规格",
                "型号",
                "材料成本",
                "团队人工",
                "财务成本"
            ),
            List.of(
                List.<Object>of(sup, type, scenario, "物联网电控箱", "250*300*160", "mini", 1200, 200, 100),
                List.<Object>of(sup, type, scenario, "总配电箱", "650*850*250", "", 2800, 100, 50)
            )
        );
        assertThat(ok("supplier-templates", "import", costs).path("created").asInt()).isEqualTo(1);
        assertThat(ok("supplier-templates", "preview", costs).path("unchanged").asInt()).isEqualTo(1);

        String typeId = jdbcTemplate.queryForObject("SELECT id FROM project_type WHERE name=?", String.class, type);
        String supId = jdbcTemplate.queryForObject("SELECT id FROM partner WHERE name=?", String.class, sup);
        var pre = getJson("/api/project-types/" + typeId + "/prefill?supplierId=" + supId).path("items");
        assertThat(pre.get(1).path("unit_price").decimalValue()).isEqualByComparingTo("3438");
        assertThat(pre.get(1).path("unit_cost").decimalValue()).isEqualByComparingTo("2950");

        // 导出的成本模板包含这两行，单台成本已计算
        var wb = exported("supplier-templates");
        var s = wb.getSheetAt(0);
        int typeCol = col(s, "项目类型"),
            unitCol = col(s, "单台成本");
        var units = new ArrayList<Double>();
        for (var r : s)
            if (r.getRowNum() > 0 && r.getCell(typeCol).getStringCellValue().equals(type)) units.add(
                r.getCell(unitCol).getNumericCellValue()
            );
        assertThat(units).containsExactlyInAnyOrder(1500d, 2950d);
        // 上面导入用的是旧表头“团队人工”（旧文件仍可导入）；导出用统一后的名称“人工成本”
        assertThat(col(s, "人工成本")).isPositive();
    }

    @Test
    void importPermissionsFollowRoles() throws Exception {
        byte[] file = xlsx(List.of("名称"), List.of(List.of("权限客户")));
        mvc
            .perform(
                multipart("/api/excel/customers/preview")
                    .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", file))
                    .with(csrf())
                    .with(user("reader").roles("VIEWER"))
            )
            .andExpect(status().isForbidden());
        mvc
            .perform(
                multipart("/api/excel/supplier-templates/import")
                    .file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", file))
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
            )
            .andExpect(status().isForbidden());
        // 只读角色可以导出
        mvc.perform(get("/api/excel/products").with(user("reader").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/excel/customers").param("template", "true")).andExpect(status().isOk());
        mvc
            .perform(
                multipart("/api/excel/customers/preview")
                    .file(new MockMultipartFile("file", "a.xls", "application/octet-stream", file))
                    .with(csrf())
            )
            .andExpect(status().isBadRequest());
    }
}
