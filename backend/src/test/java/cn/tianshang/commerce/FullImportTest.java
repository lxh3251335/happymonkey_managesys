package cn.tianshang.commerce;

import static cn.tianshang.commerce.ExcelImportExportTest.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

/** 第二批 Excel 导入：字典、电气件、组合、客户模板、带组合的项目类型、门店报价明细、回款、成本，以及整本导入。 */
@WithMockUser(username = "admin", roles = "ADMIN")
class FullImportTest extends ApiTestSupport {

    private JsonNode excel(String dataset, String action, byte[] body) throws Exception {
        return read(
            mvc
                .perform(
                    multipart("/api/excel/" + dataset + "/" + action)
                        .file(new MockMultipartFile("file", "data.xlsx", "application/octet-stream", body))
                        .with(csrf())
                )
                .andExpect(status().isOk())
                .andReturn()
        );
    }

    private JsonNode workbook(String action, byte[] body, org.springframework.test.web.servlet.ResultMatcher expected)
        throws Exception {
        return read(
            mvc
                .perform(
                    multipart("/api/excel/workbook/" + action)
                        .file(new MockMultipartFile("file", "all.xlsx", "application/octet-stream", body))
                        .with(csrf())
                )
                .andExpect(expected)
                .andReturn()
        );
    }

