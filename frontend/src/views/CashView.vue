<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import ContractTable from '../components/ContractTable.vue'
import ContractAttachments from '../components/ContractAttachments.vue'
import ContractDrawer from '../components/ContractDrawer.vue'
import ContractInfoDialog from '../components/ContractInfoDialog.vue'
import ExcelTools from '../components/ExcelTools.vue'
import ReceiptLedger from '../components/ReceiptLedger.vue'
import { changeStatusName } from '../dictionaries'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, canApprove, canFinance, canSeeCost, canWrite, isAdmin } from '../api'
import type { Contract, ContractChange, ContractInvoice, ReceiptRow } from '../types'
import { money, today, day, afterDays } from '../utils'

/** 合同台账：合同、开票、回款流水、变更审批。回款统一按合同登记，系统按门店剩余应收自动分摊。 */
const route = useRoute(),
  legacyTabs: Record<string, string> = { receipts: 'ledger', balances: 'ledger', entries: 'ledger' },
  tab = ref(legacyTabs[String(route.query.tab)] || String(route.query.tab || 'contracts')),
  contracts = ref<Contract[]>([]),
  ledger = ref<ReceiptRow[]>([]),
  invoices = ref<ContractInvoice[]>([]),
  changes = ref<ContractChange[]>([]),
  drawerOpen = ref(false),
  drawerKey = ref({ customerId: '', contractKey: '' }),
  createOpen = ref(false),
  drawer = ref<InstanceType<typeof ContractDrawer>>(),
  attachDialog = ref(false),
  attachContract = ref<Contract>(),
  busy = ref(false),
  error = ref('')
