<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import ProductDrawings from '../components/ProductDrawings.vue'
import ProductComponents from '../components/ProductComponents.vue'
import BundleManager from '../components/BundleManager.vue'
import ExcelTools from '../components/ExcelTools.vue'
import { api, post, put, canWrite, canSeeCost } from '../api'
import { useUrlState } from '../urlState'
import { dict, loadDictionaries } from '../dictionaries'
import type { Partner, Product } from '../types'
import { money, missingInvoiceFields, invoiceText, invoiceTypeName } from '../utils'
const partners = ref<Partner[]>([]),
  products = ref<Product[]>([]),
  tab = ref('CUSTOMER'),
  keyword = ref(''),
  dialog = ref(false),
  busy = ref(false),
  error = ref(''),
  page = ref(1),
  editingId = ref('')
const freshPartner = () => ({
  name: '',
  kind: tab.value,
  contact: '',
  phone: '',
  note: '',
  invoiceTitle: '',
  taxNo: '',
  address: '',
  invoicePhone: '',
  bankName: '',
  bankAccount: '',
  invoiceType: '',
  contractCode: '',
})
const partner = ref(freshPartner())
/** 标签页和搜索词写进地址：数据检查、顶栏搜索可以直接打开某条资料。 */
const urlState = useUrlState()
urlState('tab', tab, 'CUSTOMER')
urlState('q', keyword, '')
const freshProduct = () => ({
  scenario: scenario.value || '',
  name: '',
  specification: '',
  model: '',
  unit: '台',
  supplierId: '',
  mainstream: false,
  purchaseCost: undefined as number | undefined,
})
const scenario = ref(''),
  drawingsOpen = ref(false),
  drawingProduct = ref<Product>(),
  componentsOpen = ref(false),
  bundleManager = ref<InstanceType<typeof BundleManager>>()