    /** 多张表的工作簿：表名 → (表头, 数据行)。 */
    static byte[] sheets(LinkedHashMap<String, List<List<Object>>> data) throws IOException {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            for (var e : data.entrySet()) {
                var s = wb.createSheet(e.getKey());
                int r = 0;
                for (var row : e.getValue()) {
                    var x = s.createRow(r++);
                    for (int c = 0; c < row.size(); c++) {
                        Object v = row.get(c);
                        if (v instanceof Number n) x.createCell(c).setCellValue(n.doubleValue());
                        else x.createCell(c).setCellValue(Objects.toString(v, ""));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void dictionariesImportByName() throws Exception {
        String group = "集团" + uid(),
            line = "业务线" + uid();
        byte[] lines = xlsx(
            List.of("客户集团", "业务线", "简称", "排序", "启用", "备注"),
            List.of(
                List.<Object>of(group, line, "简" + uid(), 2, "是", "测试"),
                List.<Object>of(group, line + "B", "", 3, "否", ""),
                List.<Object>of("只有集团" + uid(), "", "", "", "", "")
            )
        );
        var r = excel("business-lines", "import", lines);
        // 2 个集团 + 2 条业务线
        assertThat(r.path("created").asInt()).isEqualTo(4);
        assertThat(
            count(
                "SELECT COUNT(*) FROM business_line WHERE group_id=(SELECT id FROM business_group WHERE name=?)",
                group
            )
        ).isEqualTo(2);
        assertThat(excel("business-lines", "preview", lines).path("unchanged").asInt()).isEqualTo(4);

        String scenario = "场景" + uid();
        assertThat(
            excel("scenarios", "import", xlsx(List.of("应用场景", "排序"), List.of(List.<Object>of(scenario, 9))))
                .path("created")
                .asInt()
        ).isEqualTo(1);
        var cities = xlsx(
            List.of("城市", "省份", "别名", "启用"),
            List.of(List.of("测试城" + uid(), "测试省", "别名甲、别名乙", "是"))
        );
        assertThat(excel("cities", "import", cities).path("created").asInt()).isEqualTo(1);
        assertThat(excel("cities", "preview", cities).path("unchanged").asInt()).isEqualTo(1);

        String company = "主体" + uid();
        var companies = xlsx(
            List.of("签约主体", "简称", "银行账号", "默认主体", "启用"),
            List.of(
                List.of(company, "简" + uid(), "6222 0000 1111", "否", "是"),
                List.of(company + "2", "", "", "否", "是")
            )
        );
        assertThat(excel("companies", "import", companies).path("created").asInt()).isEqualTo(2);
        var twoDefaults = xlsx(
            List.of("签约主体", "默认主体"),
            List.of(List.of("默认A" + uid(), "是"), List.of("默认B" + uid(), "是"))
        );
        assertThat(excel("companies", "preview", twoDefaults).path("errors").asInt()).isEqualTo(1);
        // 字典只有管理员能导入
        mvc
            .perform(
                multipart("/api/excel/scenarios/preview")
                    .file(new MockMultipartFile("file", "d.xlsx", "application/octet-stream", cities))
                    .with(csrf())
                    .with(user("sales1").roles("SALES"))
            )
            .andExpect(status().isForbidden());
    }

    @Test
    void masterDataWithComponentsBundlesTemplatesAndBundleRowsInTypes() throws Exception {
        String sup = "供应商" + uid();
        String scenario = "场景" + uid();
        excel("suppliers", "import", xlsx(List.of("名称"), List.of(List.of(sup))));
        jdbcTemplate.update("INSERT INTO scenario(id,name) VALUES(?,?)", UUID.randomUUID().toString(), scenario);
        var products = xlsx(
            List.of("应用场景", "产品名称", "规格", "供应商", "采购成本"),
            List.of(
                List.<Object>of(scenario, "电控箱", "A", sup, 1200),
                List.<Object>of(scenario, "配电箱", "B", sup, 2800)
            )
        );
        assertThat(excel("products", "import", products).path("created").asInt()).isEqualTo(2);
        assertThat(
            jdbcTemplate.queryForObject(
                "SELECT purchase_cost FROM product WHERE scenario=? AND name='电控箱'",
                java.math.BigDecimal.class,
                scenario
            )
        ).isEqualByComparingTo("1200");

        var components = xlsx(
            List.of("产品应用场景", "产品名称", "规格", "电气件名称", "电气件型号", "品牌", "数量", "单位"),
            List.of(
                List.<Object>of(scenario, "电控箱", "A", "断路器", "C65", "施耐德", 2, "个"),
                List.<Object>of(scenario, "电控箱", "A", "接触器", "LC1D", "施耐德", 1, "个")
            )
        );
        assertThat(excel("product-components", "import", components).path("created").asInt()).isEqualTo(1);
        assertThat(excel("product-components", "preview", components).path("unchanged").asInt()).isEqualTo(1);
        String productId = jdbcTemplate.queryForObject(
            "SELECT id FROM product WHERE scenario=? AND name='电控箱'",
            String.class,
            scenario
        );
        assertThat(count("SELECT COUNT(*) FROM product_component WHERE product_id=?", productId)).isEqualTo(2);

        String bundle = "组合" + uid();
        var bundles = xlsx(
            List.of("组合名称", "应用场景", "供应商", "产品名称", "规格", "每套数量"),
            List.of(
                List.<Object>of(bundle, scenario, sup, "电控箱", "A", 1),
                List.<Object>of(bundle, scenario, sup, "配电箱", "B", 2)
            )
        );
        assertThat(excel("bundles", "import", bundles).path("created").asInt()).isEqualTo(1);
        assertThat(excel("bundles", "preview", bundles).path("unchanged").asInt()).isEqualTo(1);

        // 项目类型：一行组合（2 套）+ 一行单品；组合展开为 2 个产品，数量 = 每套数量 × 套数
        String type = "类型" + uid();
        var types = xlsx(
            List.of("项目类型", "业务线", "组合名称", "套数", "产品应用场景", "产品名称", "规格", "数量", "标准售价"),
            List.of(
                List.<Object>of(type, "小象", bundle, 2, "", "", "", "", ""),
                List.<Object>of(type, "小象", "", "", scenario, "电控箱", "A", 1, 5500)
            )
        );
        var t = excel("project-types", "import", types);
        assertThat(t.path("created").asInt()).isEqualTo(1);
        assertThat(t.path("results").get(0).path("label").asText()).contains("3 项配置");
        String typeId = jdbcTemplate.queryForObject("SELECT id FROM project_type WHERE name=?", String.class, type);
        var items = jdbcTemplate.queryForList(
            "SELECT * FROM project_type_item WHERE project_type_id=? ORDER BY position",
            typeId
        );
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("bundle_group")).isNotNull();
        assertThat(new java.math.BigDecimal(items.get(1).get("quantity").toString())).isEqualByComparingTo("4");
        assertThat(items.get(2).get("bundle_group")).isNull();
        assertThat(excel("project-types", "preview", types).path("unchanged").asInt()).isEqualTo(1);
        // 导出的项目类型带组合名称和套数，能原样导回
        byte[] exported = mvc
            .perform(get("/api/excel/project-types"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        assertThat(excel("project-types", "preview", exported).path("errors").asInt()).isZero();

        String customer = "客户" + uid();
        excel("customers", "import", xlsx(List.of("名称"), List.of(List.of(customer))));
        var templates = xlsx(
            List.of("客户", "项目类型", "产品应用场景", "产品名称", "规格", "数量", "协议售价"),
            List.of(List.<Object>of(customer, type, scenario, "配电箱", "B", 2, 3000))
        );
        assertThat(excel("customer-templates", "import", templates).path("created").asInt()).isEqualTo(1);
        assertThat(excel("customer-templates", "preview", templates).path("unchanged").asInt()).isEqualTo(1);
        var missing = xlsx(
            List.of("客户", "项目类型", "产品应用场景", "产品名称", "规格", "数量"),
            List.of(List.<Object>of(customer, type, scenario, "不存在", "", 1))
        );
        assertThat(
            excel("customer-templates", "preview", missing).path("results").get(0).path("messages").get(0).asText()
        ).contains("找不到");
    }

    @Test
    void quoteLinesThenReceiptsAndCosts() throws Exception {
        String sup = "供应商" + uid(),
            scenario = "场景" + uid(),
            customer = "客户" + uid(),
            type = "类型" + uid(),
            bundle = "组合" + uid(),
            store = "门店" + uid(),
            contractNo = "HT-" + uid();
        excel("suppliers", "import", xlsx(List.of("名称"), List.of(List.of(sup))));
        excel("customers", "import", xlsx(List.of("名称"), List.of(List.of(customer))));
        jdbcTemplate.update("INSERT INTO scenario(id,name) VALUES(?,?)", UUID.randomUUID().toString(), scenario);
        excel(
            "products",
            "import",
            xlsx(
                List.of("应用场景", "产品名称", "规格", "供应商"),
                List.of(
                    List.of(scenario, "电控箱", "A", sup),
                    List.of(scenario, "配电箱", "B", sup),
                    List.of(scenario, "软件", "", sup)
                )
            )
        );
        excel(
            "bundles",
            "import",
            xlsx(
                List.of("组合名称", "应用场景", "供应商", "产品名称", "规格", "每套数量"),
                List.of(
                    List.<Object>of(bundle, scenario, sup, "电控箱", "A", 1),
                    List.<Object>of(bundle, scenario, sup, "配电箱", "B", 2)
                )
            )
        );
        // 项目类型给出标准售价：组合展开行按它取价
        excel(
            "project-types",
            "import",
            xlsx(
                List.of("项目类型", "业务线", "产品应用场景", "产品名称", "规格", "数量", "标准售价"),
                List.of(
                    List.<Object>of(type, "小象", scenario, "电控箱", "A", 1, 5000),
                    List.<Object>of(type, "小象", scenario, "配电箱", "B", 2, 3000)
                )
            )
        );
        var lines = xlsx(
            List.of(
                "客户",
                "门店名称",
                "项目类型",
                "合同号",
                "组合名称",
                "套数",
                "产品应用场景",
                "产品名称",
                "规格",
                "数量",
                "单价"
            ),
            List.of(
                List.<Object>of(customer, store, type, contractNo, bundle, 2, "", "", "", "", ""),
                List.<Object>of(customer, store, type, contractNo, "", "", scenario, "软件", "", 1, 8000)
            )
        );
        var preview = excel("quote-lines", "preview", lines);
        assertThat(preview.path("errors").asInt()).isZero();
        assertThat(preview.path("results").get(0).path("label").asText()).contains("3 行").contains("30000.00 元");
        assertThat(excel("quote-lines", "import", lines).path("created").asInt()).isEqualTo(1);
        String projectId = jdbcTemplate.queryForObject("SELECT id FROM project WHERE name=?", String.class, store);
        var quote = jdbcTemplate.queryForMap("SELECT * FROM quotation WHERE project_id=?", projectId);
        // 门店有合同号：直接出具
        assertThat(quote.get("status")).isEqualTo("ISSUED");
        assertThat(
            count(
                "SELECT COUNT(*) FROM quotation_item WHERE quotation_id=? AND bundle_group IS NOT NULL",
                quote.get("id")
            )
        ).isEqualTo(2);
        // 同一份文件再导入：明细相同，跳过
        assertThat(excel("quote-lines", "preview", lines).path("unchanged").asInt()).isEqualTo(1);
        // 没有标准售价的组合产品：报错而不是默默填 0
        var noPrice = xlsx(
            List.of("客户", "门店名称", "项目类型", "组合名称", "产品名称"),
            List.of(List.<Object>of(customer, "另一店" + uid(), "不存在的类型", bundle, ""))
        );
        assertThat(excel("quote-lines", "preview", noPrice).path("errors").asInt()).isEqualTo(1);

        // 回款：同一流水号两行 = 一笔到账分到一份合同，余额待分配
        String today = LocalDate.now().toString();
        String ref = "LS" + uid();
        var receipts = xlsx(
            List.of("客户", "到账日期", "到账金额", "流水号", "付款方", "合同号", "分到该合同金额", "备注"),
            List.of(
                List.<Object>of(customer, today, 20000, ref, "付款单位", contractNo, 15000, ""),
                List.<Object>of(customer, today, 20000, ref, "付款单位", "", "", "")
            )
        );
        var rp = excel("receipts", "preview", receipts);
        assertThat(rp.path("errors").asInt()).isZero();
        assertThat(rp.path("results").get(0).path("messages").get(0).asText()).contains("未分配 5000");
        assertThat(excel("receipts", "import", receipts).path("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM receipt_batch WHERE reference=?", ref)).isEqualTo(1);
        assertThat(excel("receipts", "preview", receipts).path("unchanged").asInt()).isEqualTo(1);
        var unknownContract = xlsx(
            List.of("客户", "到账日期", "到账金额", "流水号", "合同号", "分到该合同金额"),
            List.of(List.<Object>of(customer, today, 100, "X" + uid(), "没有的合同", 100))
        );
        assertThat(excel("receipts", "preview", unknownContract).path("errors").asInt()).isEqualTo(1);

        var costs = xlsx(
            List.of("客户", "合同号", "成本类别", "供应商", "金额", "发生日期", "单据号"),
            List.of(
                List.<Object>of(customer, contractNo, "材料采购", sup, 9000, today, "FP1"),
                List.<Object>of(customer, contractNo, "运输物流", "", 500, today, "")
            )
        );
        assertThat(excel("costs", "import", costs).path("created").asInt()).isEqualTo(2);
        assertThat(excel("costs", "preview", costs).path("unchanged").asInt()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM contract_cost WHERE contract_no=?", contractNo)).isEqualTo(2);
    }

    @Test
    void workbookPreviewRollsBackAndImportWritesInOrder() throws Exception {
        String sup = "供应商" + uid(),
            scenario = "场景" + uid();
        jdbcTemplate.update("INSERT INTO scenario(id,name) VALUES(?,?)", UUID.randomUUID().toString(), scenario);
        var data = new LinkedHashMap<String, List<List<Object>>>();
        data.put("填写说明", List.of(List.<Object>of("说明")));
        // 产品表在前、供应商表在后：整本导入按资料顺序处理，所以产品能找到同一本里新建的供应商
        data.put(
            "标准产品库",
            List.of(
                List.<Object>of("应用场景", "产品名称", "规格", "供应商"),
                List.<Object>of(scenario, "电控箱", "A", sup)
            )
        );
        data.put("供应商资料", List.of(List.<Object>of("名称"), List.<Object>of(sup)));
        data.put("城市", List.of(List.<Object>of("城市")));
        data.put("随便一张表", List.of(List.<Object>of("x"), List.<Object>of("y")));
        byte[] file = sheets(data);

        var preview = workbook("preview", file, status().isOk());
        assertThat(preview.path("errors").asInt()).isZero();
        assertThat(preview.path("created").asInt()).isEqualTo(2);
        var statuses = new ArrayList<String>();
        for (var s : preview.path("sheets")) statuses.add(s.path("title").asText() + ":" + s.path("status").asText());
        assertThat(statuses).containsExactly("供应商资料:OK", "城市:EMPTY", "标准产品库:OK", "随便一张表:IGNORED");
        // 预览只标记回滚（测试事务里仍看得到数据；正式请求里事务结束即回滚）
        assertThat(org.springframework.test.context.transaction.TestTransaction.isFlaggedForRollback()).isTrue();

        // 测试事务里预览写入的数据还在，所以正式导入换一本（另一家供应商）
        String sup2 = "供应商" + uid();
        var data2 = new LinkedHashMap<String, List<List<Object>>>();
        data2.put("供应商资料", List.of(List.<Object>of("名称"), List.<Object>of(sup2)));
        data2.put(
            "标准产品库",
            List.of(
                List.<Object>of("应用场景", "产品名称", "规格", "供应商"),
                List.<Object>of(scenario, "电控箱", "A", sup2)
            )
        );
        var imported = workbook("import", sheets(data2), status().isOk());
        assertThat(imported.path("created").asInt()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM partner WHERE name=?", sup2)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM product WHERE scenario=? AND name='电控箱'", scenario)).isEqualTo(2);

        // 有错误的表挡住后面的表，整本不写入
        var bad = new LinkedHashMap<String, List<List<Object>>>();
        bad.put("供应商资料", List.of(List.<Object>of("名称"), List.<Object>of("重复厂"), List.<Object>of("重复厂")));
        bad.put(
            "标准产品库",
            List.of(List.<Object>of("应用场景", "产品名称", "供应商"), List.<Object>of(scenario, "新品", sup))
        );
        var r = workbook("preview", sheets(bad), status().isOk());
        assertThat(r.path("sheets").get(0).path("status").asText()).isEqualTo("ERROR");
        assertThat(r.path("sheets").get(1).path("status").asText()).isEqualTo("SKIPPED");
        workbook("import", sheets(bad), status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM product WHERE scenario=? AND name='新品'", scenario)).isZero();

        // 完整模板：说明表在最前，之后每种资料一张表
        byte[] template = mvc
            .perform(get("/api/excel/workbook/template"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheetName(0)).isEqualTo("填写说明");
            assertThat(wb.getNumberOfSheets()).isEqualTo(getJson("/api/excel/workbook/datasets").size() + 1);
            assertThat(wb.getSheet("门店报价明细").getRow(0).getCell(0).getStringCellValue()).isEqualTo("客户*");
        }
        // 整本导入只有管理员能用
        mvc
            .perform(
                multipart("/api/excel/workbook/preview")
                    .file(new MockMultipartFile("file", "all.xlsx", "application/octet-stream", file))
                    .with(csrf())
                    .with(user("finance1").roles("FINANCE"))
            )
            .andExpect(status().isForbidden());
    }
}
