<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, post } from '../api'
import type { Contract, Partner } from '../types'
import { day, money, today } from '../utils'
import { matchCustomer, parseReceipt } from '../parsing/receipt'

/**
 * 登记回款：一笔到账可以分到同一客户的多份合同。
 * 把银行短信或回单上的文字贴进来，自动认出金额、日期、付款方并对到客户；
 * 系统给出分配建议（对发票 / 正好凑齐 / 从最早到期的开始），人来确认或调整。分不完的余额留作待分配。
 */
export interface ReceiptPreset {
  customerId?: string
  /** 从某份合同打开时预先把金额记到这份合同上。 */
  contractKey?: string
  /** 继续分配一笔已登记回款的余额。 */
  batch?: { id: string; reference: string; unallocated: number; paid_on: string; customer_id: string }
}
const props = defineProps<{ modelValue: boolean; contracts: Contract[]; preset?: ReceiptPreset }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const RULE_NOTE: Record<string, string> = {
  INVOICE: '金额正好等于一张发票，已记到那张发票的合同。',
  EXACT: '金额正好等于这几份合同的未收之和。',
  OLDEST: '没有正好对上的，已从最早到期的合同开始依次冲抵。',
}
const customers = ref<Partner[]>([]),
  pasted = ref(''),
  pasteNote = ref(''),
  ruleNote = ref(''),
  busy = ref(false),
  suggesting = ref(false)
const form = ref({
  customerId: '',
  amount: undefined as number | undefined,
  paidOn: today(),
  reference: '',
  payer: '',
  note: '',
})
/** 系统建议分到的合同，排在表格最前面，不用在一长串合同里找。 */
const suggested = ref<string[]>([])
/** 各合同本次分配的金额（合同号 → 金额）。 */
const shares = ref<Record<string, number | undefined>>({})

const batch = computed(() => props.preset?.batch)
/** 这位客户还有未收的合同，最早到期的在前。 */
const open = computed(() => {
  const order = (c: Contract) =>
    `${suggested.value.includes(c.contract_key) ? 0 : 1}${c.due_date || '9999'}${c.signed_on || c.order_date || ''}`
  return props.contracts
    .filter((c) => c.customer_id === form.value.customerId && Number(c.outstanding) > 0)
    .sort((a, b) => order(a).localeCompare(order(b)))
})
const total = computed(() => (batch.value ? batch.value.unallocated : form.value.amount || 0))
const allocated = computed(() => open.value.reduce((s, c) => s + (shares.value[c.contract_key] || 0), 0))
const left = computed(() => Math.round((total.value - allocated.value) * 100) / 100)

watch(
  () => props.modelValue,
  async (isOpen) => {
    if (!isOpen) return
    pasted.value = pasteNote.value = ruleNote.value = ''
    shares.value = {}
    suggested.value = []
    const p = props.preset
    form.value = {
      customerId: p?.batch?.customer_id || p?.customerId || '',
      amount: undefined,
      paidOn: p?.batch ? day(p.batch.paid_on) : today(),
      reference: '',
      payer: '',
      note: '',
    }
    if (p?.contractKey) {
      // 从合同里打开：默认收这份合同的全部未收
      const c = props.contracts.find((x) => x.customer_id === p.customerId && x.contract_key === p.contractKey)
      if (c) {
        form.value.amount = Number(c.outstanding)
        shares.value = { [c.contract_key]: Number(c.outstanding) }
      }
    }
    if (!customers.value.length)
      customers.value = (await api<Partner[]>('/partners').catch(() => [])).filter((x) => x.kind === 'CUSTOMER')
    if (p?.batch) await suggest()
  },
)

/** 粘贴识别：只覆盖认出来的字段。 */
watch(pasted, async (text) => {
  if (!text.trim()) return (pasteNote.value = '')
  const p = parseReceipt(text)
  const found: string[] = []
  if (p.amount) ((form.value.amount = p.amount), found.push(`金额 ${money(p.amount)}`))
  if (p.paidOn) ((form.value.paidOn = p.paidOn), found.push(`日期 ${p.paidOn}`))
  if (p.payer) ((form.value.payer = p.payer), found.push(`付款方 ${p.payer}`))
  if (p.reference) ((form.value.reference = p.reference), found.push('流水号'))
  const customer = matchCustomer(p.payer || text, customers.value)
  if (customer) {
    form.value.customerId = customer.id
    found.push(`客户 ${customer.name}`)
  }
  pasteNote.value = found.length
    ? `已认出：${found.join('，')}。${customer ? '' : '没有对上客户，请选择。'}`
    : '没有认出金额或付款方，请手工填写。'
  if (p.amount && form.value.customerId) await suggest()
})

