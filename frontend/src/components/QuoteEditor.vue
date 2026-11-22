<script setup lang="ts">
import { ref, computed, watch, nextTick, onBeforeUnmount, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete, ArrowDown } from '@element-plus/icons-vue'
import { api, canApprove, canSeeCost, post, put, upload } from '../api'
import CitySelect from './CitySelect.vue'
import ConfigListDialog from './ConfigListDialog.vue'
import {
  displayRows,
  groupOf,
  groupPayload,
  linesOfBundle,
  nextGroupNo,
  rowClass,
  setSets,
  spanGroups,
  syncPerSet,
  type DisplayRow,
  type GroupDraft,
} from '../bundles'
import type { Bundle } from '../types'
import { productLabel as labelOf } from '../products'
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
import { afterDays, day, money, percent, today } from '../utils'

const props = defineProps<{
  modelValue: boolean
  projects: Project[]
  products: Product[]
  projectId?: string
  /** 带入的报价：默认是“复制为新版本”；edit 为真时是修改这份草稿本身。 */
  seed?: Quote
  edit?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [string]; 'products-changed': [] }>()

interface LineDraft {
  productId: string
  quantity: number
  unitPrice: number | undefined
  materialCost: number | undefined
  laborCost: number | undefined
  financeCost: number | undefined
  /** 属于哪个组合；不在组合里时为空。 */
  group?: GroupDraft
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
/** 选了参考项目先看它的清单（按组合分组、规格、金额），确认了再带入。 */
const referencePreview = ref<Quote>()
watch(referenceId, async (id) => {
  referencePreview.value = undefined
  if (!id) return
  try {
    const q = await api<Quote>(`/quotes/reference?projectId=${encodeURIComponent(id)}`)
    if (referenceId.value === id) referencePreview.value = q
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})
const referenceRows = computed(() =>
  referencePreview.value
    ? displayRows(referencePreview.value.items.map((i) => ({ ...i, quantity: Number(i.quantity), group: groupOf(i) })))
    : [],
)
const sum = (xs: { amount: number | null }[]) => xs.reduce((s, x) => s + Number(x.amount || 0), 0)
async function applyReference() {
  if (!referenceId.value) return
  referencing.value = true
  try {
    const q =
      referencePreview.value ??
      (await api<Quote>(`/quotes/reference?projectId=${encodeURIComponent(referenceId.value)}`))
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
        group: groupOf(i),
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
    api<Bundle[]>('/bundles')
      .then((r) => (bundles.value = r.filter((b) => b.active)))
      .catch(() => {})
    const project = props.projects.find((p) => p.id === fromProject)
    form.value = {
      projectId: fromProject,
      projectTypeId: props.seed?.project_type_id || project?.project_type_id || '',
      supplierId: props.seed?.supplier_id || '',
      taxBasis: props.seed?.tax_basis || 'INCLUDED',
      taxPercent: props.seed ? (props.seed.tax_rate || 0) * 100 : 13,
      // 修改草稿时保留原有效期（已过期的顺延）；复制为新版本时重新起算
      validUntil:
        props.edit && props.seed?.valid_until && day(props.seed.valid_until) >= today()
          ? day(props.seed.valid_until)
          : afterDays(30),
      note: props.seed?.note || '',
      templateId:
        (props.edit && templates.value.find((t) => t.id === props.seed?.template_id)?.id) ||
        templates.value.find((t) => t.is_default)?.id ||
        '',
      companyId: props.seed?.company_id || defaultCompany.value?.id || '',
    }
    prefillNote.value = ''
    prefillSnapshot.value = ''
    configFile.value = undefined
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
          group: groupOf(i),
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
      group: groupOf(i),
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
  return p ? labelOf(p) : ''
}
function selectProduct(line: LineDraft) {
  const p = props.products.find((p) => p.id === line.productId)
  line.unitPrice = p?.reference_price == null ? undefined : Number(p.reference_price)
  // 成本先取这家供应商的采购成本，没有填时取最近一次报价的成本
  const cost = p?.purchase_cost ?? p?.reference_cost
  line.materialCost = cost == null ? undefined : Number(cost)
  line.laborCost = undefined
  line.financeCost = undefined
}

// ---------- 布局：弹窗一屏显示，产品明细多时只在明细表内滚动 ----------
const tableRef = ref<{ $el: HTMLElement; setScrollTop: (top: number) => void }>()
const tableMax = ref(Math.max(180, window.innerHeight - 470))
/** 明细表可用高度 = 窗口高度 − 弹窗里明细表以外的部分 − 上下留白。 */
function fit() {
  const dialog = document.querySelector<HTMLElement>('.quote-editor')
  const table = tableRef.value?.$el
  if (!dialog || !table) return
  const chrome = dialog.offsetHeight - table.offsetHeight
  // 上方留白 2.5vh（弹窗 top），下方留白 16px（见文件末尾的弹窗外框样式）
  tableMax.value = Math.max(150, Math.floor(window.innerHeight * 0.975 - 18 - chrome))
}
onMounted(() => window.addEventListener('resize', fit))
onBeforeUnmount(() => window.removeEventListener('resize', fit))
watch([mode, prefillNote], () => nextTick(fit))
// ---------- 按客户的配置清单带入 ----------
const configOpen = ref(false),
  /** 带入时用的清单文件；保存报价后存到门店附件里。 */
  configFile = ref<File>(),
  /** 当前项目类型（客户模板）里的产品，清单里同名同规格有几个时优先选它们。 */
  preferredProducts = ref<string[]>([])
/** 当前客户 / 项目类型 / 供应商对应的模板明细（没选项目类型时为空）。 */
async function templateLines(): Promise<PrefillLine[]> {
  if (!form.value.projectTypeId) return []
  const params = new URLSearchParams()
  if (form.value.supplierId) params.set('supplierId', form.value.supplierId)
  if (customerId.value) params.set('customerId', customerId.value)
  const q = params.size ? `?${params}` : ''
  return (
    await api<{ items: PrefillLine[] }>(`/project-types/${form.value.projectTypeId}/prefill${q}`).catch(() => ({
      items: [] as PrefillLine[],
    }))
  ).items
}
async function openConfig() {
  preferredProducts.value = (await templateLines()).map((i) => i.product_id)
  configOpen.value = true
}
/**
 * 清单里的产品和数量替换当前明细。售价、成本按当前的客户报价模板 / 项目类型标准配置 / 供应商成本模板取，
 * 模板里没有的产品取它最近一次的报价，都没有的留空由人填。
 */
async function applyConfig(r: { lines: { productId: string; quantity: number }[]; file: File }) {
  configFile.value = r.file
  const added: LineDraft[] = r.lines.map((l) => ({ ...blankLine(), productId: l.productId, quantity: l.quantity }))
  await priceLines(added)
  lines.value = added
  // 明细已按清单改过：之后换客户、项目类型不再自动按模板覆盖
  prefillSnapshot.value = ''
  const noPrice = lines.value.filter((l) => l.unitPrice == null).length
  prefillNote.value =
    `已按客户配置清单「${r.file.name}」带入 ${lines.value.length} 项` +
    (noPrice ? `，${noPrice} 项没有参考售价，请填写` : '，售价按模板或最近一次报价带出，请核对') +
    '。清单会在保存后存到门店附件里。'
}
/** 保存报价后把配置清单存到门店附件；同一个文件已存过时不重复存。 */
async function keepConfigFile(quoteId: string) {
  if (!configFile.value) return
  try {
    const projectId =
      mode.value === 'existing'
        ? form.value.projectId
        : (await api<{ project_id: string }>(`/quotes/${quoteId}`)).project_id
    const data = new FormData()
    data.append('category', 'CONFIG_LIST')
    data.append('file', configFile.value)
    await upload(`/projects/${projectId}/attachments`, data)
  } catch (e) {
    const msg = (e as Error).message
    if (!msg.includes('重复')) ElMessage.warning(`报价已保存，但配置清单没有存进门店附件：${msg}`)
  }
}

// ---------- 标准组合：整组加入，套数联动 ----------
const bundles = ref<Bundle[]>([])
/** 新加入的行按客户模板 / 项目类型配置 / 供应商成本模板取价，模板里没有的取最近一次报价。 */
async function priceLines(newLines: LineDraft[]) {
  const prices = new Map<string, PrefillLine>()
  for (const i of await templateLines()) prices.set(i.product_id, i)
  const n = (v: number | null | undefined) => (v == null ? undefined : Number(v))
  for (const line of newLines) {
    const p = prices.get(line.productId)
    if (p) {
      line.unitPrice = n(p.unit_price)
      line.materialCost = n(p.material_cost)
      line.laborCost = n(p.labor_cost)
      line.financeCost = n(p.finance_cost)
    } else selectProduct(line)
  }
}
async function addBundle(id: string) {
  const b = bundles.value.find((x) => x.id === id)
  if (!b) return
  const added: LineDraft[] = linesOfBundle(b, nextGroupNo(lines.value)).map((l) => ({ ...blankLine(), ...l }))
  await priceLines(added)
  // 去掉还没选产品的空行
  lines.value = [...lines.value.filter((l) => l.productId), ...added]
  prefillSnapshot.value = ''
  await nextTick()
  tableRef.value?.setScrollTop(1e6)
}
const rows = computed(() => displayRows(lines.value))
const spanMethod = spanGroups(() => (canSeeCost() ? 10 : 5))
function removeRow(row: DisplayRow<LineDraft>) {
  if (row.kind === 'group') lines.value = lines.value.filter((l) => l.group?.no !== row.no)
  else lines.value.splice(lines.value.indexOf(row.line), 1)
}
const changeSets = (no: number, v: number | undefined) => setSets(lines.value, no, v)
const groupTotal = (ls: LineDraft[]) => ls.reduce((s, l) => s + (lineAmount(l) || 0), 0)

async function addLine() {
  lines.value.push(blankLine())
  await nextTick()
  tableRef.value?.setScrollTop(1e6)
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
    const body = {
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
        group: groupPayload(l.group),
      })),
    }
    let id: string
    if (props.edit && props.seed) {
      await put(`/quotes/${props.seed.id}`, body)
      id = props.seed.id
    } else id = (await post<{ id: string }>('/quotes', body)).id
    await keepConfigFile(id)
    emit('update:modelValue', false)
    emit('saved', id)
    ElMessage.success(
      props.edit ? '草稿已保存' : mode.value === 'new' ? '已登记门店并保存报价草稿' : '已保存新的报价版本',
    )
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
    class="quote-editor"
    width="min(1360px, 97vw)"
    top="2.5vh"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="fit"
  >
    <template #header>
      <div class="qe-head">
        <span class="el-dialog__title">{{ edit && seed ? `修改报价草稿 · V${seed.version}` : '创建客户报价' }}</span>
        <el-radio-group v-model="mode" size="small" :disabled="!!seed">
          <el-radio-button value="new">新门店</el-radio-button>
          <el-radio-button value="existing">已登记项目</el-radio-button>
        </el-radio-group>
        <span class="muted qe-tip">合同号在客户确认、生成合同时自动编号</span>
      </div>
    </template>
    <el-form label-position="top" class="quote-form">
      <!-- 门店、供应商与报价条件：两行六列 -->
      <div class="qe-grid">
        <template v-if="mode === 'new'">
          <el-form-item label="客户" required>
            <el-select v-model="store.customerId" filterable default-first-option placeholder="选择客户">
              <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="项目类型" required>
            <el-select v-model="form.projectTypeId" placeholder="如：小象前置仓" @change="onTypeChange">
              <el-option v-for="t in activeTypes" :key="t.id" :value="t.id" :label="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="门店名称" required class="span-2">
            <el-input v-model="store.name" maxlength="300" placeholder="例如：小象天津丰泽站" />
          </el-form-item>
          <el-form-item label="城市">
            <CitySelect v-model="store.city" placeholder="留空按名称识别" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item required class="span-3">
            <template #label>
              报价项目
              <span class="muted label-extra" :title="`已签合同的 ${hiddenSigned} 个门店改报价属于合同变更`">
                只列未签合同的门店（{{ quotableProjects.length }} 个）
              </span>
              <el-checkbox v-if="canApprove() && !seed" v-model="includeSigned" size="small" class="label-check">
                显示已签合同的
              </el-checkbox>
            </template>
            <el-select v-model="form.projectId" filterable placeholder="选择门店 / 项目" :disabled="!!seed">
              <el-option
                v-for="p in quotableProjects"
                :key="p.id"
                :value="p.id"
                :label="`${p.name} · ${p.customer_name}${p.city ? ' · ' + p.city : ''}${signed(p) ? ' · 已签合同' : ''}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="项目类型">
            <el-select v-model="form.projectTypeId" clearable placeholder="选择后可带出模板" @change="onTypeChange">
              <el-option v-for="t in activeTypes" :key="t.id" :value="t.id" :label="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="门店信息">
            <span v-if="currentProject" class="muted store-info" :title="currentProject.contract_no || ''">
              {{ currentProject.customer_name }} · {{ currentProject.city || '未填城市' }}
              {{ currentProject.contract_no ? ` · ${currentProject.contract_no}` : '' }}
            </span>
            <span v-else class="muted store-info">—</span>
          </el-form-item>
        </template>
        <el-form-item :label="seeCost ? '供应商（带出成本）' : '供应商'">
          <el-select
            v-model="form.supplierId"
            clearable
            :disabled="!form.projectTypeId"
            :placeholder="!form.projectTypeId ? '先选项目类型' : suppliers.length ? '选择供应商' : '该类型暂无成本模板'"
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
        <el-form-item label="报价单版式">
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
        <el-form-item label="参考已签约项目（可选）" class="span-2">
          <div class="reference-pair">
            <el-select
              v-model="referenceId"
              filterable
              clearable
              :placeholder="referenceProjects.length ? '选一个已签合同的门店' : '暂无已签约项目可参考'"
            >
              <el-option
                v-for="p in referenceProjects"
                :key="p.id"
                :value="p.id"
                :label="`${p.name} · ${p.customer_name} · ${p.brand}${p.contract_no ? ' · ' + p.contract_no : ''}`"
              />
            </el-select>
            <el-button :disabled="!referenceId" :loading="referencing" @click="applyReference">带入</el-button>
          </div>
          <div v-if="referencePreview" class="reference-preview">
            <div class="rp-head">
              <strong>{{ referencePreview.project_name }}</strong>
              <span class="muted">
                {{ referencePreview.contract_no || '合同' }} · V{{ referencePreview.version }} ·
                {{ referencePreview.items.length }} 项
              </span>
              <span class="rp-total">合计 {{ money(referencePreview.amount) }}</span>
            </div>
            <ul>
              <li v-for="r in referenceRows" :key="r.key" :class="r.kind">
                <template v-if="r.kind === 'group'">
                  <span>组合 · {{ r.name }} × {{ r.sets }} 套</span>
                  <span>{{ money(sum(r.lines)) }}</span>
                </template>
                <template v-else>
                  <span :class="{ indent: r.line.group }">
                    {{ r.line.name }} {{ r.line.specification }} × {{ Number(r.line.quantity) }}
                  </span>
                  <span>{{ money(r.line.amount) }}</span>
                </template>
              </li>
            </ul>
          </div>
        </el-form-item>
      </div>

