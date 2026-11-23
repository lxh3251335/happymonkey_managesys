package cn.tianshang.commerce.excel;

import cn.tianshang.commerce.excel.ExcelService.Dataset;
import java.util.*;

/**
 * 完整模板的“填写示范”：每张表几行互相对应的示例资料（都以“示例”开头），从业务线、客户、产品一直到合同、开票、回款、成本，
 * 名称前后一致，整本导入能一次通过，照着改成自己的资料即可。
 */
final class SampleRows {

    static final String GROUP = "示例集团",
        LINE = "示例业务线",
        SCENARIO = "示例场景",
        CITY = "示例市",
        COMPANY = "示例签约主体有限公司",
        CUSTOMER = "示例客户有限公司",
        SUPPLIER = "示例供应商有限公司",
        BOX = "示例配电箱",
        BOX_SPEC = "500*700*250",
        CABINET = "示例控制柜",
        CABINET_SPEC = "800*2000*400",
        BUNDLE = "示例标准组合",
        TYPE = "示例项目类型",
        STORE = "示例门店（示例市第一店）",
        CONTRACT = "HT-2026-SL-001";
    /** 校验位正确的示例统一社会信用代码（仅作格式示范）。 */
    static final String TAX_NO = "91310000MA1FL7K3QJ";

    private SampleRows() {}

