<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { api, canSeeCost, canWrite, post, put } from '../api'
import type { Bundle, Partner, Product } from '../types'
import { productLabel } from '../products'
import { money } from '../utils'
import { dict, loadDictionaries } from '../dictionaries'

/**
 * 标准组合（像套餐）：按应用场景把几个标准品和数量打包。报价、项目类型标准配置里可以整组加入。
 * 组合不单独定价：组合价 = 里面标准品的单价 × 数量之和；这里显示的是按各标准品最近一次报价算的参考价。
 */
const props = defineProps<{ products: Product[] }>()

const bundles = ref<Bundle[]>([]),
  keyword = ref(''),
  scenario = ref(''),
  dialog = ref(false),
  busy = ref(false),
  editingId = ref('')
const form = ref({
  scenario: '',
  supplierId: '',
  name: '',
  note: '',
  active: true,
  items: [] as { productId: string; quantity: number }[],
})
const suppliers = ref<Partner[]>([])

const scenarios = computed(() => dict.scenarios.map((s) => s.name))
const selectableScenarios = computed(() =>
  dict.scenarios.filter((s) => s.active || s.name === form.value.scenario).map((s) => s.name),
)
const filtered = computed(() =>
  bundles.value.filter(
    (b) =>
      (!scenario.value || b.scenario === scenario.value) &&
      `${b.scenario}${b.name}${b.items.map((i) => i.name + i.specification).join('')}`.includes(keyword.value.trim()),
  ),
)
const label = productLabel
/** 同场景的标准品排在前面。 */
/** 只列这家供应商的标准品，同场景的排在前面。 */
const productOptions = computed(() =>
  props.products
    .filter((p) => p.supplier_id === form.value.supplierId)
    .sort((a, b) => Number(b.scenario === form.value.scenario) - Number(a.scenario === form.value.scenario)),
)
/** 换了供应商：别家的标准品从组合里去掉。 */
function onSupplierChange() {
  form.value.items = form.value.items.filter((i) => {
    const p = byId.value.get(i.productId)
    return !p || p.supplier_id === form.value.supplierId
  })
  if (!form.value.items.length) form.value.items.push({ productId: '', quantity: 1 })
}
const byId = computed(() => new Map(props.products.map((p) => [p.id, p])))
const formPrice = computed(() => {
  let sum = 0,
    complete = form.value.items.length > 0
  for (const i of form.value.items) {
    const p = byId.value.get(i.productId)
    if (p?.reference_price == null) complete = false
    else sum += Number(p.reference_price) * i.quantity
  }
  return { sum, complete }
})

async function load() {
  bundles.value = await api<Bundle[]>('/bundles').catch(() => [])
  if (!suppliers.value.length)
    suppliers.value = (await api<Partner[]>('/partners').catch(() => [])).filter((x) => x.kind === 'SUPPLIER')
}
onMounted(() => {
  load()
  loadDictionaries().catch(() => {})
})

