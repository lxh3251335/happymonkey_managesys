<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { api, post } from '../api'
import type {
  Partner,
  PrefillLine,
  Project,
  Product,
  ProjectType,
  Quote,
  QuoteTemplate,
  SupplierTemplate,
} from '../types'
import { afterDays, money, percent } from '../utils'

const props = defineProps<{
  modelValue: boolean
  projects: Project[]
  products: Product[]
  projectId?: string
  seed?: Quote
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [string] }>()

interface LineDraft {
  productId: string
  quantity: number
  unitPrice: number | undefined
  materialCost: number | undefined
  laborCost: number | undefined
  financeCost: number | undefined
}
const blankLine = (): LineDraft => ({
  productId: '',
  quantity: 1,
  unitPrice: undefined,
  materialCost: undefined,
  laborCost: undefined,
  financeCost: undefined,
})

const mode = ref<'new' | 'existing'>('new')
const store = ref({ customerId: '', name: '', city: '', contractNo: '' })
const form = ref({
  projectId: '',
  projectTypeId: '',
  supplierId: '',
  taxBasis: 'INCLUDED',
  taxPercent: 13,
  validUntil: afterDays(30),
  note: '',
  templateId: '',
})
const lines = ref<LineDraft[]>([]),
  busy = ref(false),
  filling = ref(false),
  types = ref<ProjectType[]>([]),
  supplierTemplates = ref<SupplierTemplate[]>([]),
  partners = ref<Partner[]>([]),
  templates = ref<QuoteTemplate[]>([]),
  prefillNote = ref('')

const customers = computed(() => partners.value.filter((p) => p.kind === 'CUSTOMER'))
const cities = computed(() =>
  [...new Set(props.projects.map((p) => p.city).filter((c): c is string => !!c))].sort((a, b) =>
    a.localeCompare(b, 'zh-CN'),
  ),
)
const activeTypes = computed(() => types.value.filter((t) => t.active))
/** 当前项目类型下有成本模板的供应商。 */
const suppliers = computed(() =>
  supplierTemplates.value.filter((s) => s.active && s.project_type_id === form.value.projectTypeId),
)
const currentProject = computed(() => props.projects.find((p) => p.id === form.value.projectId))

async function loadOptions() {
  const [t, s, p, q] = await Promise.all([
    api<ProjectType[]>('/project-types'),
    api<SupplierTemplate[]>('/supplier-templates'),
    api<Partner[]>('/partners'),
    api<QuoteTemplate[]>('/quotes/templates'),
  ])
  types.value = t
  supplierTemplates.value = s
  partners.value = p
  templates.value = q.filter((x) => x.active)
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    try {
      await loadOptions()
    } catch (e) {
      ElMessage.error((e as Error).message)
    }
    const fromProject = props.seed?.project_id || props.projectId || ''
    mode.value = fromProject ? 'existing' : 'new'
    store.value = { customerId: '', name: '', city: '', contractNo: '' }
    const project = props.projects.find((p) => p.id === fromProject)
    form.value = {
      projectId: fromProject,
      projectTypeId: props.seed?.project_type_id || project?.project_type_id || '',
      supplierId: props.seed?.supplier_id || '',
      taxBasis: props.seed?.tax_basis || 'INCLUDED',
      taxPercent: props.seed ? (props.seed.tax_rate || 0) * 100 : 13,
      validUntil: afterDays(30),
      note: props.seed?.note || '',
      templateId: templates.value.find((t) => t.is_default)?.id || '',
    }
    prefillNote.value = ''
    lines.value = props.seed
      ? props.seed.items.map((i) => ({
          productId: i.product_id,
          quantity: Number(i.quantity),
          unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
          // 历史报价没有成本拆分时，把单台成本放在材料成本中
          materialCost:
            i.material_cost != null
              ? Number(i.material_cost)
              : i.labor_cost == null && i.finance_cost == null && i.unit_cost != null
                ? Number(i.unit_cost)
                : undefined,
          laborCost: i.labor_cost == null ? undefined : Number(i.labor_cost),
          financeCost: i.finance_cost == null ? undefined : Number(i.finance_cost),
        }))
      : []
    if (!lines.value.length) lines.value.push(blankLine())
  },
)

/** 选已有项目时带出其项目类型。 */
watch(
  () => form.value.projectId,
  (id) => {
    const p = props.projects.find((x) => x.id === id)
    if (p?.project_type_id && !props.seed) form.value.projectTypeId = p.project_type_id
  },
)