    static List<Map<String, Object>> of(Dataset d) {
        return switch (d) {
            case BUSINESS_LINES -> List.of(
                row(
                    "group",
                    GROUP,
                    "group_sort",
                    1,
                    "group_active",
                    "是",
                    "line",
                    LINE,
                    "short_name",
                    "示例线",
                    "sort",
                    1,
                    "active",
                    "是",
                    "note",
                    "示例：同一集团写多行，每行一条业务线"
                )
            );
            case SCENARIOS -> List.of(
                row("name", SCENARIO, "sort", 1, "active", "是", "note", "示例：产品分类，如 小象 / 快乐猴")
            );
            case CITIES -> List.of(
                row("name", CITY, "province", "示例省", "aliases", "示例区,示例县", "sort", 1, "active", "是")
            );
            case COMPANIES -> List.of(
                row(
                    "name",
                    COMPANY,
                    "short_name",
                    "示例主体",
                    "tax_no",
                    TAX_NO,
                    "legal_rep",
                    "王五",
                    "address",
                    "示例省示例市示例路 1 号",
                    "phone",
                    "021-00000000",
                    "bank_name",
                    "示例银行示例市支行",
                    "bank_account",
                    "1234 5678 9012 3456",
                    "is_default",
                    "是",
                    "active",
                    "是",
                    "sort",
                    1,
                    "note",
                    "示例：我方开票和签合同的公司"
                )
            );
            case CUSTOMERS -> List.of(
                row(
                    "name",
                    CUSTOMER,
                    "contact",
                    "张三",
                    "phone",
                    "13800000000",
                    "invoice_title",
                    CUSTOMER,
                    "tax_no",
                    TAX_NO,
                    "address",
                    "示例省示例市示例大道 100 号",
                    "invoice_phone",
                    "021-00000001",
                    "bank_name",
                    "示例银行示例市分行",
                    "bank_account",
                    "6222 0000 0000 0001",
                    "invoice_type",
                    "专票",
                    "note",
                    "示例客户"
                )
            );
            case SUPPLIERS -> List.of(
                row(
                    "name",
                    SUPPLIER,
                    "contact",
                    "李四",
                    "phone",
                    "13900000000",
                    "invoice_title",
                    SUPPLIER,
                    "tax_no",
                    TAX_NO,
                    "address",
                    "示例省示例市示例工业园 8 号",
                    "invoice_phone",
                    "0512-00000000",
                    "bank_name",
                    "示例银行示例市支行",
                    "bank_account",
                    "6222 0000 0000 0002",
                    "invoice_type",
                    "专票",
                    "note",
                    "示例供应商"
                )
            );
            case PRODUCTS -> List.of(
                row(
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "unit",
                    "台",
                    "supplier",
                    SUPPLIER,
                    "purchase_cost",
                    3200,
                    "mainstream",
                    "是"
                ),
                row(
                    "scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "unit",
                    "台",
                    "supplier",
                    SUPPLIER,
                    "purchase_cost",
                    12000,
                    "mainstream",
                    "否"
                )
            );
            case PRODUCT_COMPONENTS -> List.of(
                row(
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "supplier",
                    SUPPLIER,
                    "comp_name",
                    "断路器",
                    "comp_model",
                    "NXB-63 C32",
                    "brand",
                    "正泰",
                    "quantity",
                    3,
                    "unit",
                    "只"
                ),
                row(
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "supplier",
                    SUPPLIER,
                    "comp_name",
                    "交流接触器",
                    "comp_model",
                    "CJX2-1210",
                    "brand",
                    "正泰",
                    "quantity",
                    2,
                    "unit",
                    "只",
                    "comp_note",
                    "示例：同一产品写多行"
                )
            );
            case BUNDLES -> List.of(
                row(
                    "bundle",
                    BUNDLE,
                    "scenario",
                    SCENARIO,
                    "supplier",
                    SUPPLIER,
                    "active",
                    "是",
                    "bundle_note",
                    "示例：一套标准配置",
                    "product_scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "quantity",
                    2
                ),
                row(
                    "bundle",
                    BUNDLE,
                    "scenario",
                    SCENARIO,
                    "supplier",
                    SUPPLIER,
                    "active",
                    "是",
                    "product_scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "quantity",
                    1
                )
            );
            case PROJECT_TYPES -> List.of(
                row(
                    "type",
                    TYPE,
                    "brand",
                    LINE,
                    "active",
                    "是",
                    "type_note",
                    "示例：一种门店的标准配置",
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    2,
                    "unit_price",
                    4800
                ),
                row(
                    "type",
                    TYPE,
                    "brand",
                    LINE,
                    "active",
                    "是",
                    "scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    1,
                    "unit_price",
                    18000
                )
            );
            case SUPPLIER_TEMPLATES -> List.of(
                row(
                    "supplier",
                    SUPPLIER,
                    "type",
                    TYPE,
                    "active",
                    "是",
                    "template_note",
                    "示例：该供应商做这种门店的成本",
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "material_cost",
                    2800,
                    "labor_cost",
                    300,
                    "finance_cost",
                    100
                ),
                row(
                    "supplier",
                    SUPPLIER,
                    "type",
                    TYPE,
                    "active",
                    "是",
                    "scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "material_cost",
                    10500,
                    "labor_cost",
                    1200,
                    "finance_cost",
                    300
                )
            );
            case CUSTOMER_TEMPLATES -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "type",
                    TYPE,
                    "active",
                    "是",
                    "template_note",
                    "示例：与客户约定的协议价",
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    2,
                    "unit_price",
                    4600
                ),
                row(
                    "customer",
                    CUSTOMER,
                    "type",
                    TYPE,
                    "active",
                    "是",
                    "scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    1,
                    "unit_price",
                    17500
                )
            );
            case PROJECTS -> List.of(
                row(
                    "name",
                    STORE,
                    "customer",
                    CUSTOMER,
                    "brand",
                    LINE,
                    "city",
                    CITY,
                    "type",
                    TYPE,
                    "order_date",
                    "2026-03-01",
                    "contract_no",
                    CONTRACT,
                    "status",
                    "制作中",
                    "expected_ship_on",
                    "2026-03-15",
                    "consignee",
                    "赵六",
                    "consignee_phone",
                    "13700000000",
                    "ship_address",
                    "示例省示例市示例大道 100 号 1 层",
                    "note",
                    "示例门店"
                )
            );
            case QUOTE_LINES -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "store",
                    STORE,
                    "city",
                    CITY,
                    "type",
                    TYPE,
                    "contract_no",
                    CONTRACT,
                    "order_date",
                    "2026-03-01",
                    "valid_until",
                    java.time.LocalDate.now().plusDays(30).toString(),
                    "tax_basis",
                    "含税",
                    "tax_rate",
                    0.13,
                    "cost_supplier",
                    SUPPLIER,
                    "quote_note",
                    "示例报价",
                    "scenario",
                    SCENARIO,
                    "name",
                    BOX,
                    "specification",
                    BOX_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    2,
                    "unit_price",
                    4600,
                    "unit_cost",
                    3200
                ),
                row(
                    "customer",
                    CUSTOMER,
                    "store",
                    STORE,
                    "scenario",
                    SCENARIO,
                    "name",
                    CABINET,
                    "specification",
                    CABINET_SPEC,
                    "supplier",
                    SUPPLIER,
                    "quantity",
                    1,
                    "unit_price",
                    17500,
                    "unit_cost",
                    12000
                )
            );
            case CONTRACTS -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "contract_no",
                    CONTRACT,
                    "status",
                    "已签订",
                    "company",
                    COMPANY,
                    "title",
                    "示例门店设备购销合同",
                    "signed_on",
                    "2026-03-02",
                    "signed_amount",
                    26700,
                    "payment_terms",
                    "发货前付 50%，验收后付 50%",
                    "warranty_months",
                    12,
                    "archived",
                    "否",
                    "note",
                    "示例合同：签约金额 = 2×4600 + 17500"
                )
            );
            case CONTRACT_INVOICES -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "contract_no",
                    CONTRACT,
                    "invoice_no",
                    "00000001",
                    "kind",
                    "蓝字",
                    "invoice_type",
                    "专票",
                    "issued_on",
                    "2026-03-20",
                    "amount",
                    13350,
                    "tax_rate",
                    0.13,
                    "note",
                    "示例：首期 50%",
                    "company",
                    COMPANY
                )
            );
            case RECEIPTS -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "paid_on",
                    "2026-03-25",
                    "amount",
                    13350,
                    "reference",
                    "示例流水20260325001",
                    "payer",
                    CUSTOMER,
                    "contract_no",
                    CONTRACT,
                    "allocated",
                    13350,
                    "note",
                    "示例：一笔到账分到一份合同写一行"
                )
            );
            case COSTS -> List.of(
                row(
                    "customer",
                    CUSTOMER,
                    "contract_no",
                    CONTRACT,
                    "category",
                    "材料采购",
                    "supplier",
                    SUPPLIER,
                    "amount",
                    18400,
                    "incurred_on",
                    "2026-03-10",
                    "reference",
                    "示例采购单001",
                    "note",
                    "示例：2×3200 + 12000"
                )
            );
        };
    }

    private static Map<String, Object> row(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }
}