const product = ref(freshProduct())
/** 应用场景来自数据字典：筛选列出全部，新增/修改只能选启用的（当前值保留）。 */
const scenarios = computed(() => dict.scenarios.map((s) => s.name))
const selectableScenarios = computed(() =>
  dict.scenarios.filter((s) => s.active || s.name === product.value.scenario).map((s) => s.name),
)
const suppliers = computed(() => partners.value.filter((p) => p.kind === 'SUPPLIER'))
const filteredPartners = computed(() =>
  partners.value.filter(
    (p) =>
      p.kind === tab.value &&
      `${p.name}${p.contact || ''}${p.phone || ''}${p.tax_no || ''}${p.invoice_title || ''}`.includes(keyword.value),
  ),
)
const filteredProducts = computed(() =>
  products.value.filter(
    (p) =>
      (!scenario.value || p.scenario === scenario.value) &&
      `${p.scenario}${p.name}${p.specification}${p.model}${p.supplier_name || ''}`.includes(keyword.value),
  ),
)
async function copyInvoice(p: Partner) {
  try {
    await navigator.clipboard.writeText(invoiceText(p))
    ElMessage.success('开票信息已复制')
  } catch {
    ElMessage.warning('浏览器未允许复制，请手动选择文本')
  }
}
function openComponents(p: Product) {
  drawingProduct.value = p
  componentsOpen.value = true
}
function openDrawings(p: Product) {
  drawingProduct.value = p
  drawingsOpen.value = true
}
function resetFilters() {
  page.value = 1
  keyword.value = ''
  scenario.value = ''
}
async function load() {
  try {
    ;[partners.value, products.value] = await Promise.all([
      api<Partner[]>('/partners'),
      api<Product[]>('/products'),
      loadDictionaries(true),
    ]).then(([a, b]) => [a, b] as const)
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
function open() {
  editingId.value = ''
  partner.value = freshPartner()
  product.value = freshProduct()
  dialog.value = true
}
function edit(row: Partner | Product) {
  editingId.value = row.id
  if (tab.value === 'PRODUCT') {
    const p = row as Product
    product.value = {
      scenario: p.scenario,
      name: p.name,
      specification: p.specification,
      model: p.model || '',
      unit: p.unit,
      supplierId: p.supplier_id || '',
      mainstream: p.mainstream,
      purchaseCost: p.purchase_cost == null ? undefined : Number(p.purchase_cost),
    }
  } else {
    const p = row as Partner
    partner.value = {
      name: p.name,
      kind: p.kind,
      contact: p.contact || '',
      phone: p.phone || '',
      note: p.note || '',
      invoiceTitle: p.invoice_title || '',
      taxNo: p.tax_no || '',
      address: p.address || '',
      invoicePhone: p.invoice_phone || '',
      bankName: p.bank_name || '',
      bankAccount: p.bank_account || '',
      invoiceType: p.invoice_type || '',
      contractCode: p.contract_code || '',
    }
  }
  dialog.value = true
}
/** 当前标签对应的 Excel 数据集。 */
const excel = computed(
  () =>
    ({
      CUSTOMER: { dataset: 'customers', title: '客户资料', canImport: canWrite() },
      SUPPLIER: { dataset: 'suppliers', title: '供应商资料', canImport: canWrite() },
      PRODUCT: { dataset: 'products', title: '标准产品库', canImport: canWrite() },
      BUNDLE: { dataset: 'bundles', title: '标准组合', canImport: canWrite() },
    })[tab.value],
)
const bundleKey = ref(0)
async function afterImport() {
  bundleKey.value++
  await load()
}
const kindName = computed(() => (tab.value === 'PRODUCT' ? '标准产品' : tab.value === 'CUSTOMER' ? '客户' : '供应商'))
async function save() {
  if (
    tab.value === 'PRODUCT'
      ? !product.value.scenario.trim() || !product.value.name.trim() || !product.value.supplierId
      : !partner.value.name.trim()
  )
    return ElMessage.warning('请填写必填资料')
  busy.value = true
  try {
    const path = tab.value === 'PRODUCT' ? '/products' : '/partners'
    const body =
      tab.value === 'PRODUCT'
        ? { ...product.value, supplierId: product.value.supplierId, purchaseCost: product.value.purchaseCost ?? null }
        : partner.value
    if (editingId.value) await put(`${path}/${editingId.value}`, body)
    else await post(path, body)
    dialog.value = false
    await load()
    ElMessage.success('基础资料已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader title="基础资料">
      <ExcelTools
        v-if="excel"
        :key="excel.dataset"
        :dataset="excel.dataset"
        :title="excel.title"
        :can-import="excel.canImport"
        @imported="afterImport"
      />
      <el-button v-if="canWrite() && tab === 'BUNDLE'" type="primary" size="small" @click="bundleManager?.open()">
        <el-icon><Plus /></el-icon>
        新增标准组合
      </el-button>
      <el-button v-else-if="canWrite()" type="primary" size="small" @click="open">
        <el-icon><Plus /></el-icon>
        {{ tab === 'PRODUCT' ? '新增标准产品' : tab === 'CUSTOMER' ? '新增客户' : '新增供应商' }}
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="panel">
      <div class="filter-bar">
        <el-tabs v-model="tab" class="table-tabs" @tab-change="resetFilters">
          <el-tab-pane label="客户资料" name="CUSTOMER" />
          <el-tab-pane label="供应商资料" name="SUPPLIER" />
          <el-tab-pane label="标准产品库" name="PRODUCT" />
          <el-tab-pane label="标准组合" name="BUNDLE" />
        </el-tabs>
        <el-input
          v-if="tab === 'CUSTOMER' || tab === 'SUPPLIER'"
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索名称或联系人"
          clearable
          @input="page = 1"
        />
      </div>
      <div v-if="tab === 'PRODUCT'" class="sub-filter">
        <el-select v-model="scenario" clearable placeholder="全部应用场景" class="scenario-filter" @change="page = 1">
          <el-option v-for="x in scenarios" :key="x" :label="x" :value="x" />
        </el-select>
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索名称、规格、型号或供应商"
          clearable
          class="product-search"
          @input="page = 1"
        />
        <span class="muted count">共 {{ filteredProducts.length }} 个产品</span>
      </div>
      <BundleManager v-if="tab === 'BUNDLE'" ref="bundleManager" :key="`bd${bundleKey}`" :products="products" />
      <el-table v-else-if="tab !== 'PRODUCT'" :data="filteredPartners.slice((page - 1) * 12, page * 12)">
        <el-table-column :label="tab === 'CUSTOMER' ? '客户名称' : '供应商名称'" prop="name" min-width="220" />
        <el-table-column label="联系人 / 电话" min-width="150">
          <template #default="{ row }">
            {{ row.contact || '—' }}
            <small class="cell-secondary muted">{{ row.phone }}</small>
          </template>
        </el-table-column>
        <!-- 开票抬头默认同名称，只在不同时显示；税号有才显示 -->
        <el-table-column label="开票信息" min-width="240">
          <template #default="{ row }">
            <span v-if="!missingInvoiceFields(row).length" class="invoice-ok">
              完整{{ row.invoice_type ? ` · ${row.invoice_type === 'SPECIAL' ? '专票' : '普票'}` : '' }}
            </span>
            <span v-else class="invoice-missing">缺：{{ missingInvoiceFields(row).join('、') }}</span>
            <small v-if="row.tax_no || (row.invoice_title && row.invoice_title !== row.name)" class="cell-secondary">
              {{ row.invoice_title && row.invoice_title !== row.name ? `抬头 ${row.invoice_title} ` : '' }}
              <span v-if="row.tax_no" class="mono">税号 {{ row.tax_no }}</span>
            </small>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="note" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite()" link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link @click="copyInvoice(row)">复制开票信息</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template v-else>
        <el-table :data="filteredProducts.slice((page - 1) * 12, page * 12)">
          <el-table-column label="应用场景" prop="scenario" width="120" show-overflow-tooltip />
          <el-table-column label="产品名称" min-width="190">
            <template #default="{ row }">{{ row.name }}</template>
          </el-table-column>
          <el-table-column label="规格" prop="specification" min-width="130" />
          <el-table-column label="型号" prop="model" min-width="90" />
          <el-table-column label="供应商" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.supplier_name">{{ row.supplier_name }}</span>
              <span v-else class="danger-text">未指定，请编辑补上</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canSeeCost()" label="采购成本" align="right" min-width="110">
            <template #default="{ row }">{{ row.purchase_cost == null ? '—' : money(row.purchase_cost) }}</template>
          </el-table-column>
          <el-table-column label="电气件" width="80" align="center">
            <template #default="{ row }">
              <el-button link :type="row.components ? 'primary' : 'info'" @click="openComponents(row)">
                {{ row.components ? `${row.components} 项` : canWrite() ? '填写' : '—' }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="图纸" width="80" align="center">
            <template #default="{ row }">
              <el-button link :type="row.drawings ? 'primary' : 'info'" @click="openDrawings(row)">
                {{ row.drawings ? `${row.drawings} 份` : canWrite() ? '上传' : '—' }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="历史售价参考" align="right" min-width="120">
            <template #default="{ row }">{{ money(row.reference_price) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost()" label="历史成本参考" align="right" min-width="120">
            <template #default="{ row }">{{ money(row.reference_cost) }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="edit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="panel-bottom-note">
          参考价格取最近一笔有记录的报价 / 成本，不代表供应商当前有效报价。报价时请核对配置、时间和税口径。
        </div>
      </template>
      <div v-if="tab !== 'BUNDLE'" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :total="tab === 'PRODUCT' ? filteredProducts.length : filteredPartners.length"
          :page-size="12"
          layout="total,prev,pager,next"
        />
      </div>
    </section>
    <el-dialog v-model="dialog" :title="`${editingId ? '编辑' : '新增'}${kindName}`" width="640px" top="6vh">
      <el-form label-position="top">
        <template v-if="tab !== 'PRODUCT'">
          <el-form-item label="名称" required><el-input v-model="partner.name" maxlength="200" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="联系人"><el-input v-model="partner.contact" maxlength="100" /></el-form-item>
            <el-form-item label="联系电话"><el-input v-model="partner.phone" maxlength="60" /></el-form-item>
          </div>
          <div class="form-section">开票信息</div>
          <div class="form-grid">
            <el-form-item label="开票抬头（默认同名称）">
              <el-input v-model="partner.invoiceTitle" maxlength="200" :placeholder="partner.name" />
            </el-form-item>
            <el-form-item label="纳税人识别号">
              <el-input v-model="partner.taxNo" maxlength="30" placeholder="18 位统一社会信用代码" class="mono" />
            </el-form-item>
          </div>
          <el-form-item label="注册地址">
            <el-input v-model="partner.address" maxlength="300" />
          </el-form-item>
          <div class="form-grid">
            <el-form-item label="开票电话"><el-input v-model="partner.invoicePhone" maxlength="60" /></el-form-item>
            <el-form-item label="发票类型">
              <el-select v-model="partner.invoiceType" clearable placeholder="未约定">
                <el-option v-for="(n, k) in invoiceTypeName" :key="k" :label="n" :value="k" />
              </el-select>
            </el-form-item>
            <el-form-item label="开户银行"><el-input v-model="partner.bankName" maxlength="200" /></el-form-item>
            <el-form-item label="银行账号">
              <el-input v-model="partner.bankAccount" maxlength="40" class="mono" />
            </el-form-item>
          </div>
          <el-form-item v-if="tab === 'CUSTOMER'" label="合同代码（合同号自动编号用）">
            <el-input
              v-model="partner.contractCode"
              maxlength="20"
              placeholder="如 SHHLZY，合同号为 TJ2026-SHHLZY-109"
            />
            <small class="muted">留空时按该客户已有合同号识别；新客户没有历史合同时请填写，否则用通用编号。</small>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="partner.note" type="textarea" maxlength="1000" /></el-form-item>
        </template>
        <template v-else>
          <div class="form-grid">
            <el-form-item label="应用场景" required>
              <el-select v-model="product.scenario" filterable placeholder="选择应用场景">
                <el-option v-for="x in selectableScenarios" :key="x" :label="x" :value="x" />
              </el-select>
              <small class="muted">新场景请在 系统管理 → 数据字典 → 应用场景 中添加</small>
            </el-form-item>
            <el-form-item label="单位" required><el-input v-model="product.unit" maxlength="30" /></el-form-item>
            <el-form-item label="供应商" required>
              <el-select v-model="product.supplierId" filterable placeholder="选择供应商">
                <el-option v-for="x in suppliers" :key="x.id" :label="x.name" :value="x.id" />
              </el-select>
              <small class="muted">同一种产品不同供应商各建一条，价格分开记</small>
            </el-form-item>
            <el-form-item v-if="canSeeCost()" label="采购成本（元 / 台）">
              <el-input-number
                v-model="product.purchaseCost"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="这家供应商的拿货价"
              />
            </el-form-item>
          </div>
          <el-form-item label="产品名称" required><el-input v-model="product.name" maxlength="200" /></el-form-item>
          <el-form-item label="规格（软件、服务类可留空）">
            <el-input v-model="product.specification" maxlength="200" placeholder="例如：650×850×250 mm" />
          </el-form-item>
          <el-form-item label="型号 / 关键配置"><el-input v-model="product.model" maxlength="100" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存资料</el-button>
      </template>
    </el-dialog>
    <ProductDrawings v-model="drawingsOpen" :product="drawingProduct" @changed="load" />
    <ProductComponents v-model="componentsOpen" :product="drawingProduct" @changed="load" />
  </div>
</template>
<style scoped>
.sub-filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid #eef1ec;
}
.form-section {
  margin: 4px 0 10px;
  padding-top: 10px;
  border-top: 1px solid #eef1ec;
  font-size: 14px;
  font-weight: 600;
  color: #253731;
}
.invoice-ok {
  color: #24634f;
  font-size: 13px;
}
.invoice-missing {
  color: #8a6a1f;
  font-size: 13px;
}
.cell-secondary {
  display: block;
}
.mono {
  font-variant-numeric: tabular-nums;
}
.scenario-filter {
  width: 180px;
}
.product-search {
  width: 260px;
}
.count {
  margin-left: auto;
  font-size: 13px;
}
</style>
