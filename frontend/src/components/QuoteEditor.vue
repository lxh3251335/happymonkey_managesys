<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { api, canApprove, canSeeCost, post } from '../api'
import CitySelect from './CitySelect.vue'
import { activeCompanies, companyLabel, defaultCompany, loadDictionaries } from '../dictionaries'
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
const store = ref({ customerId: '', name: '', city: '' })
const form = ref({
  projectId: '',
  projectTypeId: '',
  supplierId: '',
  taxBasis: 'INCLUDED',
  taxPercent: 13,
  validUntil: afterDays(30),
  note: '',
  templateId: '',
  companyId: '',
})
/** 业务员看不到成本：成本列与毛利隐藏，保存时由系统按供应商成本模板核算。 */
const seeCost = canSeeCost()
const lines = ref<LineDraft[]>([]),
  busy = ref(false),
  filling = ref(false),
  types = ref<ProjectType[]>([]),
  supplierTemplates = ref<SupplierTemplate[]>([]),
  partners = ref<Partner[]>([]),
  templates = ref<QuoteTemplate[]>([]),
  prefillNote = ref('')

const customers = computed(() => partners.value.filter((p) => p.kind === 'CUSTOMER'))
const activeTypes = computed(() => types.value.filter((t) => t.active))
/** 当前项目类型下有成本模板的供应商。 */
const suppliers = computed(() =>
  supplierTemplates.value.filter((s) => s.active && s.project_type_id === form.value.projectTypeId),
)
const currentProject = computed(() => props.projects.find((p) => p.id === form.value.projectId))
/** 本次报价的客户：新门店取所选客户，已登记项目取门店的客户；用于匹配客户报价模板。 */
const customerId = computed(() =>
  mode.value === 'new' ? store.value.customerId : currentProject.value?.customer_id || '',
)
/** 明细是否还没动过（只有空行）：此时切换客户 / 项目可以放心按模板重新带出。 */
const untouched = () => lines.value.every((l) => !l.productId)
/** 模板带出时的明细快照；明细与快照一致说明带出后没有手工改过，换客户时可以重新带出而不丢修改。 */
const prefillSnapshot = ref('')
const unchangedSincePrefill = () => !!prefillSnapshot.value && prefillSnapshot.value === JSON.stringify(lines.value)

/** 合同已签订的门店：新报价等于改合同，只有管理层 / 管理员可以选。 */
const signed = (p: Project) => p.contract_state !== 'NONE' && p.contract_state !== 'DRAFT'
const includeSigned = ref(false)
/** “已登记项目”可选范围：尚未签合同（或合同仍在拟定）的门店；当前已选的保留。 */
const quotableProjects = computed(() =>
  props.projects.filter((p) => !signed(p) || (canApprove() && includeSigned.value) || p.id === form.value.projectId),
)
const hiddenSigned = computed(() => props.projects.filter(signed).length)

// ---------- 参考已签约项目：带入产品、数量与售价 ----------
const referenceId = ref(''),
  referencing = ref(false)
/** 可参考的项目：合同已签订或已完结的门店；选了项目类型时同业务线的排在前面。 */
const referenceProjects = computed(() => {
  const brand = types.value.find((t) => t.id === form.value.projectTypeId)?.brand
  return props.projects
    .filter((p) => p.contract_state === 'SIGNED' || p.contract_state === 'COMPLETED')
    .sort((a, b) => Number(b.brand === brand) - Number(a.brand === brand) || a.name.localeCompare(b.name, 'zh-CN'))
})
async function applyReference() {
  if (!referenceId.value) return
  referencing.value = true
  try {
    const q = await api<Quote>(`/quotes/reference?projectId=${encodeURIComponent(referenceId.value)}`)
    const n = (v: number | null | undefined) => (v == null ? undefined : Number(v))
    // 已选供应商且看得到成本时，成本仍按供应商成本模板（当前有效成本），不沿用旧报价的成本
    const keepCost = new Map(lines.value.map((l) => [l.productId, l]))
    lines.value = q.items.map((i) => {
      const old = keepCost.get(i.product_id)
      return {
        productId: i.product_id,
        quantity: Number(i.quantity),
        unitPrice: n(i.unit_price),
        materialCost:
          old?.materialCost ??
          n(i.material_cost) ??
          (i.labor_cost == null && i.finance_cost == null ? n(i.unit_cost) : undefined),
        laborCost: old?.laborCost ?? n(i.labor_cost),
        financeCost: old?.financeCost ?? n(i.finance_cost),
      }
    })
    if (!lines.value.length) lines.value.push(blankLine())
    prefillSnapshot.value = ''
    prefillNote.value = `已参考「${q.project_name}」（${q.contract_no || '合同'}）带入 ${q.items.length} 项产品、数量和售价，可逐行修改。`
    ElMessage.success('已带入参考项目的产品与报价')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    referencing.value = false
  }
}

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
      await Promise.all([loadOptions(), loadDictionaries()])
    } catch (e) {
      ElMessage.error((e as Error).message)
    }
    const fromProject = props.seed?.project_id || props.projectId || ''
    mode.value = fromProject ? 'existing' : 'new'
    store.value = { customerId: '', name: '', city: '' }
    referenceId.value = ''
    includeSigned.value = false
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
      companyId: props.seed?.company_id || defaultCompany.value?.id || '',
    }
    prefillNote.value = ''
    prefillSnapshot.value = ''
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

