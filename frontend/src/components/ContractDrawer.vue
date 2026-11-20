<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, canApprove, canFinance, canSeeCost, canWrite, post, session } from '../api'
import StatusTag from './StatusTag.vue'
import ContractInfoDialog from './ContractInfoDialog.vue'
import { changeStatusName, contractStatusName, costCategoryName, invoiceStatusName } from '../dictionaries'
import type { ContractChange, ContractCost, ContractDetail, ContractInvoice, Partner } from '../types'
import { actionName, dateTime, day, money, percent, today } from '../utils'
import { useRouter } from 'vue-router'

/** 合同详情：要素、执行（开票 / 回款 / 发货 / 成本）、变更审批与全程操作记录。 */
const props = defineProps<{ modelValue: boolean; customerId: string; contractKey: string }>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  changed: []
  confirm: [ContractDetail]
  receipt: [ContractDetail]
  refund: [ContractDetail]
  attachments: [ContractDetail]
}>()

const c = ref<ContractDetail | null>(null),
  invoices = ref<ContractInvoice[]>([]),
  costs = ref<ContractCost[]>([]),
  suppliers = ref<Partner[]>([]),
  tab = ref('overview'),
  loading = ref(false),
  busy = ref(false),
  editOpen = ref(false)

const q = () =>
  `customerId=${encodeURIComponent(props.customerId)}&contractKey=${encodeURIComponent(props.contractKey)}`
async function load() {
  if (!props.customerId || !props.contractKey) return
  loading.value = true
  try {
    ;[c.value, invoices.value, costs.value] = await Promise.all([
      api<ContractDetail>(`/contracts/detail?${q()}`),
      api<ContractInvoice[]>(`/contracts/invoices?${q()}`),
      // 实际成本仅成本可见角色加载
      canSeeCost() ? api<ContractCost[]>(`/contracts/costs?${q()}`) : Promise.resolve([] as ContractCost[]),
    ])
  } catch (e) {
    ElMessage.error((e as Error).message)
    emit('update:modelValue', false)
  } finally {
    loading.value = false
  }
}
watch(
  () => [props.modelValue, props.customerId, props.contractKey],
  () => {
    if (props.modelValue) {
      tab.value = 'overview'
      load()
    }
  },
  { immediate: true },
)
async function refresh() {
  await load()
  emit('changed')
}
defineExpose({ refresh })

const pending = computed(() => c.value?.changes.filter((x) => x.status === 'PENDING') || [])
/** 实际成本由系统管理员、管理层、财务登记。 */
const canCost = computed(() => canSeeCost())
const firstQuote = computed(() => c.value?.items[0]?.quote_id)
function printContract() {
  if (firstQuote.value) window.open(`/print/${firstQuote.value}/contract`, '_blank', 'noopener')
}

const router = useRouter()

