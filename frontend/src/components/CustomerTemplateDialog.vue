<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { api, post } from '../api'
import type { CustomerTemplate, Partner, Product, ProjectType } from '../types'
import { productLabel } from '../products'
import { money } from '../utils'

/**
 * 客户报价模板编辑窗口：某客户在某项目类型下的产品、数量与协议售价。
 * 在“报价配置”中维护，也可从报价详情“存为客户模板”打开（seed 带入该报价的客户、类型与明细）。
 */
export interface TemplateSeed {
  customerId?: string
  projectTypeId?: string
  /** 带入的明细；不传则按已有模板或项目类型标准配置带出。 */
  items?: { product_id: string; quantity: number; unit_price: number | null }[]
  /** 明细来源说明，显示在表格上方。 */
  from?: string
}
const props = defineProps<{ modelValue: boolean; seed?: TemplateSeed }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

interface Line {
  productId: string
  quantity: number
  unitPrice: number | undefined
}
const form = ref({ customerId: '', projectTypeId: '', note: '', active: true, items: [] as Line[] })
const templates = ref<CustomerTemplate[]>([]),
  types = ref<ProjectType[]>([]),
  customers = ref<Partner[]>([]),
  products = ref<Product[]>([]),
  sourceNote = ref(''),
  busy = ref(false),
  fetching = ref(false)

const type = computed(() => types.value.find((t) => t.id === form.value.projectTypeId))
const existing = computed(() =>
  templates.value.find(
    (t) => t.customer_id === form.value.customerId && t.project_type_id === form.value.projectTypeId,
  ),
)
/** 项目类型标准售价，用于对照该客户的协议价。 */
const standardPrice = (productId: string) => {
  const v = type.value?.items.find((i) => i.product_id === productId)?.unit_price
  return v == null ? null : Number(v)
}
const total = computed(() => form.value.items.reduce((s, l) => s + l.quantity * (l.unitPrice || 0), 0))
const toLines = (items: { product_id: string; quantity: number; unit_price: number | null }[]): Line[] =>
  items.map((i) => ({
    productId: i.product_id,
    quantity: Number(i.quantity),
    unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
  }))

/** 选好客户和类型后：已有模板则载入，否则按项目类型标准配置起草。 */
function rebuild() {
  if (!form.value.customerId || !form.value.projectTypeId) return
  // 明细是从报价带入的：换客户或项目类型时保留明细，只沿用已有模板的备注与状态
  if (props.seed?.items) {
    if (existing.value) {
      form.value.note = existing.value.note || ''
      form.value.active = existing.value.active
    }
    return
  }
  if (existing.value) {
    form.value.note = existing.value.note || ''
    form.value.active = existing.value.active
    form.value.items = toLines(existing.value.items)
    sourceNote.value = '已载入该客户现有的模板，保存后整体替换。'
  } else useStandard()
}
function useStandard() {
  form.value.items = toLines(type.value?.items || [])
  sourceNote.value = form.value.items.length
    ? '已按项目类型的标准配置起草，请改成该客户的协议价。'
    : '该项目类型还没有标准配置，请手动添加产品，或从该客户最近的报价带入。'
}
async function useHistory() {
  if (!form.value.customerId || !form.value.projectTypeId) return ElMessage.warning('请先选择客户和项目类型')
  fetching.value = true
  try {
    const q = new URLSearchParams({ customerId: form.value.customerId, projectTypeId: form.value.projectTypeId })
    const r = await api<{
      quote_code: string
      project_name: string
      confirmed: boolean
      items: { product_id: string; quantity: number; unit_price: number | null }[]
    }>(`/customer-templates/from-history?${q}`)
    form.value.items = toLines(r.items)
    sourceNote.value = `已带入「${r.project_name}」${r.confirmed ? '已确认' : '最近一份'}报价（${r.quote_code}）的 ${r.items.length} 项产品与售价。`
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    fetching.value = false
  }
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    try {
      const [t, ty, p, pr] = await Promise.all([
        api<CustomerTemplate[]>('/customer-templates'),
        api<ProjectType[]>('/project-types'),
        api<Partner[]>('/partners'),
        api<Product[]>('/products'),
      ])
      templates.value = t
      types.value = ty
      customers.value = p.filter((x) => x.kind === 'CUSTOMER')
      products.value = pr
    } catch (e) {
      ElMessage.error((e as Error).message)
    }
    form.value = {
      customerId: props.seed?.customerId || '',
      projectTypeId: props.seed?.projectTypeId || '',
      note: '',
      active: true,
      items: [],
    }
    sourceNote.value = ''
    if (props.seed?.items) {
      // 带入明细时保留已有模板的备注与状态，明细以带入的为准
      form.value.note = existing.value?.note || ''
      form.value.active = existing.value?.active ?? true
      form.value.items = toLines(props.seed.items)
      sourceNote.value = props.seed.from || ''
    } else rebuild()
  },
)