function open(b?: Bundle) {
  editingId.value = b?.id || ''
  form.value = b
    ? {
        scenario: b.scenario,
        supplierId: b.supplier_id || '',
        name: b.name,
        note: b.note || '',
        active: b.active,
        items: b.items.map((i) => ({ productId: i.product_id, quantity: Number(i.quantity) })),
      }
    : {
        scenario: scenario.value || '',
        supplierId: '',
        name: '',
        note: '',
        active: true,
        items: [{ productId: '', quantity: 1 }],
      }
  dialog.value = true
}
async function save() {
  const items = form.value.items.filter((i) => i.productId)
  if (!form.value.supplierId) return ElMessage.warning('请选择供应商')
  if (!form.value.scenario || !form.value.name.trim()) return ElMessage.warning('请选择应用场景并填写组合名称')
  if (!items.length) return ElMessage.warning('组合里至少要有一个标准品')
  if (items.some((i) => !(i.quantity > 0))) return ElMessage.warning('数量要大于 0')
  busy.value = true
  try {
    const body = { ...form.value, items }
    if (editingId.value) await put(`/bundles/${editingId.value}`, body)
    else await post('/bundles', body)
    dialog.value = false
    await load()
    ElMessage.success('组合已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
defineExpose({ open })
</script>
<template>
  <div>
    <div class="sub-filter">
      <el-select v-model="scenario" clearable placeholder="全部应用场景" class="scenario-filter">
        <el-option v-for="x in scenarios" :key="x" :label="x" :value="x" />
      </el-select>
      <el-input
        v-model="keyword"
        :prefix-icon="Search"
        clearable
        placeholder="搜索组合名称或里面的标准品"
        class="search"
      />
      <span class="muted count">共 {{ filtered.length }} 个组合</span>
    </div>
    <el-table :data="filtered" empty-text="还没有标准组合">
      <el-table-column label="供应商" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.supplier_name">{{ row.supplier_name }}</span>
          <span v-else class="danger-text">未指定，请编辑补上</span>
        </template>
      </el-table-column>
      <el-table-column label="应用场景" prop="scenario" width="120" />
      <el-table-column label="组合名称" min-width="160">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="!row.active" size="small" type="info">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="包含的标准品" min-width="320">
        <template #default="{ row }">
          <span v-for="(i, n) in row.items" :key="i.product_id" class="item">
            {{ i.name }} {{ i.specification }}{{ i.model ? ' / ' + i.model : '' }} × {{ Number(i.quantity) }}
            <span v-if="n < row.items.length - 1" class="muted">；</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="参考价" align="right" width="130">
        <template #default="{ row }">
          {{ row.reference_price == null ? '—' : money(row.reference_price) }}
          <small v-if="row.reference_price != null && !row.price_complete" class="cell-secondary">
            部分标准品没有参考价
          </small>
        </template>
      </el-table-column>
      <el-table-column v-if="canSeeCost()" label="采购成本" align="right" width="130">
        <template #default="{ row }">
          {{ row.reference_cost == null ? '—' : money(row.reference_cost) }}
          <small v-if="row.reference_cost != null && !row.cost_complete" class="cell-secondary">
            部分标准品没填采购成本
          </small>
        </template>
      </el-table-column>
      <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
        <template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialog"
      :title="editingId ? '编辑标准组合' : '新增标准组合'"
      width="min(820px, 96vw)"
      append-to-body
    >
      <el-form label-position="top" class="bundle-form">
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商" @change="onSupplierChange">
            <el-option v-for="x in suppliers" :key="x.id" :label="x.name" :value="x.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="应用场景" required>
          <el-select v-model="form.scenario" filterable placeholder="选择应用场景">
            <el-option v-for="x in selectableScenarios" :key="x" :label="x" :value="x" />
          </el-select>
        </el-form-item>
        <el-form-item label="组合名称" required>
          <el-input v-model="form.name" maxlength="120" placeholder="如 冷柜控制组合" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.active" />
        </el-form-item>
        <el-form-item label="备注" class="span-3">
          <el-input v-model="form.note" maxlength="1000" />
        </el-form-item>
      </el-form>
      <div class="items-head">
        <strong>包含的标准品</strong>
        <span class="muted">
          参考价 {{ form.items.some((i) => i.productId) ? money(formPrice.sum) : '—' }}
          <template v-if="form.items.some((i) => i.productId) && !formPrice.complete">
            （部分标准品没有参考价）
          </template>
        </span>
        <el-button size="small" :icon="Plus" @click="form.items.push({ productId: '', quantity: 1 })">
          添加标准品
        </el-button>
      </div>
      <el-table :data="form.items" size="small" class="editor-table" max-height="360">
        <el-table-column label="标准品" min-width="360">
          <template #default="{ row }">
            <el-select
              v-model="row.productId"
              filterable
              :placeholder="form.supplierId ? '选择这家供应商的标准品' : '先选供应商'"
              :disabled="!form.supplierId"
            >
              <el-option v-for="p in productOptions" :key="p.id" :value="p.id" :label="label(p)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="每套数量" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :precision="0" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="参考单价" width="120" align="right">
          <template #default="{ row }">{{ money(byId.get(row.productId)?.reference_price ?? null) }}</template>
        </el-table-column>
        <el-table-column label="" width="44">
          <template #default="{ $index }">
            <el-button link type="danger" :icon="Delete" @click="form.items.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.sub-filter {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 24px 12px;
}
.scenario-filter {
  width: 160px;
}
.search {
  width: 260px;
}
.count {
  margin-left: auto;
  font-size: 13px;
}
.item {
  white-space: nowrap;
}
.bundle-form {
  display: grid;
  grid-template-columns: 200px 160px 1fr 80px;
  gap: 0 14px;
}
.span-3 {
  grid-column: 1 / -1;
}
.items-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 8px;
}
.items-head .muted {
  margin-right: auto;
  font-size: 13px;
}
</style>
