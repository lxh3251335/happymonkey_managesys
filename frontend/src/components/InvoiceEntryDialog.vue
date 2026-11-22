<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { api, upload } from '../api'
import type { Contract, Partner } from '../types'
import { day, invoiceTypeName, money, today } from '../utils'

/**
 * 登记发票：一张发票可以分到同一客户的多份合同。
 * 把电子发票 PDF 选进来（或拖进来），系统读出发票号码、日期、价税合计、购买方并对到客户，
 * 再给出分配建议（备注里写了合同号的优先 / 正好凑齐 / 从最早的合同开始）。原件自动存进各合同的附件。
 */
const props = defineProps<{
  modelValue: boolean
  contracts: Contract[]
  /** 从“粘贴登记”打开时带入的发票 PDF。 */
  initialFile?: File
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const RULE_NOTE: Record<string, string> = {
  REMARK: '发票备注里写了合同号，已记到这些合同。',
  EXACT: '金额正好等于这几份合同还没开票的金额之和。',
  OLDEST: '没有正好对上的，已从最早的合同开始依次记。',
}
interface Suggestion {
  rule: string
  allocations: { contractKey: string; amount: number }[]
}
interface Parsed {
  recognized: boolean
  invoiceNo: string | null
  issuedOn: string | null
  amount: number | null
  taxRate: number | null
  invoiceType: string | null
  buyerName: string | null
  sellerName: string | null
  remark: string | null
  customerId: string | null
  duplicate?: boolean
  suggestion?: Suggestion
}
const customers = ref<Partner[]>([]),
  file = ref<File>(),
  reading = ref(false),
  dragging = ref(false),
  fileNote = ref<{ text: string; warn: boolean } | null>(null),
  ruleNote = ref(''),
  remark = ref(''),
  busy = ref(false),
  suggesting = ref(false)
const fresh = () => ({
  customerId: '',
  invoiceNo: '',
  invoiceType: '',
  issuedOn: today(),
  amount: undefined as number | undefined,
  taxRate: undefined as number | undefined,
  note: '',
})
const form = ref(fresh())
const suggested = ref<string[]>([])
const shares = ref<Record<string, number | undefined>>({})

/** 这位客户还没开足票的合同；系统建议的排在前面。 */
const open = computed(() => {
  const order = (c: Contract) =>
    `${suggested.value.includes(c.contract_key) ? 0 : 1}${c.signed_on || c.order_date || '9999'}`
  return props.contracts
    .filter(
      (c) =>
        c.customer_id === form.value.customerId &&
        Number(c.uninvoiced) > 0 &&
        c.status !== 'DRAFT' &&
        c.status !== 'TERMINATED',
    )
    .sort((a, b) => order(a).localeCompare(order(b)))
})
const total = computed(() => form.value.amount || 0)
const allocated = computed(() => open.value.reduce((s, c) => s + (shares.value[c.contract_key] || 0), 0))
const left = computed(() => Math.round((total.value - allocated.value) * 100) / 100)

watch(
  () => props.modelValue,
  async (isOpen) => {
    if (!isOpen) return
    form.value = fresh()
    file.value = undefined
    fileNote.value = null
    ruleNote.value = remark.value = ''
    shares.value = {}
    suggested.value = []
    if (!customers.value.length)
      customers.value = (await api<Partner[]>('/partners').catch(() => [])).filter((x) => x.kind === 'CUSTOMER')
    if (props.initialFile) await read(props.initialFile)
  },
)

function applySuggestion(s?: Suggestion) {
  shares.value = Object.fromEntries((s?.allocations || []).map((a) => [a.contractKey, Number(a.amount)]))
  suggested.value = (s?.allocations || []).map((a) => a.contractKey)
  ruleNote.value = s?.allocations.length ? RULE_NOTE[s.rule] : ''
}

/** 读发票 PDF：只覆盖认出来的字段。 */
async function read(f?: File) {
  if (!f) return
  if (!/\.pdf$/i.test(f.name)) return ElMessage.warning('目前只能识别 PDF 格式的电子发票；图片或 OFD 请手工填写')
  file.value = f
  reading.value = true
  try {
    const data = new FormData()
    data.append('file', f)
    const p = await upload<Parsed>('/invoice-entries/parse', data)
    if (!p.recognized) {
      fileNote.value = {
        text: '这个 PDF 里没有读到发票文字（可能是扫描件），请手工填写；文件仍会作为原件保存。',
        warn: true,
      }
      return
    }
    const found: string[] = []
    if (p.invoiceNo) ((form.value.invoiceNo = p.invoiceNo), found.push(`号码 ${p.invoiceNo}`))
    if (p.issuedOn) ((form.value.issuedOn = p.issuedOn), found.push(`日期 ${p.issuedOn}`))
    if (p.amount != null) ((form.value.amount = Number(p.amount)), found.push(`价税合计 ${money(p.amount)}`))
    if (p.taxRate != null) form.value.taxRate = Math.round(Number(p.taxRate) * 10000) / 100
    if (p.invoiceType) form.value.invoiceType = p.invoiceType
    if (p.buyerName) found.push(`购买方 ${p.buyerName}`)
    remark.value = p.remark || ''
    if (p.customerId) form.value.customerId = p.customerId
    applySuggestion(p.suggestion)
    fileNote.value = {
      text:
        `已读出：${found.join('，')}。` +
        (p.customerId ? '' : '没有对上客户，请选择。') +
        (p.duplicate ? '这个发票号码已经登记过。' : ''),
      warn: !p.customerId || !!p.duplicate,
    }
  } catch (e) {
    fileNote.value = { text: (e as Error).message, warn: true }
  } finally {
    reading.value = false
  }
}
function onDrop(e: DragEvent) {
  dragging.value = false
  read(e.dataTransfer?.files?.[0])
}
function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  read(input.files?.[0])
  input.value = ''
}