async function save() {
  const f = form.value
  if (!f.customerId || !f.projectTypeId) return ElMessage.warning('请选择客户和项目类型')
  if (!f.items.length || f.items.some((l) => !l.productId))
    return ElMessage.warning('请至少添加一项产品，并选好每一行的产品')
  if (new Set(f.items.map((l) => l.productId)).size !== f.items.length) return ElMessage.warning('同一产品不能重复出现')
  busy.value = true
  try {
    await post('/customer-templates', {
      customerId: f.customerId,
      projectTypeId: f.projectTypeId,
      note: f.note,
      active: f.active,
      items: f.items.map((l) => ({ productId: l.productId, quantity: l.quantity, unitPrice: l.unitPrice ?? null })),
    })
    emit('update:modelValue', false)
    emit('saved')
    ElMessage.success('客户报价模板已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="客户报价模板"
    width="min(980px, 96vw)"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <div class="pick-grid">
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable placeholder="选择客户" @change="rebuild">
            <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目类型" required>
          <el-select v-model="form.projectTypeId" placeholder="选择项目类型" @change="rebuild">
            <el-option v-for="t in types" :key="t.id" :value="t.id" :label="t.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </div>
      <div v-if="existing && seed?.items" class="inline-note warning">
        该客户在此项目类型下已有模板（{{ existing.items.length }} 项），保存后会被下面的明细整体替换。
      </div>
      <div class="tool-row">
        <span class="muted">{{ sourceNote || '选择客户和项目类型后带出明细。' }}</span>
        <el-button size="small" :disabled="!form.projectTypeId" @click="useStandard">按标准配置带入</el-button>
        <el-button
          size="small"
          :loading="fetching"
          :disabled="!form.customerId || !form.projectTypeId"
          @click="useHistory"
        >
          从该客户最近报价带入
        </el-button>
      </div>
      <el-table :data="form.items" size="small" class="editor-table" empty-text="还没有产品">
        <el-table-column label="产品 / 规格" min-width="300">
          <template #default="{ row }">
            <el-select v-model="row.productId" filterable placeholder="选择标准产品">
              <el-option v-for="p in products" :key="p.id" :value="p.id" :label="productLabel(p)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="96">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0.001" :max="999999" :precision="0" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="协议售价" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" placeholder="待定" />
          </template>
        </el-table-column>
        <el-table-column label="标准售价" width="110" align="right">
          <template #default="{ row }">
            <span class="muted">
              {{ standardPrice(row.productId) == null ? '—' : money(standardPrice(row.productId)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="小计" width="120" align="right">
          <template #default="{ row }">
            {{ row.unitPrice == null ? '—' : money(row.quantity * row.unitPrice) }}
          </template>
        </el-table-column>
        <el-table-column width="64" align="center">
          <template #default="{ $index }">
            <el-button :icon="Delete" link type="danger" aria-label="删除产品" @click="form.items.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="tool-row foot">
        <el-button
          :icon="Plus"
          size="small"
          @click="form.items.push({ productId: '', quantity: 1, unitPrice: undefined })"
        >
          添加产品
        </el-button>
        <strong class="total">整套售价 {{ money(total) }}</strong>
      </div>
      <el-form-item label="备注">
        <el-input v-model="form.note" maxlength="1000" placeholder="如：2026 年框架协议价，含运费" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存模板</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.pick-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr 150px;
  gap: 0 16px;
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 13px;
}
.tool-row .muted {
  flex: 1;
}
.tool-row.foot {
  margin: 8px 0 12px;
}
.tool-row .total {
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
:deep(.el-input-number) {
  width: 100%;
}
@media (max-width: 760px) {
  .pick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
