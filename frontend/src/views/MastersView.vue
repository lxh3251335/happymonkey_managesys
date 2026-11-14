<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, put, canWrite } from '../api'
import type { Partner, Product } from '../types'
import { money } from '../utils'
const partners = ref<Partner[]>([]),
  products = ref<Product[]>([]),
  tab = ref('CUSTOMER'),
  keyword = ref(''),
  dialog = ref(false),
  busy = ref(false),
  error = ref(''),
  page = ref(1),
  editingId = ref('')
const partner = ref({ name: '', kind: 'CUSTOMER', contact: '', phone: '', note: '' })
const product = ref({ brand: '小象', name: '', specification: '', model: '', unit: '台' })
const filteredPartners = computed(() =>
  partners.value.filter(
    (p) => p.kind === tab.value && `${p.name}${p.contact || ''}${p.phone || ''}`.includes(keyword.value),
  ),
)
const filteredProducts = computed(() =>
  products.value.filter((p) => `${p.brand}${p.name}${p.specification}${p.model}`.includes(keyword.value)),
)
function resetFilters() {
  page.value = 1
  keyword.value = ''
}
async function load() {
  try {
    ;[partners.value, products.value] = await Promise.all([api<Partner[]>('/partners'), api<Product[]>('/products')])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
function open() {
  editingId.value = ''
  partner.value = { name: '', kind: tab.value, contact: '', phone: '', note: '' }
  product.value = { brand: '小象', name: '', specification: '', model: '', unit: '台' }
  dialog.value = true
}
function edit(row: Partner | Product) {
  editingId.value = row.id
  if (tab.value === 'PRODUCT') {
    const p = row as Product
    product.value = { brand: p.brand, name: p.name, specification: p.specification, model: p.model || '', unit: p.unit }
  } else {
    const p = row as Partner
    partner.value = { name: p.name, kind: p.kind, contact: p.contact || '', phone: p.phone || '', note: p.note || '' }
  }
  dialog.value = true
}
const kindName = computed(() => (tab.value === 'PRODUCT' ? '标准产品' : tab.value === 'CUSTOMER' ? '客户' : '供应商'))
async function save() {
  if (
    tab.value === 'PRODUCT'
      ? !product.value.name.trim() || !product.value.specification.trim()
      : !partner.value.name.trim()
  )
    return ElMessage.warning('请填写必填资料')
  busy.value = true
  try {
    const path = tab.value === 'PRODUCT' ? '/products' : '/partners'
    const body = tab.value === 'PRODUCT' ? product.value : partner.value
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
      eyebrow="MASTER DATA"
      title="基础资料"
      description="统一维护客户、供应商和标准产品，建立可以复用的报价基础。"
    >
      <el-button v-if="canWrite()" type="primary" @click="open">
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
        </el-tabs>
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="搜索名称、规格或联系人"
          clearable
          @input="page = 1"
        />
      </div>
      <el-table v-if="tab !== 'PRODUCT'" :data="filteredPartners.slice((page - 1) * 12, page * 12)">
        <el-table-column :label="tab === 'CUSTOMER' ? '客户名称' : '供应商名称'" prop="name" min-width="220" />
        <el-table-column label="联系人" prop="contact" min-width="130" />
        <el-table-column label="联系电话" prop="phone" min-width="160" />
        <el-table-column label="备注" prop="note" min-width="300" show-overflow-tooltip />
        <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <template v-else>
        <el-table :data="filteredProducts.slice((page - 1) * 12, page * 12)">
          <el-table-column label="品牌" prop="brand" width="100" />
          <el-table-column label="产品名称" prop="name" min-width="190" />
          <el-table-column label="规格" prop="specification" min-width="160" />
          <el-table-column label="型号" prop="model" min-width="110" />
          <el-table-column label="单位" prop="unit" width="75" />
          <el-table-column label="历史销售单价参考" align="right" min-width="170">
            <template #default="{ row }">{{ money(row.reference_price) }}</template>
          </el-table-column>
          <el-table-column label="历史单台成本参考" align="right" min-width="170">
            <template #default="{ row }">{{ money(row.reference_cost) }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button></template>
          </el-table-column>
        </el-table>
        <div class="panel-bottom-note">
          参考价格取最近一笔有记录的报价 / 成本，不代表供应商当前有效报价。报价时请核对配置、时间和税口径。
        </div>
      </template>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :total="tab === 'PRODUCT' ? filteredProducts.length : filteredPartners.length"
          :page-size="12"
          layout="total,prev,pager,next"
        />
      </div>
    </section>
    <el-dialog v-model="dialog" :title="`${editingId ? '编辑' : '新增'}${kindName}`" width="580px">
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
          <el-form-item label="备注"><el-input v-model="partner.note" type="textarea" maxlength="1000" /></el-form-item>
        </template>
        <template v-else>
          <div class="form-grid">
            <el-form-item label="品牌">
              <el-select v-model="product.brand">
                <el-option v-for="b in ['小象', '快乐猴', '其他']" :key="b" :label="b" :value="b" />
              </el-select>
            </el-form-item>
            <el-form-item label="单位" required><el-input v-model="product.unit" maxlength="30" /></el-form-item>
          </div>
          <el-form-item label="产品名称" required><el-input v-model="product.name" maxlength="200" /></el-form-item>
          <el-form-item label="规格" required>
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
  </div>
</template>