// ---------- 开票 ----------
const invoiceOpen = ref(false)
const freshInvoice = () => ({
  kind: 'INVOICE' as 'INVOICE' | 'RED',
  invoiceNo: '',
  invoiceType: '',
  issuedOn: today(),
  amount: 0,
  taxPercent: 13,
  reversesId: '' as string,
  note: '',
})
const inv = ref(freshInvoice())
const blueInvoices = computed(() =>
  invoices.value.filter((i) => i.kind === 'INVOICE' && Number(i.amount) > Number(i.reversed)),
)
function openInvoice(kind: 'INVOICE' | 'RED') {
  inv.value = {
    ...freshInvoice(),
    kind,
    amount: kind === 'INVOICE' ? Math.max(0, Number(c.value?.uninvoiced || 0)) : 0,
  }
  invoiceOpen.value = true
}
async function saveInvoice() {
  if (!c.value) return
  if (!inv.value.invoiceNo.trim() || inv.value.amount <= 0) return ElMessage.warning('请填写发票号码和正数金额')
  if (inv.value.kind === 'RED' && !inv.value.reversesId) return ElMessage.warning('请选择冲销的原发票')
  busy.value = true
  try {
    await post('/contracts/invoices', {
      customerId: c.value.customer_id,
      contractKey: c.value.contract_key,
      kind: inv.value.kind,
      invoiceNo: inv.value.invoiceNo.trim(),
      invoiceType: inv.value.invoiceType || null,
      issuedOn: inv.value.issuedOn,
      amount: inv.value.amount,
      taxRate: inv.value.taxPercent / 100,
      reversesId: inv.value.kind === 'RED' ? inv.value.reversesId : null,
      note: inv.value.note,
    })
    invoiceOpen.value = false
    ElMessage.success(inv.value.kind === 'RED' ? '红字发票已登记' : '开票已登记')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function askReason(title: string) {
  try {
    const r = await ElMessageBox.prompt('请填写原因（保留在操作记录中）', title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => !!v?.trim() || '请填写原因',
    })
    return r.value.trim()
  } catch {
    return null
  }
}
async function deleteInvoice(i: ContractInvoice) {
  const reason = await askReason(`删除发票 ${i.invoice_no}`)
  if (!reason) return
  try {
    await post(`/contracts/invoices/${i.id}/delete`, { reason })
    ElMessage.success('已删除错误登记')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 成本 ----------
const costOpen = ref(false)
const freshCost = () => ({
  category: 'MATERIAL',
  supplierId: '',
  amount: 0,
  incurredOn: today(),
  reference: '',
  note: '',
})
const cost = ref(freshCost())
async function openCost() {
  cost.value = freshCost()
  if (!suppliers.value.length)
    suppliers.value = (await api<Partner[]>('/partners').catch(() => [])).filter((p) => p.kind === 'SUPPLIER')
  costOpen.value = true
}
async function saveCost() {
  if (!c.value) return
  if (cost.value.amount <= 0) return ElMessage.warning('请填写正数金额')
  busy.value = true
  try {
    await post('/contracts/costs', {
      customerId: c.value.customer_id,
      contractKey: c.value.contract_key,
      ...cost.value,
      supplierId: cost.value.supplierId || null,
    })
    costOpen.value = false
    ElMessage.success('成本已登记')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function deleteCost(x: ContractCost) {
  const reason = await askReason('删除成本记录')
  if (!reason) return
  try {
    await post(`/contracts/costs/${x.id}/delete`, { reason })
    ElMessage.success('已删除')
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---------- 变更审批 ----------
async function review(x: ContractChange, action: 'approve' | 'reject' | 'withdraw') {
  let note: string | null = ''
  if (action === 'reject') {
    note = await askReason('驳回变更申请')
    if (!note) return
  } else if (action === 'approve') {
    try {
      await ElMessageBox.confirm('批准后合同内容立即按申请修改。', '批准变更', { type: 'warning' })
    } catch {
      return
    }
  }
  try {
    await post(`/contracts/changes/${x.id}/${action}`, action === 'withdraw' ? {} : { note })
    ElMessage.success({ approve: '已批准，合同已更新', reject: '已驳回', withdraw: '已撤回' }[action])
    await refresh()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>
<template>
  <el-drawer
    :model-value="modelValue"
    size="min(1080px, 96vw)"
    :with-header="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="drawer-body">
      <template v-if="c">
        <div class="head">
          <div>
            <div class="eyebrow">{{ c.customer_name }} · {{ c.brands.join(' / ') || '未关联门店' }}</div>
            <h2>
              {{ c.contract_no || '未填合同号' }}
              <span class="status">{{ contractStatusName[c.status] }}</span>
              <el-tag v-if="pending.length" type="warning" size="small">变更待审批</el-tag>
            </h2>
            <p class="muted">
              {{ c.title || '未填合同名称' }} · 签约主体 {{ c.company_name || '未指定' }} · 负责人
              {{ c.owner_name || '未分配' }}
            </p>
          </div>
          <div class="actions">
            <el-button v-if="canWrite() || canFinance()" size="small" type="primary" @click="editOpen = true">
              {{ c.status === 'DRAFT' || canApprove() ? '编辑合同' : '申请变更' }}
            </el-button>
            <el-button v-if="canFinance()" size="small" @click="openInvoice('INVOICE')">登记开票</el-button>
            <el-button
              v-if="(canWrite() || canFinance()) && c.projects && (c.confirmation !== 'CONFIRMED' || c.undated > 0)"
              size="small"
              @click="emit('confirm', c)"
            >
              设置应收截止日
            </el-button>
            <el-button v-if="canFinance() && Number(c.outstanding) > 0" size="small" @click="emit('receipt', c)">
              登记回款
            </el-button>
            <el-button v-if="canCost" size="small" @click="openCost">登记成本</el-button>
            <el-button size="small" @click="emit('attachments', c)">附件 {{ c.attachments || '' }}</el-button>
            <el-button v-if="firstQuote" size="small" link type="primary" @click="printContract">打印合同</el-button>
          </div>
        </div>

        <div class="metrics">
          <div>
            <span>合同金额</span>
            <strong>{{ money(c.amount) }}</strong>
            <small v-if="c.signed_amount != null">门店订单 {{ money(c.order_amount) }}</small>
            <small v-else>按门店订单合计</small>
          </div>
          <div>
            <span>已开票</span>
            <strong>{{ money(c.invoiced) }}</strong>
            <small>{{ invoiceStatusName[c.invoice_status] }} · 未开 {{ money(c.uninvoiced) }}</small>
          </div>
          <div>
            <span>已回款</span>
            <strong>{{ money(c.received) }}</strong>
            <small>回款率 {{ percent(c.collection_rate) }}</small>
          </div>
          <div>
            <span>未收应收</span>
            <strong :class="c.overdue > 0 ? 'danger-text' : ''">{{ money(c.outstanding) }}</strong>
            <small :class="c.overdue > 0 ? 'danger-text' : ''">
              {{
                c.overdue > 0
                  ? `逾期 ${money(c.overdue)}（${c.overdue_days} 天）`
                  : `开票未回款 ${money(c.invoiced_unreceived)}`
              }}
            </small>
          </div>
          <div>
            <span>发货</span>
            <strong>{{ c.shipped }} / {{ c.projects }}</strong>
            <small>已发货 / 门店</small>
          </div>
          <div v-if="canSeeCost()">
            <span>毛利</span>
            <strong :class="c.profit != null && c.profit < 0 ? 'danger-text' : ''">
              {{ c.profit == null ? '—' : money(c.profit) }}
            </strong>
            <small>
              {{ c.gross_margin == null ? '成本待补' : percent(c.gross_margin) }} ·
              {{
                c.cost_source === 'ACTUAL' ? '实际成本' : c.cost_source === 'ESTIMATED' ? '报价预估成本' : '成本不完整'
              }}
            </small>
          </div>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="合同信息" name="overview">
            <el-descriptions :column="3" border size="small">
              <el-descriptions-item label="签订日期">{{ day(c.info?.signed_on) }}</el-descriptions-item>
              <el-descriptions-item label="签约金额">
                {{ c.info?.signed_amount == null ? '按门店订单' : money(c.info.signed_amount) }}
              </el-descriptions-item>
              <el-descriptions-item label="我方签约主体">{{ c.info?.company_name || '未指定' }}</el-descriptions-item>
              <el-descriptions-item label="销售负责人">{{ c.owner_name || '未分配' }}</el-descriptions-item>
              <el-descriptions-item label="质保期">
                {{ c.info?.warranty_months != null ? `${c.info.warranty_months} 个月` : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="原件归档">
                {{ c.info?.archived ? `已归档 ${c.info.archive_no || ''}` : '未归档' }}
              </el-descriptions-item>
              <el-descriptions-item label="合同名称" :span="3">{{ c.info?.title || '—' }}</el-descriptions-item>
              <el-descriptions-item label="付款条款" :span="3">
                <span class="pre">{{ c.info?.payment_terms || '—' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="备注" :span="3">{{ c.info?.note || '—' }}</el-descriptions-item>
            </el-descriptions>
            <div v-if="!c.recorded" class="inline-note gap-top">
              这是历史合同（只有门店订单），尚未登记合同信息。首次编辑时按“已签订”建立合同记录。
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`门店订单 ${c.projects}`" name="items">
            <el-table :data="c.items" size="small" empty-text="尚未关联门店订单（在项目台账中为门店填写该合同号）">
              <el-table-column label="门店" prop="project_name" min-width="180" show-overflow-tooltip />
              <el-table-column label="城市" prop="city" width="64" />
              <el-table-column label="下单日期" width="98">
                <template #default="{ row }">{{ day(row.order_date) }}</template>
              </el-table-column>
              <el-table-column label="发货 / 物流" min-width="150">
                <template #default="{ row }">
                  {{ row.shipping_status || '未登记' }}
                  <small v-if="row.shipped_on" class="cell-secondary">{{ day(row.shipped_on) }} 发出</small>
                  <small v-else-if="row.expected_ship_on" class="cell-secondary">
                    预计 {{ day(row.expected_ship_on) }}
                  </small>
                  <small v-if="row.tracking_no" class="cell-secondary">
                    {{ row.courier || '' }} {{ row.tracking_no }}
                  </small>
                  <el-button
                    v-if="canWrite()"
                    link
                    type="primary"
                    class="cell-link"
                    @click="router.push({ path: '/projects', query: { delivery: row.project_id } })"
                  >
                    在项目台账办理发货
                  </el-button>
                </template>
              </el-table-column>
              <el-table-column label="报价" width="126">
                <template #default="{ row }">
                  <el-button link type="primary" @click="router.push(`/quotes?detail=${row.quote_id}`)">
                    {{ row.quote_code }}
                  </el-button>
                  <StatusTag :status="row.status" />
                </template>
              </el-table-column>
              <el-table-column label="金额" align="right" min-width="98">
                <template #default="{ row }">{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="已回款" align="right" min-width="98">
                <template #default="{ row }">{{ money(row.received) }}</template>
              </el-table-column>
              <el-table-column label="未收" align="right" min-width="98">
                <template #default="{ row }">{{ money(row.outstanding) }}</template>
              </el-table-column>
              <el-table-column label="应收截止日" width="100">
                <template #default="{ row }">
                  <span :class="row.due_date ? '' : 'muted'">{{ row.due_date ? day(row.due_date) : '未设' }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`开票 ${invoices.length}`" name="invoices">
            <div v-if="canFinance()" class="tab-actions">
              <el-button size="small" type="primary" @click="openInvoice('INVOICE')">登记蓝字发票</el-button>
              <el-button size="small" :disabled="!blueInvoices.length" @click="openInvoice('RED')">红字冲销</el-button>
            </div>
            <el-table :data="invoices" size="small" empty-text="暂无开票记录">
              <el-table-column label="开票日期" width="100">
                <template #default="{ row }">{{ day(row.issued_on) }}</template>
              </el-table-column>
              <el-table-column label="发票号码" prop="invoice_no" min-width="170" />
              <el-table-column label="类型" width="110">
                <template #default="{ row }">
                  <span :class="row.kind === 'RED' ? 'danger-text' : ''">
                    {{ row.kind === 'RED' ? '红字' : '蓝字'
                    }}{{ row.invoice_type === 'SPECIAL' ? '专票' : row.invoice_type === 'NORMAL' ? '普票' : '' }}
                  </span>
                  <small v-if="row.reverses_no" class="cell-secondary">冲 {{ row.reverses_no }}</small>
                </template>
              </el-table-column>
              <el-table-column label="价税合计" align="right" min-width="110">
                <template #default="{ row }">{{ row.kind === 'RED' ? '-' : '' }}{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="税率" width="70">
                <template #default="{ row }">{{ row.tax_rate == null ? '—' : percent(row.tax_rate) }}</template>
              </el-table-column>
              <el-table-column label="签约主体" prop="company_name" min-width="140" show-overflow-tooltip />
              <el-table-column label="备注" prop="note" min-width="120" show-overflow-tooltip />
              <el-table-column v-if="canFinance()" label="" width="60">
                <template #default="{ row }">
                  <el-button link type="danger" @click="deleteInvoice(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`回款 ${c.receipts.length}`" name="receipts">
            <el-table :data="c.receipts" size="small" empty-text="暂无回款记录">
              <el-table-column label="日期" width="100">
                <template #default="{ row }">{{ day(row.paid_on) }}</template>
              </el-table-column>
              <el-table-column label="类型" width="80">
                <template #default="{ row }">
                  <span :class="row.kind === 'RECEIPT' ? 'profit-text' : 'danger-text'">
                    {{ row.kind === 'RECEIPT' ? '回款' : '退款' }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="金额" align="right" min-width="120">
                <template #default="{ row }">{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="凭证号" prop="reference" min-width="160" />
              <el-table-column label="备注" prop="note" min-width="160" show-overflow-tooltip />
            </el-table>
            <div v-if="canFinance() && Number(c.received) > 0" class="tab-actions">
              <el-button size="small" link @click="emit('refund', c)">登记退款</el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane v-if="canSeeCost()" :label="`成本 ${costs.length}`" name="costs">
            <div class="cost-note muted">
              报价预估成本 {{ c.estimated_cost == null ? '不完整' : money(c.estimated_cost) }} · 已登记实际成本
              {{ money(c.actual_cost) }}
            </div>
            <div v-if="canCost" class="tab-actions">
              <el-button size="small" type="primary" @click="openCost">登记成本</el-button>
            </div>
            <el-table :data="costs" size="small" empty-text="暂无实际成本">
              <el-table-column label="日期" width="100">
                <template #default="{ row }">{{ day(row.incurred_on) }}</template>
              </el-table-column>
              <el-table-column label="类别" width="90">
                <template #default="{ row }">{{ costCategoryName[row.category] }}</template>
              </el-table-column>
              <el-table-column label="供应商" prop="supplier_name" min-width="140" />
              <el-table-column label="金额" align="right" min-width="110">
                <template #default="{ row }">{{ money(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="凭证号" prop="reference" min-width="120" />
              <el-table-column label="备注" prop="note" min-width="140" show-overflow-tooltip />
              <el-table-column label="登记人" prop="created_by" width="90" />
              <el-table-column label="" width="60">
                <template #default="{ row }">
                  <el-button v-if="canSeeCost()" link type="danger" @click="deleteCost(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`变更记录 ${c.changes.length}`" name="changes">
            <div v-if="!c.changes.length" class="muted empty">暂无变更记录</div>
            <div v-for="x in c.changes" :key="x.id" :class="['change', x.status.toLowerCase()]">
              <div class="change-head">
                <span :class="['badge', x.status.toLowerCase()]">{{ changeStatusName[x.status] }}</span>
                <strong>{{ x.requester_name || x.requested_by }}</strong>
                <span class="muted">{{ dateTime(x.requested_at) }}</span>
                <span class="muted">原因：{{ x.reason }}</span>
                <span class="spacer"></span>
                <template v-if="x.status === 'PENDING'">
                  <template v-if="canApprove()">
                    <el-button size="small" type="primary" @click="review(x, 'approve')">批准</el-button>
                    <el-button size="small" @click="review(x, 'reject')">驳回</el-button>
                  </template>
                  <el-button
                    v-if="x.requested_by === session.user?.username"
                    size="small"
                    link
                    @click="review(x, 'withdraw')"
                  >
                    撤回
                  </el-button>
                </template>
              </div>
              <table class="diff">
                <tr v-for="d in x.changes" :key="d.field">
                  <th>{{ d.label }}</th>
                  <td class="before">{{ d.before ?? '（空）' }}</td>
                  <td class="arrow">→</td>
                  <td class="after">{{ d.after ?? '（空）' }}</td>
                </tr>
              </table>
              <div v-if="x.reviewed_by && x.status !== 'DIRECT'" class="muted review">
                {{ changeStatusName[x.status] }}：{{ x.reviewer_name || x.reviewed_by }} {{ dateTime(x.reviewed_at) }}
                {{ x.review_note ? `· ${x.review_note}` : '' }}
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`操作记录 ${c.events.length}`" name="events">
            <el-table :data="c.events" size="small" max-height="460" empty-text="暂无操作记录">
              <el-table-column label="时间" width="140">
                <template #default="{ row }">{{ dateTime(row.created_at) }}</template>
              </el-table-column>
              <el-table-column label="操作人" width="100">
                <template #default="{ row }">{{ row.actor_name || row.actor }}</template>
              </el-table-column>
              <el-table-column label="操作" width="130">
                <template #default="{ row }">{{ actionName[row.action] || row.action }}</template>
              </el-table-column>
              <el-table-column label="说明" prop="detail" min-width="300" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <ContractInfoDialog v-model="editOpen" :contract="c" @saved="refresh" />

    <el-dialog
      v-model="invoiceOpen"
      :title="inv.kind === 'RED' ? '登记红字发票（冲销）' : '登记开票'"
      width="560px"
      append-to-body
    >
      <p v-if="c" class="dialog-lead">
        合同金额 {{ money(c.amount) }} · 已开票 {{ money(c.invoiced) }} · 未开票 {{ money(c.uninvoiced) }}
      </p>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="发票号码" required>
            <el-input v-model="inv.invoiceNo" maxlength="60" placeholder="数电票 20 位号码" />
          </el-form-item>
          <el-form-item label="开票日期" required>
            <el-date-picker v-model="inv.issuedOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="价税合计（元）" required>
            <el-input-number v-model="inv.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="税率（%）">
            <el-input-number v-model="inv.taxPercent" :min="0" :max="100" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="发票类型">
            <el-select v-model="inv.invoiceType" clearable placeholder="按客户资料">
              <el-option label="增值税专用发票" value="SPECIAL" />
              <el-option label="增值税普通发票" value="NORMAL" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="inv.kind === 'RED'" label="冲销的原发票" required>
            <el-select v-model="inv.reversesId">
              <el-option
                v-for="b in blueInvoices"
                :key="b.id"
                :value="b.id"
                :label="`${b.invoice_no} / 可冲 ${money(Number(b.amount) - Number(b.reversed))}`"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="inv.note" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="invoiceOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveInvoice">登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="costOpen" title="登记合同实际成本" width="560px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="成本类别" required>
            <el-select v-model="cost.category">
              <el-option v-for="(n, k) in costCategoryName" :key="k" :label="n" :value="k" />
            </el-select>
          </el-form-item>
          <el-form-item label="供应商">
            <el-select v-model="cost.supplierId" clearable filterable placeholder="可不填">
              <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="金额（元）" required>
            <el-input-number v-model="cost.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="发生日期" required>
            <el-date-picker v-model="cost.incurredOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="凭证号"><el-input v-model="cost.reference" maxlength="150" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="cost.note" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="costOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveCost">登记</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>
<style scoped>
.drawer-body {
  min-height: 300px;
}
.head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}
.head h2 {
  margin: 2px 0 4px;
  font-size: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.head p {
  margin: 0;
  font-size: 14px;
}
.eyebrow {
  font-size: 13px;
  color: #5d6a63;
}
.status {
  font-size: 13px;
  font-weight: 500;
  color: #24634f;
  background: #e6f0ea;
  border-radius: 10px;
  padding: 2px 8px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  max-width: 520px;
}
.actions .el-button + .el-button {
  margin-left: 0;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 1px;
  background: #e4eae0;
  border: 1px solid #e4eae0;
  border-radius: 8px;
  overflow: hidden;
  margin: 14px 0 6px;
}
.metrics > div {
  background: #fff;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.metrics span {
  font-size: 13px;
  color: #5d6a63;
}
.metrics strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  color: #253731;
}
.metrics small {
  font-size: 12px;
  color: #66716a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
@media (max-width: 900px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.tab-actions {
  display: flex;
  gap: 8px;
  margin: 4px 0 10px;
}
.cost-note {
  font-size: 13px;
  margin-bottom: 6px;
}
.pre {
  white-space: pre-wrap;
}
.gap-top {
  margin-top: 10px;
}
.empty {
  padding: 24px 0;
  text-align: center;
}
.change {
  border: 1px solid #e4eae0;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.change.pending {
  border-color: #e7c77d;
  background: #fffaf0;
}
.change-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.badge {
  font-size: 13px;
  padding: 1px 8px;
  border-radius: 10px;
  background: #eef1ec;
  color: #4b5a52;
}
.badge.pending {
  background: #f7efdb;
  color: #8a6a1f;
}
.badge.approved {
  background: #e6f0ea;
  color: #24634f;
}
.badge.rejected {
  background: #f9e4e1;
  color: #b23b2e;
}
.diff {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 13px;
}
.diff th {
  width: 110px;
  text-align: left;
  color: #5d6a63;
  font-weight: 500;
  padding: 3px 0;
  vertical-align: top;
}
.diff td {
  padding: 3px 6px;
  vertical-align: top;
  white-space: pre-wrap;
  word-break: break-all;
}
.diff .before {
  color: #66716a;
  text-decoration: line-through;
  width: 40%;
}
.diff .arrow {
  color: #66716a;
  width: 16px;
}
.diff .after {
  color: #253731;
}
.review {
  font-size: 13px;
  margin-top: 6px;
}
.cell-secondary {
  display: block;
}
.cell-link {
  display: block;
  height: auto;
  padding: 0;
  font-size: 13px;
}
</style>