/** 选已有项目时带出其项目类型；明细还没填时直接按模板带出。 */
watch(
  () => form.value.projectId,
  (id) => {
    const p = props.projects.find((x) => x.id === id)
    if (!p || props.seed || !props.modelValue) return
    if (p.project_type_id) form.value.projectTypeId = p.project_type_id
    if (form.value.projectTypeId && untouched()) applyTemplate()
  },
)
/** 新门店换了客户：客户报价模板可能不同，明细还没填或当前就是按模板带出的，则重新带出。 */
watch(
  () => store.value.customerId,
  () => {
    if (mode.value !== 'new' || props.seed || !props.modelValue || !form.value.projectTypeId) return
    if (untouched() || unchangedSincePrefill()) applyTemplate()
  },
)

/** 按客户报价模板（没有则按项目类型标准配置）+ 供应商成本模板带出明细（会替换当前明细）。 */
async function applyTemplate() {
  if (!form.value.projectTypeId) return
  filling.value = true
  try {
    const params = new URLSearchParams()
    if (form.value.supplierId) params.set('supplierId', form.value.supplierId)
    if (customerId.value) params.set('customerId', customerId.value)
    const q = params.size ? `?${params}` : ''
    const r = await api<{ items: PrefillLine[]; source: 'CUSTOMER' | 'TYPE' }>(
      `/project-types/${form.value.projectTypeId}/prefill${q}`,
    )
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
    prefillSnapshot.value = JSON.stringify(lines.value)
    const missing = r.items.filter((i) => i.unit_cost == null).length
    const from =
      r.source === 'CUSTOMER' ? '已按该客户的报价模板带出配置和协议价' : '已按项目类型的标准配置带出配置和售价'
    prefillNote.value =
      from +
      (!seeCost
        ? form.value.supplierId
          ? '；成本由系统按该供应商的成本模板核算。'
          : '；请选择供应商，系统按其成本模板核算成本。'
        : !form.value.supplierId
          ? '；选择供应商后带出成本。'
          : missing
            ? `，并带出供应商成本；${missing} 项该供应商模板未报成本，请补充。`
            : '，并带出供应商成本，可逐行修改。')
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
  return p ? `${p.scenario} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}` : ''
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
            }
          : null,
      projectTypeId: form.value.projectTypeId || null,
      supplierId: form.value.supplierId || null,
      taxBasis: form.value.taxBasis,
      taxRate: form.value.taxBasis === 'UNKNOWN' ? null : form.value.taxPercent / 100,
      validUntil: form.value.validUntil,
      note: form.value.note,
      templateId: form.value.templateId || null,
      companyId: form.value.companyId || null,
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
          <CitySelect v-model="store.city" placeholder="留空按门店名称识别" />
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
        <el-form-item label="合同号">
          <el-input disabled placeholder="客户确认、生成合同时自动编号" />
        </el-form-item>
      </div>
      <div v-else class="step-grid">
        <el-form-item label="报价项目" required class="span-2">
          <el-select v-model="form.projectId" filterable placeholder="选择门店 / 项目" :disabled="!!seed">
            <el-option
              v-for="p in quotableProjects"
              :key="p.id"
              :value="p.id"
              :label="`${p.name} · ${p.customer_name}${p.city ? ' · ' + p.city : ''}${signed(p) ? ' · 已签合同' : ''}`"
            />
          </el-select>
          <small class="muted pick-hint">
            只列出还没有签合同的门店（{{ quotableProjects.length }} 个）。已签合同的
            {{ hiddenSigned }} 个门店改报价属于合同变更。
            <el-checkbox v-if="canApprove() && !seed" v-model="includeSigned" size="small">
              显示已签合同的门店
            </el-checkbox>
          </small>
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

      <div class="reference-row">
        <span class="reference-label">参考已签约项目（可选）</span>
        <el-select
          v-model="referenceId"
          filterable
          clearable
          placeholder="选择一个已签合同的门店，带入它的产品、数量和售价"
        >
          <el-option
            v-for="p in referenceProjects"
            :key="p.id"
            :value="p.id"
            :label="`${p.name} · ${p.customer_name} · ${p.brand}${p.contract_no ? ' · ' + p.contract_no : ''}`"
          />
        </el-select>
        <el-button :disabled="!referenceId" :loading="referencing" @click="applyReference">带入产品与报价</el-button>
        <span v-if="!referenceProjects.length" class="muted">暂无已签约项目可参考</span>
      </div>

      <div class="step-head">
        <span class="step-no">2</span>
        供应商与报价条件
      </div>
      <div class="step-grid four">
        <el-form-item :label="seeCost ? '供应商（带出成本）' : '供应商'">
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
        <el-form-item label="报价单版式（抬头与条款）">
          <el-select v-model="form.templateId" placeholder="默认版式">
            <el-option
              v-for="t in templates"
              :key="t.id"
              :value="t.id"
              :label="`${t.name}${t.is_default ? '（默认）' : ''}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="我方签约主体">
          <el-select v-model="form.companyId" placeholder="默认签约主体">
            <el-option
              v-for="c in activeCompanies"
              :key="c.id"
              :value="c.id"
              :label="`${companyLabel(c)}${c.is_default ? '（默认）' : ''}`"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="step-head">
        <span class="step-no">3</span>
        {{ seeCost ? '报价明细与毛利' : '报价明细' }}
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
        <el-table-column v-if="seeCost" label="材料成本" width="116">
          <template #default="{ row }">
            <el-input-number v-model="row.materialCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="人工成本" width="104">
          <template #default="{ row }">
            <el-input-number v-model="row.laborCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="财务成本" width="104">
          <template #default="{ row }">
            <el-input-number v-model="row.financeCost" :min="0" :precision="2" :controls="false" placeholder="—" />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="单台成本" width="104" align="right">
          <template #default="{ row }">
            <span :class="unitCost(row) == null ? 'muted' : ''">
              {{ unitCost(row) == null ? '待补充' : money(unitCost(row)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="销售小计" width="112" align="right">
          <template #default="{ row }">{{ lineAmount(row) == null ? '待补充' : money(lineAmount(row)) }}</template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="毛利率" width="84" align="right">
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
        <div v-if="seeCost">
          <span>材料成本</span>
          <strong>{{ money(part('materialCost')) }}</strong>
        </div>
        <div v-if="seeCost">
          <span>人工成本</span>
          <strong>{{ money(part('laborCost')) }}</strong>
        </div>
        <div v-if="seeCost">
          <span>财务成本</span>
          <strong>{{ money(part('financeCost')) }}</strong>
        </div>
        <div v-if="seeCost">
          <span>成本合计</span>
          <strong>{{ cost == null ? '待补充' : money(cost) }}</strong>
        </div>
        <div v-if="seeCost">
          <span>预计毛利</span>
          <strong :class="profit != null && profit < 0 ? 'danger-text' : ''">
            {{ profit == null ? '待补充' : money(profit) }}
          </strong>
        </div>
        <div v-if="seeCost" class="mb-main">
          <span>毛利率</span>
          <strong :class="margin != null && margin < 0 ? 'danger-text' : ''">
            {{ margin == null ? '待补充' : percent(margin) }}
          </strong>
        </div>
      </div>
      <p v-if="seeCost && !costComplete" class="muted cost-hint">有明细未填成本，毛利与毛利率暂不计算。</p>

      <el-form-item label="对客备注（本单）">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="2"
          maxlength="2000"
          placeholder="本单特别说明，例如交货周期、运输范围。通用条款请维护在报价单版式中。请勿填写内部成本。"
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
.reference-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 14px;
  padding: 8px 12px;
  border: 1px dashed #c9d3c2;
  border-radius: 8px;
  font-size: 13px;
}
.reference-label {
  flex: none;
  color: #33463e;
}
.reference-row .el-select {
  flex: 1;
  max-width: 560px;
}
.pick-hint {
  display: block;
  line-height: 1.6;
}
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