/** 按项目类型 + 供应商模板带出明细（会替换当前明细）。 */
async function applyTemplate() {
  if (!form.value.projectTypeId) return
  filling.value = true
  try {
    const q = form.value.supplierId ? `?supplierId=${encodeURIComponent(form.value.supplierId)}` : ''
    const r = await api<{ items: PrefillLine[] }>(`/project-types/${form.value.projectTypeId}/prefill${q}`)
    const n = (v: number | null) => (v == null ? undefined : Number(v))
    lines.value = r.items.map((i) => ({
      productId: i.product_id,
      quantity: Number(i.quantity),
      unitPrice: n(i.unit_price),
      materialCost: n(i.material_cost),
      laborCost: n(i.labor_cost),
      financeCost: n(i.finance_cost),
    }))
    if (!lines.value.length) lines.value.push(blankLine())
    const missing = r.items.filter((i) => i.unit_cost == null).length
    prefillNote.value = !form.value.supplierId
      ? '已按项目类型带出配置和售价；选择供应商后带出成本。'
      : missing
        ? `已带出配置、售价和供应商成本；${missing} 项该供应商模板未报成本，请补充。`
        : '已带出配置、售价和供应商成本，可逐行修改。'
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    filling.value = false
  }
}
function onTypeChange() {
  if (!suppliers.value.some((s) => s.supplier_id === form.value.supplierId)) form.value.supplierId = ''
  applyTemplate()
}

const unitCost = (l: LineDraft) =>
  l.materialCost == null && l.laborCost == null && l.financeCost == null
    ? null
    : (l.materialCost || 0) + (l.laborCost || 0) + (l.financeCost || 0)
const cents = (n: number) => Math.round(n * 100) / 100
const lineAmount = (l: LineDraft) => (l.unitPrice == null ? null : cents(l.quantity * l.unitPrice))
const lineCost = (l: LineDraft) => {
  const c = unitCost(l)
  return c == null ? null : cents(l.quantity * c)
}
const lineMargin = (l: LineDraft) => {
  const a = lineAmount(l),
    c = lineCost(l)
  return a == null || c == null || a === 0 ? null : (a - c) / a
}
const total = computed(() => lines.value.reduce((s, l) => s + (lineAmount(l) || 0), 0))
const part = (k: 'materialCost' | 'laborCost' | 'financeCost') =>
  lines.value.reduce((s, l) => s + cents(l.quantity * (l[k] || 0)), 0)
const costComplete = computed(() => lines.value.every((l) => unitCost(l) != null))
const cost = computed(() => (costComplete.value ? lines.value.reduce((s, l) => s + (lineCost(l) || 0), 0) : null))
const profit = computed(() => (cost.value == null ? null : total.value - cost.value))
const margin = computed(() => (profit.value == null || total.value === 0 ? null : profit.value / total.value))

function productLabel(id: string) {
  const p = props.products.find((x) => x.id === id)
  return p ? `${p.brand} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}` : ''
}
function selectProduct(line: LineDraft) {
  const p = props.products.find((p) => p.id === line.productId)
  line.unitPrice = p?.reference_price == null ? undefined : Number(p.reference_price)
  line.materialCost = p?.reference_cost == null ? undefined : Number(p.reference_cost)
  line.laborCost = undefined
  line.financeCost = undefined
}