async function suggest() {
  if (!form.value.customerId || !total.value) return ElMessage.warning('请先选择客户并填写价税合计')
  suggesting.value = true
  try {
    const q = new URLSearchParams({
      customerId: form.value.customerId,
      amount: String(total.value),
      hint: remark.value,
    })
    const s = await api<Suggestion>(`/invoice-entries/suggest?${q}`)
    applySuggestion(s)
    if (!s.allocations.length) ruleNote.value = '这位客户没有还没开足票的合同。'
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    suggesting.value = false
  }
}
function changeCustomer() {
  shares.value = {}
  suggested.value = []
  ruleNote.value = ''
}

async function save() {
  const f = form.value
  if (!f.customerId || !f.invoiceNo.trim() || !f.amount || !f.issuedOn)
    return ElMessage.warning('请填写客户、发票号码、开票日期和价税合计')
  if (left.value !== 0)
    return ElMessage.warning(
      left.value > 0 ? `还有 ${money(left.value)} 没有记到合同上` : `记到合同的金额比价税合计多 ${money(-left.value)}`,
    )
  const allocations = open.value
    .filter((c) => (shares.value[c.contract_key] || 0) > 0)
    .map((c) => ({ contractKey: c.contract_key, amount: shares.value[c.contract_key] }))
  busy.value = true
  try {
    const data = new FormData()
    data.append(
      'data',
      new Blob(
        [
          JSON.stringify({
            customerId: f.customerId,
            invoiceNo: f.invoiceNo.trim(),
            invoiceType: f.invoiceType,
            issuedOn: f.issuedOn,
            amount: f.amount,
            taxRate: f.taxRate == null ? null : f.taxRate / 100,
            note: f.note,
            allocations,
          }),
        ],
        { type: 'application/json' },
      ),
    )
    if (file.value) data.append('file', file.value)
    const r = await upload<{ contracts: number; attached: number }>('/invoice-entries', data)
    emit('update:modelValue', false)
    emit('saved')
    ElMessage.success(
      `发票已登记到 ${r.contracts} 份合同` + (r.attached ? `，原件已存入 ${r.attached} 份合同的附件` : ''),
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
    title="登记发票"
    width="min(860px, 96vw)"
    top="3vh"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <label
        :class="['drop', { on: dragging, has: file }]"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <input type="file" accept=".pdf,application/pdf" class="drop-input" @change="onPick" />
        <el-icon class="drop-icon"><UploadFilled /></el-icon>
        <span v-if="reading">正在读取发票…</span>
        <span v-else-if="file">{{ file.name }}（点这里可以换一个文件）</span>
        <span v-else>把电子发票 PDF 拖到这里，或点击选择文件，自动填好下面各项</span>
      </label>
      <div v-if="fileNote" :class="['file-note', { warn: fileNote.warn }]">{{ fileNote.text }}</div>
      <div class="entry-grid">
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable placeholder="选择客户" @change="changeCustomer">
            <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="发票号码" required>
          <el-input v-model="form.invoiceNo" maxlength="60" class="mono" />
        </el-form-item>
        <el-form-item label="开票日期" required>
          <el-date-picker v-model="form.issuedOn" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="价税合计（元）" required>
          <el-input-number v-model="form.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
        </el-form-item>
        <el-form-item label="税率（%）">
          <el-input-number v-model="form.taxRate" :min="0" :max="100" :precision="2" :controls="false" />
        </el-form-item>
        <el-form-item label="发票类型">
          <el-select v-model="form.invoiceType" clearable placeholder="按客户资料">
            <el-option v-for="(n, k) in invoiceTypeName" :key="k" :label="n" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" class="wide"><el-input v-model="form.note" maxlength="1000" /></el-form-item>
      </div>
      <div v-if="remark" class="muted remark">发票备注：{{ remark }}</div>

      <div class="alloc-head">
        <strong>记到合同</strong>
        <span class="muted">
          {{ ruleNote || '填好客户和价税合计后，点“按建议分配”，或直接在下面填每份合同的金额。' }}
        </span>
        <el-button size="small" :loading="suggesting" :disabled="!form.customerId || !total" @click="suggest">
          按建议分配
        </el-button>
      </div>
      <el-table
        :data="open"
        size="small"
        class="editor-table"
        max-height="164"
        empty-text="这位客户没有还没开足票的合同"
      >
        <el-table-column label="合同" min-width="250">
          <template #default="{ row }">
            <strong>{{ row.contract_no || '未填合同号' }}</strong>
            <small
              class="cell-secondary ellipsis"
              :title="row.title || row.items.map((i: any) => i.project_name).join('、')"
            >
              {{ row.title || row.items.map((i: any) => i.project_name).join('、') }}
            </small>
          </template>
        </el-table-column>
        <el-table-column label="签订 / 下单" width="108">
          <template #default="{ row }">{{ day(row.signed_on || row.order_date) }}</template>
        </el-table-column>
        <el-table-column label="未开票" width="118" align="right">
          <template #default="{ row }">{{ money(row.uninvoiced) }}</template>
        </el-table-column>
        <el-table-column label="本次开票（元）" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="shares[row.contract_key]"
              :min="0"
              :max="Number(row.uninvoiced)"
              :precision="2"
              :controls="false"
              placeholder="—"
            />
          </template>
        </el-table-column>
        <el-table-column width="64" align="center">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="
                shares[row.contract_key] = Math.min(
                  Number(row.uninvoiced),
                  Math.round((left + (shares[row.contract_key] || 0)) * 100) / 100,
                )
              "
            >
              开足
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div :class="['alloc-foot', { off: left !== 0 && total > 0 }]">
        价税合计 {{ money(total) }} · 已记 {{ money(allocated) }} ·
        <strong>
          {{
            left < 0
              ? `多记了 ${money(-left)}`
              : left > 0
                ? `还有 ${money(left)} 没有记到合同上`
                : total
                  ? '正好记完'
                  : '—'
          }}
        </strong>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!total || left !== 0" @click="save">登记发票</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.drop {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px dashed #b9c7bc;
  border-radius: 6px;
  background: #f8faf7;
  color: #33443b;
  font-size: 13px;
  cursor: pointer;
}
.drop.on {
  border-color: #24634f;
  background: #edf4ef;
}
.drop.has {
  border-style: solid;
}
.drop-input {
  display: none;
}
.drop-icon {
  font-size: 22px;
  color: #24634f;
}
.file-note {
  margin-top: 6px;
  font-size: 13px;
  color: #24634f;
}
.file-note.warn {
  color: #96670f;
}
/* 四列两行：客户、号码、日期、金额 / 税率、类型、备注（占两列） */
.entry-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 14px;
  margin-top: 10px;
}
.entry-grid .wide {
  grid-column: span 2;
}
.remark {
  margin-bottom: 8px;
  font-size: 13px;
}
.alloc-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 8px;
  font-size: 13px;
}
.alloc-head strong {
  font-size: 14px;
  flex: none;
}
.alloc-head .muted {
  flex: 1;
}
.alloc-foot {
  margin: 10px 0;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.alloc-foot.off strong {
  color: #a8432f;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
:deep(.el-input-number) {
  width: 100%;
}
@media (max-width: 760px) {
  .entry-grid {
    grid-template-columns: 1fr;
  }
  .entry-grid .wide {
    grid-column: auto;
  }
}
</style>