/** 向系统要分配建议并填进表里。 */
async function suggest() {
  if (!form.value.customerId || !total.value) return ElMessage.warning('请先选择客户并填写金额')
  suggesting.value = true
  try {
    const q = new URLSearchParams({ customerId: form.value.customerId, amount: String(total.value) })
    const s = await api<{ rule: string; allocations: { contractKey: string; amount: number }[] }>(
      `/receipt-batches/suggest?${q}`,
    )
    shares.value = Object.fromEntries(s.allocations.map((a) => [a.contractKey, Number(a.amount)]))
    suggested.value = s.allocations.map((a) => a.contractKey)
    ruleNote.value = s.allocations.length ? RULE_NOTE[s.rule] : '这位客户没有未收的合同，这笔钱会全部留作待分配。'
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
  if (!batch.value && (!f.customerId || !f.amount || !f.paidOn)) return ElMessage.warning('请填写客户、金额和回款日期')
  if (left.value < 0) return ElMessage.warning(`分到合同的金额比到账金额多 ${money(-left.value)}`)
  const allocations = open.value
    .filter((c) => (shares.value[c.contract_key] || 0) > 0)
    .map((c) => ({ contractKey: c.contract_key, amount: shares.value[c.contract_key] }))
  if (batch.value && !allocations.length) return ElMessage.warning('请至少给一份合同分配金额')
  busy.value = true
  try {
    const r = batch.value
      ? await post<{ unallocated: number }>(`/receipt-batches/${batch.value.id}/allocate`, { allocations })
      : await post<{ unallocated: number; reference: string }>('/receipt-batches', {
          customerId: f.customerId,
          amount: f.amount,
          paidOn: f.paidOn,
          reference: f.reference,
          payer: f.payer,
          note: f.note,
          sourceText: pasted.value.slice(0, 4000),
          allocations,
        })
    emit('update:modelValue', false)
    emit('saved')
    ElMessage.success(
      `已登记，分到 ${allocations.length} 份合同` +
        (Number(r.unallocated) > 0 ? `；还有 ${money(r.unallocated)} 待分配，可在回款流水里继续分` : ''),
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
    :title="batch ? `分配回款余额 · ${batch.reference}` : '登记回款'"
    width="min(860px, 96vw)"
    top="3vh"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <template v-if="!batch">
        <el-input
          v-model="pasted"
          type="textarea"
          :rows="2"
          resize="none"
          class="paste-box"
          placeholder="粘贴识别：把银行到账短信或回单上的文字贴到这里，自动填金额、日期、付款方并对到客户。"
        />
        <div v-if="pasteNote" class="paste-note">{{ pasteNote }}</div>
        <div class="entry-grid">
          <el-form-item label="客户" required>
            <el-select v-model="form.customerId" filterable placeholder="选择客户" @change="changeCustomer">
              <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="到账金额（元）" required>
            <el-input-number v-model="form.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="回款日期" required>
            <el-date-picker v-model="form.paidOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="付款方">
            <el-input v-model="form.payer" maxlength="200" placeholder="短信或回单上的付款单位" />
          </el-form-item>
          <el-form-item label="银行流水号">
            <el-input v-model="form.reference" maxlength="100" placeholder="没有可不填，系统自动编号" />
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" maxlength="1000" /></el-form-item>
        </div>
      </template>
      <p v-else class="muted lead">这笔回款还有 {{ money(batch.unallocated) }} 没有分到合同上。</p>

      <div class="alloc-head">
        <strong>分到合同</strong>
        <span class="muted">{{ ruleNote || '填好客户和金额后，点“按建议分配”，或直接在下面填每份合同的金额。' }}</span>
        <el-button size="small" :loading="suggesting" :disabled="!form.customerId || !total" @click="suggest">
          按建议分配
        </el-button>
      </div>
      <el-table :data="open" size="small" class="editor-table" max-height="216" empty-text="这位客户没有未收的合同">
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
        <el-table-column label="应收截止日" width="108">
          <template #default="{ row }">
            <span :class="row.overdue > 0 ? 'danger-text' : row.due_date ? '' : 'muted'">
              {{ row.due_date ? day(row.due_date) : '未设' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="未收" width="118" align="right">
          <template #default="{ row }">{{ money(row.outstanding) }}</template>
        </el-table-column>
        <el-table-column label="本次分配（元）" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="shares[row.contract_key]"
              :min="0"
              :max="Number(row.outstanding)"
              :precision="2"
              :controls="false"
              placeholder="—"
            />
          </template>
        </el-table-column>
        <el-table-column width="64" align="center">
          <template #default="{ row }">
            <!-- 一键收这份合同的全部未收（不超过还没分完的金额） -->
            <el-button
              link
              type="primary"
              @click="
                shares[row.contract_key] = Math.min(
                  Number(row.outstanding),
                  Math.round((left + (shares[row.contract_key] || 0)) * 100) / 100,
                )
              "
            >
              收齐
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div :class="['alloc-foot', { over: left < 0 }]">
        {{ batch ? '待分配' : '到账' }} {{ money(total) }} · 已分 {{ money(allocated) }} ·
        <strong>
          {{ left < 0 ? `多分了 ${money(-left)}` : left > 0 ? `还有 ${money(left)} 待分配` : '正好分完' }}
        </strong>
        <span v-if="left > 0 && !batch" class="muted">（可以先登记，余额以后在回款流水里继续分）</span>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="left < 0" @click="save">
        {{ batch ? '确认分配' : '登记回款' }}
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.paste-box {
  margin-bottom: 6px;
}
.paste-box :deep(textarea) {
  background: #f8faf7;
}
.paste-note {
  margin-bottom: 10px;
  font-size: 13px;
  color: #24634f;
}
.entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.lead {
  margin-bottom: 12px;
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
  margin-top: 10px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.alloc-foot.over strong {
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
}
</style>