async function load() {
  try {
    ;[contracts.value, ledger.value, invoices.value, changes.value] = await Promise.all([
      api<Contract[]>('/contracts'),
      api<ReceiptRow[]>('/cash/ledger'),
      api<ContractInvoice[]>('/contracts/invoices'),
      api<ContractChange[]>('/contracts/changes/pending'),
    ])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}

// 历史门店流水：只能关联退款冲正
const legacyOpen = ref(false),
  legacyRow = ref<ReceiptRow>(),
  legacy = ref({ amount: 0, paidOn: today(), reference: '', note: '' })
function openLegacyRefund(r: ReceiptRow) {
  legacyRow.value = r
  legacy.value = { amount: Number(r.amount) - Number(r.refunded), paidOn: today(), reference: '', note: '' }
  legacyOpen.value = true
}
async function saveLegacyRefund() {
  if (!legacyRow.value || legacy.value.amount <= 0 || !legacy.value.reference.trim())
    return ElMessage.warning('请填写正数金额及凭证号')
  busy.value = true
  try {
    await post('/cash/legacy-refund', { ...legacy.value, reversesId: legacyRow.value.id })
    legacyOpen.value = false
    ElMessage.success('已登记退款冲正')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
// 合同：确认应收、整笔回款与退款（按门店剩余应收比例自动分摊）
const active = ref<Contract>(),
  confirmDialog = ref(false),
  dueDate = ref(afterDays(30)),
  contractDialog = ref(false)
const freshContract = () => ({
  kind: 'RECEIPT',
  amount: 0,
  paidOn: today(),
  reference: '',
  note: '',
  reversesId: null as string | null,
})
const cform = ref(freshContract())
const contractRefundable = computed(() =>
  ledger.value.filter(
    (r) =>
      r.source === 'CONTRACT' &&
      r.kind === 'RECEIPT' &&
      active.value &&
      r.customer_id === active.value.customer_id &&
      r.contract_key === active.value.contract_key &&
      Number(r.amount) > Number(r.refunded),
  ),
)
/** 页面上直接登记回款：先选合同（仅已确认应收、仍有未收的合同）。 */
const receivable = computed(() => contracts.value.filter((c) => Number(c.outstanding) > 0))
const pickKey = ref('')
function openReceipt() {
  active.value = undefined
  pickKey.value = ''
  cform.value = freshContract()
  contractDialog.value = true
}
function pickContract(key: string) {
  const c = receivable.value.find((x) => `${x.customer_id}::${x.contract_key}` === key)
  if (!c) return
  active.value = c
  cform.value.amount = Number(c.outstanding)
}
function openConfirm(c: Contract) {
  active.value = c
  dueDate.value = afterDays(30)
  confirmDialog.value = true
}
function openContract(c: Contract, kind: 'RECEIPT' | 'REFUND') {
  active.value = c
  pickKey.value = ''
  cform.value = { ...freshContract(), kind, amount: kind === 'RECEIPT' ? Number(c.outstanding) : 0 }
  contractDialog.value = true
}
async function confirmContract() {
  if (!active.value || !dueDate.value) return ElMessage.warning('请填写应收截止日期')
  busy.value = true
  try {
    const r = await post<{ confirmed: number }>('/contracts/confirm', {
      customerId: active.value.customer_id,
      contractKey: active.value.contract_key,
      dueDate: dueDate.value,
    })
    confirmDialog.value = false
    ElMessage.success(`已确认 ${r.confirmed} 个门店订单的应收`)
    await reloadAll()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function saveContract() {
  if (!active.value || !cform.value.reference.trim() || cform.value.amount <= 0)
    return ElMessage.warning('请填写正数金额及凭证号')
  if (cform.value.kind === 'REFUND' && !cform.value.reversesId) return ElMessage.warning('请选择原合同收款')
  busy.value = true
  try {
    const r = await post<{ allocations: number }>('/contracts/receipts', {
      ...cform.value,
      customerId: active.value.customer_id,
      contractKey: active.value.contract_key,
    })
    contractDialog.value = false
    ElMessage.success(`已登记，分摊到 ${r.allocations} 个门店订单`)
    await reloadAll()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
const csum = (k: keyof Contract) => contracts.value.reduce((s, c) => s + Number(c[k] || 0), 0)
/** 毛利率分母：只算成本已知（有毛利）的合同。 */
const marginBase = computed(() =>
  contracts.value.filter((c) => c.profit != null).reduce((s, c) => s + Number(c.amount || 0), 0),
)
const ratio = (a: number, b: number) => (b ? `${((a / b) * 100).toFixed(1)}%` : '—')
function openDrawer(customerId: string, contractKey: string) {
  drawerKey.value = { customerId, contractKey }
  drawerOpen.value = true
}
/** 抽屉中的操作完成后：刷新列表，并让抽屉重新加载。 */
async function reloadAll() {
  await load()
  if (drawerOpen.value) await drawer.value?.refresh()
}
const invoiceKeyword = ref('')
const filteredInvoices = computed(() =>
  invoices.value.filter((i) =>
    `${i.invoice_no}${i.customer_name}${i.contract_no}${i.company_name || ''}${i.note || ''}`.includes(
      invoiceKeyword.value.trim(),
    ),
  ),
)
const invoiceTotal = computed(() =>
  filteredInvoices.value.reduce((s, i) => s + (i.kind === 'RED' ? -1 : 1) * Number(i.amount), 0),
)
onMounted(async () => {
  await load()
  if (route.query.customer && route.query.contract)
    openDrawer(String(route.query.customer), String(route.query.contract))
})
</script>
<template>
  <div>
    <PageHeader
      eyebrow="CONTRACTS & EXECUTION"
      title="合同台账"
      description="合同要素、开票、到款、发货与成本一处追踪；已签订合同的修改须管理层批准。"
    >
      <el-button v-if="canWrite()" type="primary" @click="createOpen = true">
        <el-icon><Plus /></el-icon>
        新建合同
      </el-button>
      <el-button v-if="canFinance()" @click="openReceipt">登记合同回款</el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="metric-strip panel">
      <div>
        <span>合同额</span>
        <strong>{{ money(csum('amount')) }}</strong>
        <small>{{ contracts.length }} 份 · 拟定中 {{ contracts.filter((c) => c.status === 'DRAFT').length }}</small>
      </div>
      <div>
        <span>已开票</span>
        <strong>{{ money(csum('invoiced')) }}</strong>
        <small>{{ ratio(csum('invoiced'), csum('amount')) }} · 未开 {{ money(csum('uninvoiced')) }}</small>
      </div>
      <div>
        <span>已到款</span>
        <strong>{{ money(csum('received')) }}</strong>
        <small>回款率 {{ ratio(csum('received'), csum('amount')) }}</small>
      </div>
      <div>
        <span>未收应收</span>
        <strong :class="csum('overdue') > 0 ? 'danger-text' : ''">{{ money(csum('outstanding')) }}</strong>
        <small :class="csum('overdue') > 0 ? 'danger-text' : ''">逾期 {{ money(csum('overdue')) }}</small>
      </div>
      <div v-if="canSeeCost()">
        <span>毛利</span>
        <strong>{{ money(csum('profit')) }}</strong>
        <small>{{ ratio(csum('profit'), marginBase) }} · 成本已知合同</small>
      </div>
      <button type="button" :class="['strip-action', { hot: changes.length }]" @click="tab = 'changes'">
        <span>待审批变更</span>
        <strong>{{ changes.length }}</strong>
      </button>
    </div>
    <section class="panel">
      <div class="filter-bar">
        <el-tabs v-model="tab" class="table-tabs">
          <el-tab-pane label="合同台账" name="contracts" />
          <el-tab-pane label="开票记录" name="invoices" />
          <el-tab-pane label="回款流水" name="ledger" />
          <el-tab-pane name="changes">
            <template #label>
              变更审批
              <el-badge v-if="changes.length" :value="changes.length" type="warning" class="tab-badge" />
            </template>
          </el-tab-pane>
        </el-tabs>
        <ExcelTools
          v-if="tab === 'contracts'"
          dataset="contracts"
          title="合同台账"
          :can-import="isAdmin()"
          @imported="load"
        />
        <template v-if="tab === 'invoices'">
          <el-input v-model="invoiceKeyword" clearable :prefix-icon="Search" placeholder="搜索发票号、客户或合同号" />
          <ExcelTools dataset="contract-invoices" title="开票记录" :can-import="canFinance()" @imported="load" />
        </template>
      </div>
      <template v-if="tab === 'contracts'">
        <ContractTable
          :contracts="contracts"
          :initial-quick="String(route.query.quick || '')"
          @open="(c) => openDrawer(c.customer_id, c.contract_key)"
        />
        <div class="panel-bottom-note">
          点击合同查看详情、开票、到款、成本与变更记录。按合同整笔登记回款时，系统按各门店剩余应收比例自动分摊到门店订单。
        </div>
      </template>
      <template v-else-if="tab === 'invoices'">
        <div class="invoice-sum">
          {{ filteredInvoices.length }} 张发票 · 净开票
          <strong>{{ money(invoiceTotal) }}</strong>
        </div>
        <el-table
          :data="filteredInvoices"
          class="clickable"
          @row-click="(r: ContractInvoice) => openDrawer(r.customer_id, r.contract_no)"
        >
          <el-table-column label="开票日期" width="110">
            <template #default="{ row }">{{ day(row.issued_on) }}</template>
          </el-table-column>
          <el-table-column label="发票号码" prop="invoice_no" min-width="180" />
          <el-table-column label="合同号 / 客户" min-width="200">
            <template #default="{ row }">
              {{ row.contract_no.startsWith('#') ? '未填合同号' : row.contract_no }}
              <small class="cell-secondary">{{ row.customer_name }}</small>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <span :class="row.kind === 'RED' ? 'danger-text' : ''">
                {{ row.kind === 'RED' ? '红字冲销' : '蓝字' }}
              </span>
              <small v-if="row.reverses_no" class="cell-secondary">冲 {{ row.reverses_no }}</small>
            </template>
          </el-table-column>
          <el-table-column label="价税合计" align="right" width="140">
            <template #default="{ row }">{{ row.kind === 'RED' ? '-' : '' }}{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="开票方" prop="company_name" min-width="160" show-overflow-tooltip />
          <el-table-column label="备注" prop="note" min-width="140" show-overflow-tooltip />
        </el-table>
      </template>
      <template v-else-if="tab === 'changes'">
        <div class="inline-note changes-note">
          {{
            canApprove()
              ? '已签订合同的修改申请，批准后立即生效。点击打开合同查看完整对比后审批。'
              : '你提交的待审批变更申请。'
          }}
        </div>
        <el-table
          :data="changes"
          empty-text="没有待审批的变更"
          class="clickable"
          @row-click="(r: ContractChange) => openDrawer(r.customer_id!, r.contract_no!)"
        >
          <el-table-column label="提交时间" width="150">
            <template #default="{ row }">{{ String(row.requested_at).replace('T', ' ').slice(0, 16) }}</template>
          </el-table-column>
          <el-table-column label="合同" min-width="200">
            <template #default="{ row }">
              {{ row.contract_no?.startsWith('#') ? '未填合同号' : row.contract_no }}
              <small class="cell-secondary">{{ row.customer_name }} · {{ row.title || '' }}</small>
            </template>
          </el-table-column>
          <el-table-column label="申请人" width="100">
            <template #default="{ row }">{{ row.requester_name || row.requested_by }}</template>
          </el-table-column>
          <el-table-column label="修改内容" min-width="320">
            <template #default="{ row }">
              <div v-for="d in row.changes" :key="d.field" class="change-line">
                <span class="muted">{{ d.label }}：</span>
                <s class="muted">{{ d.before ?? '空' }}</s>
                → {{ d.after ?? '空' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="原因" prop="reason" min-width="160" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">{{ changeStatusName[row.status] }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template v-else-if="tab === 'ledger'">
        <ReceiptLedger
          :rows="ledger"
          @open="(r) => openDrawer(r.customer_id, r.contract_key)"
          @refund-legacy="openLegacyRefund"
        />
        <div class="panel-bottom-note">
          回款统一按合同登记（合同详情或右上角“登记合同回款”），系统按各门店剩余应收比例自动分摊。早期按门店登记的流水标为“历史”，只能关联退款冲正。
        </div>
      </template>
    </section>
    <el-dialog v-model="legacyOpen" title="历史门店流水 · 退款冲正" width="520px">
      <p v-if="legacyRow" class="dialog-lead">
        {{ legacyRow.project_name }} · 原流水 {{ legacyRow.reference }} · 可退
        {{ money(Number(legacyRow.amount) - Number(legacyRow.refunded)) }}
      </p>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="退款金额（元）" required>
            <el-input-number v-model="legacy.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="退款日期" required>
            <el-date-picker v-model="legacy.paidOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="凭证 / 银行流水号" required>
            <el-input v-model="legacy.reference" maxlength="150" />
          </el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="legacy.note" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="legacyOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveLegacyRefund">登记冲正</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="confirmDialog" title="确认合同应收" width="520px">
      <template v-if="active">
        <p class="dialog-lead">
          {{ active.contract_no || '未填合同号' }} · {{ active.customer_name }}：
          {{ active.projects - active.confirmed_projects }} 个门店订单待确认，合计
          {{ money(Number(active.amount) - Number(active.confirmed_amount)) }}。
        </p>
        <el-form label-position="top">
          <el-form-item label="应收截止日期" required>
            <el-date-picker v-model="dueDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </el-form>
        <div class="inline-note">确认后这些门店订单计入应收和账龄，历史报价状态变为“已确认”。</div>
      </template>
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="confirmContract">确认应收</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="contractDialog"
      :title="cform.kind === 'RECEIPT' ? '登记合同回款' : '登记合同退款'"
      width="600px"
    >
      <el-form v-if="!active || pickKey" label-position="top">
        <el-form-item label="合同" required>
          <el-select v-model="pickKey" filterable placeholder="选择仍有未收应收的合同" @change="pickContract">
            <el-option
              v-for="c in receivable"
              :key="`${c.customer_id}::${c.contract_key}`"
              :value="`${c.customer_id}::${c.contract_key}`"
              :label="`${c.contract_no || '未填合同号'} / ${c.customer_name} / 未收 ${money(c.outstanding)}`"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template v-if="active">
        <p class="dialog-lead">
          {{ active.contract_no || '未填合同号' }} · {{ active.customer_name }} · {{ active.projects }} 个门店 ·
          剩余应收
          {{ money(active.outstanding) }}
        </p>
        <el-form label-position="top">
          <div class="form-grid">
            <el-form-item label="金额（元）" required>
              <el-input-number v-model="cform.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
            </el-form-item>
            <el-form-item label="收付款日期" required>
              <el-date-picker v-model="cform.paidOn" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="凭证 / 银行流水号" required>
              <el-input v-model="cform.reference" maxlength="140" />
            </el-form-item>
          </div>
          <el-form-item v-if="cform.kind === 'REFUND'" label="关联原合同收款" required>
            <el-select v-model="cform.reversesId" filterable>
              <el-option
                v-for="r in contractRefundable"
                :key="r.id"
                :value="r.id"
                :label="`${day(r.paid_on)} / ${r.reference} / 可退 ${money(Number(r.amount) - Number(r.refunded))}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="备注"><el-input v-model="cform.note" type="textarea" maxlength="1000" /></el-form-item>
          <div class="inline-note">按各门店剩余应收比例自动分摊；已入账流水不可修改，错误收款请登记关联退款冲正。</div>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="contractDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveContract">登记</el-button>
      </template>
    </el-dialog>
    <ContractAttachments v-model="attachDialog" :contract="attachContract" @changed="reloadAll" />
    <ContractDrawer
      ref="drawer"
      v-model="drawerOpen"
      :customer-id="drawerKey.customerId"
      :contract-key="drawerKey.contractKey"
      @changed="load"
      @confirm="openConfirm"
      @receipt="(c) => openContract(c, 'RECEIPT')"
      @refund="(c) => openContract(c, 'REFUND')"
      @attachments="(c) => ((attachContract = c), (attachDialog = true))"
    />
    <ContractInfoDialog
      v-model="createOpen"
      @saved="async (k) => (await load(), openDrawer(k.customerId, k.contractKey))"
    />
  </div>
</template>
<style scoped>
.metric-strip {
  display: flex;
  align-items: stretch;
  margin-bottom: 12px;
  padding: 0;
  overflow: hidden;
}
.metric-strip > div,
.strip-action {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 9px 16px;
  border-right: 1px solid #edf1ea;
}
.metric-strip span {
  font-size: 12px;
  color: #6b7a72;
}
.metric-strip strong {
  font-size: 17px;
  line-height: 22px;
  color: #253731;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.metric-strip small {
  font-size: 11px;
  color: #8a958e;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.strip-action {
  flex: 0 0 104px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  font: inherit;
}
.strip-action:hover {
  background: #f6f9f4;
}
.strip-action.hot strong {
  color: #a0661b;
}
@media (max-width: 1000px) {
  .metric-strip {
    flex-wrap: wrap;
  }
  .metric-strip > div {
    flex: 1 1 33%;
  }
}
.invoice-sum {
  padding: 8px 24px;
  font-size: 12px;
  color: #6b7a72;
}
.invoice-sum strong {
  color: #253731;
}
.clickable :deep(.el-table__row) {
  cursor: pointer;
}
.changes-note {
  margin: 12px 24px;
}
.change-line {
  font-size: 12px;
  line-height: 1.6;
}
.tab-badge {
  margin-left: 4px;
  vertical-align: top;
}
</style>