async function save() {
  if (mode.value === 'new') {
    if (!store.value.customerId || !store.value.name.trim() || !form.value.projectTypeId)
      return ElMessage.warning('请选择客户、项目类型，并填写门店名称')
  } else if (!form.value.projectId) return ElMessage.warning('请选择项目')
  if (!form.value.validUntil || !lines.value.length || lines.value.some((l) => !l.productId || l.unitPrice == null))
    return ElMessage.warning('请补全产品、数量、销售单价和有效期')
  busy.value = true
  try {
    const result = await post<{ id: string }>('/quotes', {
      projectId: mode.value === 'existing' ? form.value.projectId : null,
      newProject:
        mode.value === 'new'
          ? {
              customerId: store.value.customerId,
              name: store.value.name.trim(),
              city: store.value.city || null,
              projectTypeId: form.value.projectTypeId,
              contractNo: store.value.contractNo || null,
            }
          : null,
      projectTypeId: form.value.projectTypeId || null,
      supplierId: form.value.supplierId || null,
      taxBasis: form.value.taxBasis,
      taxRate: form.value.taxBasis === 'UNKNOWN' ? null : form.value.taxPercent / 100,
      validUntil: form.value.validUntil,
      note: form.value.note,
      templateId: form.value.templateId || null,
      items: lines.value.map((l) => ({
        productId: l.productId,
        quantity: l.quantity,
        unitPrice: l.unitPrice,
        materialCost: l.materialCost ?? null,
        laborCost: l.laborCost ?? null,
        financeCost: l.financeCost ?? null,
      })),
    })
    emit('update:modelValue', false)
    emit('saved', result.id)
    ElMessage.success(mode.value === 'new' ? '已登记门店并保存报价草稿' : '已保存新的报价版本')
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
    @update:model-value="emit('update:modelValue', $event)"
    title="创建客户报价"
    width="min(1340px, 96vw)"
    top="4vh"
    :close-on-click-modal="false"
  >
    <el-form label-position="top" class="quote-form">
      <div class="step-head">
        <span class="step-no">1</span>
        门店与项目类型
        <el-radio-group v-model="mode" size="small" class="mode-switch" :disabled="!!seed">
          <el-radio-button value="new">新门店</el-radio-button>
          <el-radio-button value="existing">已登记项目</el-radio-button>
        </el-radio-group>
      </div>
      <div v-if="mode === 'new'" class="step-grid">
        <el-form-item label="客户" required>
          <el-select v-model="store.customerId" filterable default-first-option placeholder="选择客户">
            <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="城市">
          <el-select
            v-model="store.city"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="选择或输入城市"
          >
            <el-option v-for="c in cities" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目类型" required>
          <el-select v-model="form.projectTypeId" placeholder="如：小象前置仓" @change="onTypeChange">
            <el-option v-for="t in activeTypes" :key="t.id" :value="t.id" :label="t.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="门店名称" required class="span-2">
          <el-input
            v-model="store.name"
            maxlength="300"
            placeholder="例如：小象天津丰泽站（城市未选时按名称自动识别）"
          />
        </el-form-item>
        <el-form-item label="合同号（可选）">
          <el-input v-model="store.contractNo" maxlength="100" />
        </el-form-item>
      </div>
      <div v-else class="step-grid">
        <el-form-item label="报价项目" required class="span-2">
          <el-select v-model="form.projectId" filterable placeholder="选择门店 / 项目" :disabled="!!seed">
            <el-option
              v-for="p in projects"
              :key="p.id"
              :value="p.id"
              :label="`${p.name} · ${p.customer_name}${p.city ? ' · ' + p.city : ''}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="项目类型">
          <el-select v-model="form.projectTypeId" clearable placeholder="选择后可带出模板" @change="onTypeChange">
            <el-option v-for="t in activeTypes" :key="t.id" :value="t.id" :label="t.name" />
          </el-select>
        </el-form-item>
        <p v-if="currentProject" class="muted project-hint span-3">
          {{ currentProject.customer_name }} · {{ currentProject.brand }} · {{ currentProject.city || '未填城市' }}
          {{ currentProject.contract_no ? ` · 合同号 ${currentProject.contract_no}` : '' }}
        </p>
      </div>

      <div class="step-head">
        <span class="step-no">2</span>
        供应商与报价条件
      </div>
      <div class="step-grid four">
        <el-form-item label="供应商（带出成本）">
          <el-select
            v-model="form.supplierId"
            clearable
            :disabled="!form.projectTypeId"
            :placeholder="
              !form.projectTypeId ? '先选项目类型' : suppliers.length ? '选择供应商' : '该类型暂无供应商成本模板'
            "
            @change="applyTemplate"
          >
            <el-option v-for="s in suppliers" :key="s.supplier_id" :value="s.supplier_id" :label="s.supplier_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="报价有效期" required>
          <el-date-picker v-model="form.validUntil" value-format="YYYY-MM-DD" type="date" />
        </el-form-item>
        <el-form-item label="税口径 / 税率（%）">
          <div class="tax-pair">
            <el-select v-model="form.taxBasis">
              <el-option label="含税" value="INCLUDED" />
              <el-option label="未税" value="EXCLUDED" />
              <el-option label="待核实" value="UNKNOWN" />
            </el-select>
            <el-input-number
              v-if="form.taxBasis !== 'UNKNOWN'"
              v-model="form.taxPercent"
              :min="0"
              :max="100"
              :precision="2"
              :controls="false"
            />
          </div>
        </el-form-item>
        <el-form-item label="报价单模板（抬头与条款）">
          <el-select v-model="form.templateId" placeholder="默认模板">
            <el-option
              v-for="t in templates"
              :key="t.id"
              :value="t.id"
              :label="`${t.name}${t.is_default ? '（默认）' : ''}`"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="step-head">
        <span class="step-no">3</span>
        报价明细与毛利
        <el-button
          v-if="form.projectTypeId"
          size="small"
          link
          type="primary"
          :loading="filling"
          class="refill"
          @click="applyTemplate"
        >
          按模板重新带出
        </el-button>
      </div>
      <div v-if="prefillNote" class="inline-note">{{ prefillNote }}</div>
      <el-table :data="lines" class="editor-table" size="small">
        <el-table-column label="产品 / 规格" min-width="230">
          <template #default="{ row }">
            <el-select
              v-model="row.productId"
              filterable
              :title="productLabel(row.productId)"
              placeholder="选择标准产品"
              @change="selectProduct(row)"
            >
              <el-option v-for="p in products" :key="p.id" :value="p.id" :label="productLabel(p.id)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="86">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0.001" :max="999999" :precision="0" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="销售单价" width="124">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" placeholder="必填" />
          </template>
        </el-table-column>
        <el-table-column label="材料成本" width="116">
          <template #default="{ row }">
            <el-input-number v-model="row.materialCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column label="人工成本" width="104">
          <template #default="{ row }">
            <el-input-number v-model="row.laborCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column label="财务成本" width="104">
          <template #default="{ row }">
            <el-input-number v-model="row.financeCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column label="单台成本" width="104" align="right">
          <template #default="{ row }">
            <span :class="unitCost(row) == null ? 'muted' : ''">
              {{ unitCost(row) == null ? '待补充' : money(unitCost(row)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="销售小计" width="112" align="right">
          <template #default="{ row }">{{ lineAmount(row) == null ? '待补充' : money(lineAmount(row)) }}</template>
        </el-table-column>
        <el-table-column label="毛利率" width="84" align="right">
          <template #default="{ row }">
            <span :class="lineMargin(row) != null && lineMargin(row)! < 0 ? 'danger-text' : ''">
              {{ lineMargin(row) == null ? '—' : percent(lineMargin(row)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column width="48">
          <template #default="{ $index }">
            <el-button :icon="Delete" link type="danger" aria-label="删除报价明细" @click="lines.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <el-button class="add-line" :icon="Plus" size="small" @click="lines.push(blankLine())">添加产品</el-button>

      <div class="margin-board">
        <div class="mb-main">
          <span>报价合计</span>
          <strong>{{ money(total) }}</strong>
        </div>
        <div>
          <span>材料成本</span>
          <strong>{{ money(part('materialCost')) }}</strong>
        </div>
        <div>
          <span>人工成本</span>
          <strong>{{ money(part('laborCost')) }}</strong>
        </div>
        <div>
          <span>财务成本</span>
          <strong>{{ money(part('financeCost')) }}</strong>
        </div>
        <div>
          <span>成本合计</span>
          <strong>{{ cost == null ? '待补充' : money(cost) }}</strong>
        </div>
        <div>
          <span>预计毛利</span>
          <strong :class="profit != null && profit < 0 ? 'danger-text' : ''">
            {{ profit == null ? '待补充' : money(profit) }}
          </strong>
        </div>
        <div class="mb-main">
          <span>毛利率</span>
          <strong :class="margin != null && margin < 0 ? 'danger-text' : ''">
            {{ margin == null ? '待补充' : percent(margin) }}
          </strong>
        </div>
      </div>
      <p v-if="!costComplete" class="muted cost-hint">有明细未填成本，毛利与毛利率暂不计算。</p>

      <el-form-item label="对客备注（本单）">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="2"
          maxlength="2000"
          placeholder="本单特别说明，例如交货周期、运输范围。通用条款请维护在报价模板中。请勿填写内部成本。"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存报价草稿</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.quote-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: #253731;
}
.step-no {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #24634f;
  color: #fff;
  font-size: 11px;
}
.mode-switch,
.refill {
  margin-left: auto;
}
.step-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.step-grid.four {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
.span-2 {
  grid-column: span 2;
}
.span-3 {
  grid-column: span 3;
}
.project-hint {
  margin: -6px 0 10px;
  font-size: 12px;
}
.tax-pair {
  display: grid;
  grid-template-columns: 1fr 90px;
  gap: 6px;
  width: 100%;
}
.editor-table :deep(.el-input-number) {
  width: 100%;
}
.editor-table :deep(.cell) {
  white-space: nowrap;
  padding: 0 6px;
}
.margin-board {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 1px;
  margin: 12px 0 4px;
  border: 1px solid #e4eae0;
  border-radius: 6px;
  overflow: hidden;
  background: #e4eae0;
}
.margin-board > div {
  background: #fafcf8;
  padding: 10px 12px;
}
.margin-board span {
  display: block;
  font-size: 11px;
  color: #6b7a72;
}
.margin-board strong {
  font-size: 15px;
  font-weight: 500;
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.margin-board .mb-main {
  background: #eef5ef;
}
.margin-board .mb-main strong {
  font-size: 18px;
}
.cost-hint {
  font-size: 12px;
  margin: 0 0 8px;
}
@media (max-width: 900px) {
  .step-grid,
  .step-grid.four {
    grid-template-columns: 1fr;
  }
  .span-2,
  .span-3 {
    grid-column: auto;
  }
  .margin-board {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