      <!-- 报价明细：产品多时只在表内滚动 -->
      <div class="qe-detail-head">
        <strong>{{ seeCost ? '报价明细与毛利' : '报价明细' }}</strong>
        <span class="muted qe-note" :title="prefillNote">{{ prefillNote }}</span>
        <el-button v-if="form.projectTypeId" size="small" link type="primary" :loading="filling" @click="applyTemplate">
          按模板重新带出
        </el-button>
        <el-button size="small" @click="openConfig">按客户配置清单带入</el-button>
        <el-dropdown trigger="click" @command="addBundle">
          <el-button size="small">
            添加组合
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="!bundles.length" disabled>
                还没有标准组合，可在“基础资料 → 标准组合”里建
              </el-dropdown-item>
              <el-dropdown-item v-for="b in bundles" :key="b.id" :command="b.id">
                <span class="bundle-option">
                  {{ b.scenario }} / {{ b.name }}
                  <small>{{ b.supplier_name || '未指定供应商' }} · {{ b.items.length }} 种标准品</small>
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button :icon="Plus" size="small" @click="addLine">添加产品</el-button>
      </div>
      <el-table
        ref="tableRef"
        :data="rows"
        row-key="key"
        :span-method="spanMethod"
        :row-class-name="rowClass"
        class="editor-table qe-table"
        size="small"
        :max-height="tableMax"
      >
        <el-table-column label="产品 / 规格" min-width="230">
          <template #default="{ row }">
            <div v-if="row.kind === 'group'" class="group-head">
              <span class="group-name">组合 · {{ row.name }}</span>
              <span class="group-sets">
                <el-input-number
                  :model-value="row.sets"
                  :min="1"
                  :precision="0"
                  :controls="false"
                  size="small"
                  @change="(v: number | undefined) => changeSets(row.no, v)"
                />
                套
              </span>
              <span class="muted">{{ row.lines.length }} 种标准品 · 小计 {{ money(groupTotal(row.lines)) }}</span>
              <el-button link type="danger" size="small" @click="removeRow(row)">移除组合</el-button>
            </div>
            <el-select
              v-else
              v-model="row.line.productId"
              filterable
              :title="productLabel(row.line.productId)"
              placeholder="选择标准产品"
              @change="selectProduct(row.line)"
            >
              <el-option v-for="p in products" :key="p.id" :value="p.id" :label="productLabel(p.id)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="86">
          <template #default="{ row }">
            <el-input-number
              v-if="row.kind === 'line'"
              v-model="row.line.quantity"
              :min="0.001"
              :max="999999"
              :precision="0"
              :controls="false"
              @change="syncPerSet(row.line)"
            />
          </template>
        </el-table-column>
        <el-table-column label="销售单价" width="124">
          <template #default="{ row }">
            <el-input-number
              v-if="row.kind === 'line'"
              v-model="row.line.unitPrice"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="必填"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="材料成本" width="116">
          <template #default="{ row }">
            <el-input-number
              v-if="row.kind === 'line'"
              v-model="row.line.materialCost"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="—"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="人工成本" width="104">
          <template #default="{ row }">
            <el-input-number
              v-if="row.kind === 'line'"
              v-model="row.line.laborCost"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="—"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="财务成本" width="104">
          <template #default="{ row }">
            <el-input-number
              v-if="row.kind === 'line'"
              v-model="row.line.financeCost"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="—"
            />
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="单台成本" width="104" align="right">
          <template #default="{ row }">
            <span v-if="row.kind === 'line'" :class="unitCost(row.line) == null ? 'muted' : ''">
              {{ unitCost(row.line) == null ? '待补充' : money(unitCost(row.line)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="销售小计" width="112" align="right">
          <template #default="{ row }">
            <template v-if="row.kind === 'line'">
              {{ lineAmount(row.line) == null ? '待补充' : money(lineAmount(row.line)) }}
            </template>
          </template>
        </el-table-column>
        <el-table-column v-if="seeCost" label="毛利率" width="84" align="right">
          <template #default="{ row }">
            <span
              v-if="row.kind === 'line'"
              :class="lineMargin(row.line) != null && lineMargin(row.line)! < 0 ? 'danger-text' : ''"
            >
              {{ lineMargin(row.line) == null ? '—' : percent(lineMargin(row.line)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column width="48">
          <template #default="{ row }">
            <el-button
              v-if="row.kind === 'line'"
              :icon="Delete"
              link
              type="danger"
              aria-label="删除报价明细"
              @click="removeRow(row)"
            />
          </template>
        </el-table-column>
      </el-table>

      <div class="margin-board">
        <div class="mb-main">
          <span>报价合计</span>
          <strong>{{ money(total) }}</strong>
        </div>
        <template v-if="seeCost">
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
          <div :title="costComplete ? '' : '有明细未填成本，毛利与毛利率暂不计算'">
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
        </template>
      </div>

      <div class="qe-remark">
        <span>对客备注（本单）</span>
        <el-input
          v-model="form.note"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 2 }"
          maxlength="2000"
          placeholder="本单特别说明，如交货周期、运输范围；通用条款维护在报价单版式中。请勿填写内部成本。"
        />
      </div>
    </el-form>
    <template #footer>
      <span v-if="seeCost && !costComplete" class="muted qe-footnote">有明细未填成本，毛利与毛利率暂不计算。</span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">{{ edit ? '保存修改' : '保存报价草稿' }}</el-button>
    </template>
    <ConfigListDialog
      v-model="configOpen"
      :products="products"
      :preferred="preferredProducts"
      @apply="applyConfig"
      @product-added="emit('products-changed')"
    />
  </el-dialog>
</template>
<style scoped>
.qe-head {
  display: flex;
  align-items: center;
  gap: 16px;
}
.qe-tip {
  font-size: 13px;
}
.quote-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.quote-form :deep(.el-form-item__label) {
  margin-bottom: 2px;
  line-height: 20px;
  white-space: nowrap;
}
.qe-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 0 14px;
}
.span-2 {
  grid-column: span 2;
}
.span-3 {
  grid-column: span 3;
}
.label-extra {
  margin-left: 8px;
  font-size: 12px;
}
.label-check {
  margin-left: 10px;
  height: 20px;
  vertical-align: top;
}
.store-info {
  overflow: hidden;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.tax-pair {
  display: grid;
  grid-template-columns: 1fr 76px;
  gap: 6px;
  width: 100%;
}
.reference-pair {
  display: flex;
  gap: 8px;
  width: 100%;
}
.reference-pair .el-select {
  flex: 1;
  min-width: 0;
}
.qe-detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 6px;
  font-size: 14px;
  color: #253731;
}
.qe-note {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 明细表用紧凑行：一屏多放几行产品 */
.qe-table :deep(td.el-table__cell) {
  height: 40px;
  padding: 3px 0;
}
.qe-table :deep(th.el-table__cell) {
  height: 34px;
  padding: 4px 0;
}
.qe-table :deep(.cell) {
  padding: 0 6px;
  white-space: nowrap;
}
.qe-table :deep(.el-input__wrapper),
.qe-table :deep(.el-select__wrapper) {
  min-height: 30px;
}
.qe-table :deep(.el-input-number) {
  width: 100%;
  line-height: 28px;
}
.margin-board {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 1px;
  margin: 8px 0;
  border: 1px solid #e4eae0;
  border-radius: 6px;
  overflow: hidden;
  background: #e4eae0;
}
.margin-board > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 12px;
  background: #fafcf8;
}
.margin-board span {
  font-size: 12px;
  color: #5d6a63;
  white-space: nowrap;
}
.margin-board strong {
  font-size: 14px;
  font-weight: 500;
  color: #253731;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.margin-board .mb-main {
  background: #eef5ef;
}
.margin-board .mb-main strong {
  font-size: 16px;
}
.qe-remark {
  display: flex;
  align-items: center;
  gap: 12px;
}
.qe-remark > span {
  flex: none;
  font-size: 13px;
  color: #65745c;
}
.qe-footnote {
  margin-right: auto;
  font-size: 13px;
}
@media (max-width: 1000px) {
  .qe-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .span-3 {
    grid-column: span 2;
  }
  .margin-board {
    grid-template-columns: repeat(2, 1fr);
  }
  .qe-tip {
    display: none;
  }
}
</style>
<!-- 弹窗外框由 el-dialog 渲染，不带本组件的 scoped 标记，这里单独收紧留白 -->
<style>
.quote-editor.el-dialog {
  margin-bottom: 16px;
  padding: 16px 24px 14px;
}
.quote-editor .el-dialog__header {
  padding-bottom: 6px;
}
.quote-editor .el-dialog__body {
  padding-top: 8px;
}
.quote-editor .el-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 10px;
  padding-top: 12px;
}
.bundle-option {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  min-width: 220px;
}
.bundle-option small {
  color: var(--muted);
}
.reference-preview {
  width: 100%;
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid #e1e7de;
  border-radius: 8px;
  background: #f7f9f6;
  font-size: 13px;
}
.reference-preview .rp-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 4px;
}
.reference-preview .rp-total {
  margin-left: auto;
  font-weight: 600;
}
.reference-preview ul {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 150px;
  overflow: auto;
}
.reference-preview li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0;
  line-height: 1.4;
}
.reference-preview li.group {
  font-weight: 600;
  color: var(--ink);
}
.reference-preview .indent {
  padding-left: 16px;
}
</style>
