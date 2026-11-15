<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import ProjectTypeManager from '../components/ProjectTypeManager.vue'
import SupplierTemplateManager from '../components/SupplierTemplateManager.vue'
import ProductDrawings from '../components/ProductDrawings.vue'
import ExcelTools from '../components/ExcelTools.vue'
import { api, post, put, canWrite, canFinance } from '../api'
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
})
const partner = ref(freshPartner())
const freshProduct = () => ({
  scenario: scenario.value || '',
  name: '',
  specification: '',
  model: '',
  unit: '台',
  supplierId: '',
  mainstream: false,
})
const scenario = ref(''),
  mainstreamOnly = ref(false),
  drawingsOpen = ref(false),
  drawingProduct = ref<Product>()
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
      (!mainstreamOnly.value || p.mainstream) &&
      `${p.scenario}${p.name}${p.specification}${p.model}${p.supplier_name || ''}`.includes(keyword.value),
  ),
)
async function toggleMainstream(p: Product) {
  try {
    await put(`/products/${p.id}/mainstream`, { mainstream: !p.mainstream })
    p.mainstream = !p.mainstream
    ElMessage.success(p.mainstream ? '已设为主流配置' : '已取消主流配置')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function copyInvoice(p: Partner) {
  try {
    await navigator.clipboard.writeText(invoiceText(p))
    ElMessage.success('开票信息已复制')
  } catch {
    ElMessage.warning('浏览器未允许复制，请手动选择文本')
  }
}
function openDrawings(p: Product) {
  drawingProduct.value = p
  drawingsOpen.value = true
}
function resetFilters() {
  page.value = 1
  keyword.value = ''
  scenario.value = ''
  mainstreamOnly.value = false
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
      TYPES: { dataset: 'project-types', title: '项目类型', canImport: canWrite() },
      COSTS: { dataset: 'supplier-templates', title: '供应商成本模板', canImport: canFinance() },
    })[tab.value]!,
)
/** 导入后重新挂载项目类型 / 成本模板组件以刷新数据。 */
const reloadKey = ref(0)
async function afterImport() {
  reloadKey.value++
  await load()
}
const isConfig = computed(() => tab.value === 'TYPES' || tab.value === 'COSTS')
const kindName = computed(() => (tab.value === 'PRODUCT' ? '标准产品' : tab.value === 'CUSTOMER' ? '客户' : '供应商'))
async function save() {
  if (
    tab.value === 'PRODUCT' ? !product.value.scenario.trim() || !product.value.name.trim() : !partner.value.name.trim()
  )
    return ElMessage.warning('请填写必填资料')
  busy.value = true
  try {
    const path = tab.value === 'PRODUCT' ? '/products' : '/partners'
    const body =
      tab.value === 'PRODUCT' ? { ...product.value, supplierId: product.value.supplierId || null } : partner.value
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
    <PageHeader
      title="基础资料"
      description="客户、供应商、标准产品、项目类型与供应商成本，报价与经营分析都以此为基础。"
    >
      <ExcelTools
        :key="excel.dataset"
        :dataset="excel.dataset"
        :title="excel.title"
        :can-import="excel.canImport"
        @imported="afterImport"
      />
      <el-button v-if="canWrite() && !isConfig" type="primary" size="small" @click="open">
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
          <el-tab-pane label="项目类型" name="TYPES" />
          <el-tab-pane label="供应商成本模板" name="COSTS" />
        </el-tabs>
        <el-input
          v-if="!isConfig && tab !== 'PRODUCT'"
          v-model="keyword"
          :prefix-icon="Search"
          :placeholder="tab === 'PRODUCT' ? '搜索名称、规格、型号或供应商' : '搜索名称或联系人'"
          clearable
          @input="page = 1"
        />
      </div>
      <div v-if="tab === 'PRODUCT'" class="sub-filter">
        <el-select v-model="scenario" clearable placeholder="全部应用场景" class="scenario-filter" @change="page = 1">
          <el-option v-for="x in scenarios" :key="x" :label="x" :value="x" />
        </el-select>
        <el-checkbox v-model="mainstreamOnly" @change="page = 1">只看主流配置</el-checkbox>
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
      <ProjectTypeManager v-if="tab === 'TYPES'" :key="`t${reloadKey}`" :products="products" />
      <SupplierTemplateManager v-else-if="tab === 'COSTS'" :key="`c${reloadKey}`" />
      <el-table v-else-if="tab !== 'PRODUCT'" :data="filteredPartners.slice((page - 1) * 12, page * 12)">
        <el-table-column :label="tab === 'CUSTOMER' ? '客户名称' : '供应商名称'" prop="name" min-width="220" />
        <el-table-column label="联系人 / 电话" min-width="150">
          <template #default="{ row }">
            {{ row.contact || '—' }}
            <small class="cell-secondary muted">{{ row.phone }}</small>
          </template>
        </el-table-column>
        <el-table-column label="开票抬头 / 税号" min-width="240">
          <template #default="{ row }">
            {{ row.invoice_title || row.name }}
            <small class="cell-secondary mono">{{ row.tax_no || '未填税号' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="开票信息" width="150">
          <template #default="{ row }">
            <span v-if="!missingInvoiceFields(row).length" class="invoice-ok">
              完整{{ row.invoice_type ? ` · ${row.invoice_type === 'SPECIAL' ? '专票' : '普票'}` : '' }}
            </span>
            <span v-else class="invoice-missing" :title="`缺：${missingInvoiceFields(row).join('、')}`">
              缺 {{ missingInvoiceFields(row).length }} 项
            </span>
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
            <template #default="{ row }">
              {{ row.name }}
              <span v-if="row.mainstream" class="mainstream-tag">主流配置</span>
            </template>
          </el-table-column>
          <el-table-column label="规格" prop="specification" min-width="130" />
          <el-table-column label="型号" prop="model" min-width="90" />
          <el-table-column label="单位" prop="unit" width="60" />
          <el-table-column label="供应商" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="row.supplier_name ? '' : 'muted'">{{ row.supplier_name || '未指定' }}</span>
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
          <el-table-column label="历史成本参考" align="right" min-width="120">
            <template #default="{ row }">{{ money(row.reference_cost) }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite()" label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="edit(row)">编辑</el-button>
              <el-button link @click="toggleMainstream(row)">{{ row.mainstream ? '取消主流' : '设为主流' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="panel-bottom-note">
          参考价格取最近一笔有记录的报价 / 成本，不代表供应商当前有效报价。报价时请核对配置、时间和税口径。
        </div>
      </template>
      <div v-if="!isConfig" class="pagination">
        <el-pagination
          v-model:current-page="page"
          :total="tab === 'PRODUCT' ? filteredProducts.length : filteredPartners.length"
          :page-size="12"
          layout="total,prev,pager,next"
        />
      </div>
    </section>
    <el-dialog v-model="dialog" :title="`${editingId ? '编辑' : '新增'}${kindName}`" width="640px" top="6vh">
      <div v-if="editingId && tab === 'PRODUCT'" class="inline-note">
        修改只影响之后新建的报价；已保存的报价明细保留当时的品名、规格和单位。
      </div>
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
            <el-form-item label="供应商">
              <el-select v-model="product.supplierId" filterable clearable placeholder="未指定">
                <el-option v-for="x in suppliers" :key="x.id" :label="x.name" :value="x.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="标签">
              <el-checkbox v-model="product.mainstream">主流配置</el-checkbox>
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
  font-size: 13px;
  font-weight: 600;
  color: #253731;
}
.invoice-ok {
  color: #24634f;
  font-size: 12px;
}
.invoice-missing {
  color: #8a6a1f;
  font-size: 12px;
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
  font-size: 12px;
}
.mainstream-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 3px;
  background: #e6f0ea;
  color: #24634f;
  font-size: 11px;
  line-height: 18px;
}
</style>
