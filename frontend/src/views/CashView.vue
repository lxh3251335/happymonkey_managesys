<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { onDataChanged } from '../dataEvents'
import { useRoute } from 'vue-router'
import { useUrlState } from '../urlState'
import { reviewChange } from '../changeReview'
import ContractTable from '../components/ContractTable.vue'
import ContractAttachments from '../components/ContractAttachments.vue'
import ContractDrawer from '../components/ContractDrawer.vue'
import ContractInfoDialog from '../components/ContractInfoDialog.vue'
import ExcelTools from '../components/ExcelTools.vue'
import ReceiptLedger from '../components/ReceiptLedger.vue'
import ReceiptEntryDialog, { type ReceiptPreset } from '../components/ReceiptEntryDialog.vue'
import InvoiceEntryDialog from '../components/InvoiceEntryDialog.vue'
import { changeStatusName } from '../dictionaries'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, canApprove, canFinance, canWrite, isAdmin } from '../api'
import type { Contract, ContractChange, ContractInvoice, ReceiptBatch, ReceiptRow } from '../types'
import { money, today, day, afterDays } from '../utils'

/** 合同台账：合同、开票、回款流水、变更审批。回款统一按合同登记，系统按门店剩余应收自动分摊。 */
const route = useRoute(),
  legacyTabs: Record<string, string> = { receipts: 'ledger', balances: 'ledger', entries: 'ledger' },
  tab = ref(legacyTabs[String(route.query.tab)] || String(route.query.tab || 'contracts')),
  urlState = useUrlState(),
  contracts = ref<Contract[]>([]),
  ledger = ref<ReceiptRow[]>([]),
  invoices = ref<ContractInvoice[]>([]),
  changes = ref<ContractChange[]>([]),
  drawerOpen = ref(false),
  drawerKey = ref({ customerId: '', contractKey: '' }),
  drawerTab = ref(''),
  createOpen = ref(false),
  drawer = ref<InstanceType<typeof ContractDrawer>>(),
  attachDialog = ref(false),
  attachContract = ref<Contract>(),
  busy = ref(false),
  error = ref('')
/** 回款单：一笔到账分到多份合同；unallocated > 0 的还有余额待分配。 */
urlState('tab', tab, 'contracts')
const batches = ref<ReceiptBatch[]>([])
const pendingBatches = computed(() => batches.value.filter((b) => Number(b.unallocated) > 0))
const entryOpen = ref(false),
  entryPreset = ref<ReceiptPreset>(),
  invoiceEntryOpen = ref(false)
/** 登记回款（可分到多份合同）；从某份合同打开时预先记到那份合同上，也可以继续分配一笔回款的余额。 */
function openEntry(preset?: ReceiptPreset) {
  entryPreset.value = preset
  entryOpen.value = true
}
async function load() {
  try {
    ;[contracts.value, ledger.value, batches.value, invoices.value, changes.value] = await Promise.all([
      api<Contract[]>('/contracts'),
      api<ReceiptRow[]>('/cash/ledger'),
      api<ReceiptBatch[]>('/receipt-batches'),
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
// 合同：设置应收截止日、整笔回款与退款（按门店剩余应收比例自动分摊）
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
function openConfirm(c: Contract) {
  active.value = c
  dueDate.value = afterDays(30)
  confirmDialog.value = true
}
// ---------- 批量设置应收截止日期（对合同台账当前筛选出的、未设截止日的合同） ----------
const batchOpen = ref(false),
  batchTargets = ref<Contract[]>([]),
  batchForm = ref({ mode: 'OFFSET' as 'OFFSET' | 'FIXED', base: 'SIGNED' as 'SIGNED' | 'ORDER', days: 30, dueDate: '' })
function openBatchDue(targets: Contract[]) {
  batchTargets.value = targets
  batchForm.value = { mode: 'OFFSET', base: 'SIGNED', days: 30, dueDate: afterDays(30) }
  batchOpen.value = true
}
async function saveBatchDue() {
  const f = batchForm.value
  if (f.mode === 'FIXED' && !f.dueDate) return ElMessage.warning('请填写应收截止日期')
  busy.value = true
  try {
    const r = await post<{ updated: number; contracts: number; noBase: number }>('/contracts/due-dates', {
      contracts: batchTargets.value.map((c) => ({ customerId: c.customer_id, contractKey: c.contract_key })),
      mode: f.mode,
      dueDate: f.mode === 'FIXED' ? f.dueDate : null,
      base: f.mode === 'OFFSET' ? f.base : null,
      days: f.mode === 'OFFSET' ? f.days : null,
    })
    batchOpen.value = false
    await reloadAll()
    const done = `已为 ${r.contracts} 份合同的 ${r.updated} 个门店订单设置应收截止日期`
    if (r.noBase)
      ElMessageBox.alert(
        `${done}。另有 ${r.noBase} 个门店订单没有签订日期或下单日期，无法推算，请改用“统一日期”或在合同里单独设置。`,
        '批量设置完成',
        { confirmButtonText: '知道了' },
      ).catch(() => {})
    else ElMessage.success(done)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
function openContract(c: Contract, kind: 'RECEIPT' | 'REFUND') {
  active.value = c
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
    ElMessage.success(`已为 ${r.confirmed} 个门店订单设置应收截止日期`)
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
  if (cform.value.kind === 'REFUND' && !cform.value.reversesId) return ElMessage.warning('请选择原回款')
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
/** 打开合同详情；打开着的合同也写进地址，刷新或返回时还在。 */
function openDrawer(customerId: string, contractKey: string, initialTab = '') {
  drawerKey.value = { customerId, contractKey }
  drawerTab.value = initialTab
  drawerOpen.value = true
  urlState.write('customer', customerId)
  urlState.write('contract', contractKey)
}
watch(drawerOpen, (open) => {
  if (open) return
  urlState.write('customer', undefined)
  urlState.write('contract', undefined)
})
/** 在审批列表里直接批准或驳回，不用先打开合同。 */
async function reviewRow(x: ContractChange, action: 'approve' | 'reject') {
  if (await reviewChange(x, action)) await load()
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
onDataChanged(load)
onMounted(async () => {
  await load()
  if (route.query.customer && route.query.contract)
    openDrawer(String(route.query.customer), String(route.query.contract))
})
</script>
<template>
  <div>
    <PageHeader eyebrow="CONTRACTS & EXECUTION" title="合同台账">
      <el-button v-if="canWrite()" type="primary" @click="createOpen = true">
        <el-icon><Plus /></el-icon>
        新建合同
      </el-button>
      <el-button v-if="canFinance()" @click="invoiceEntryOpen = true">登记发票</el-button>
      <el-button v-if="canFinance()" @click="openEntry()">登记回款</el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="panel">
      <div class="filter-bar tab-bar">
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
        <ExcelTools
          v-if="tab === 'ledger'"
          dataset="receipts"
          title="回款登记"
          :can-import="canFinance()"
          @imported="load"
        />
      </div>
      <template v-if="tab === 'contracts'">
        <ContractTable
          :contracts="contracts"
          :page-size="20"
          sync-url
          :batch-due="canWrite() || canFinance()"
          @batch-due="openBatchDue"
          @open="(c) => openDrawer(c.customer_id, c.contract_key)"
        />
        <div class="panel-bottom-note">
          点击合同查看详情、开票、回款、成本与变更记录。按合同整笔登记回款时，系统按各门店剩余应收比例自动分摊到门店订单。
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
          <el-table-column label="签约主体" prop="company_name" min-width="160" show-overflow-tooltip />
          <el-table-column label="备注" prop="note" min-width="140" show-overflow-tooltip />
        </el-table>
      </template>
      <template v-else-if="tab === 'changes'">
        <el-table
          :data="changes"
          empty-text="没有待审批的变更"
          class="clickable"
          @row-click="(r: ContractChange) => openDrawer(r.customer_id!, r.contract_no!, 'changes')"
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
          <el-table-column v-if="canApprove()" label="审批" width="132" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click.stop="reviewRow(row, 'approve')">批准</el-button>
              <el-button size="small" @click.stop="reviewRow(row, 'reject')">驳回</el-button>
            </template>
          </el-table-column>
          <el-table-column v-else label="状态" width="80">
            <template #default="{ row }">{{ changeStatusName[row.status] }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template v-else-if="tab === 'ledger'">
        <!-- 还有余额没分到合同上的回款 -->
        <div v-if="pendingBatches.length" class="pending-batches">
          <strong>待分配回款 {{ pendingBatches.length }} 笔</strong>
          <span v-for="b in pendingBatches" :key="b.id" class="pending-item">
            {{ day(b.paid_on) }} · {{ b.customer_name }} · 到账 {{ money(b.amount) }}，还有
            <b>{{ money(b.unallocated) }}</b>
            未分配
            <el-button
              v-if="canFinance()"
              link
              type="primary"
              @click="
                openEntry({
                  batch: {
                    id: b.id,
                    reference: b.reference,
                    unallocated: Number(b.unallocated),
                    paid_on: b.paid_on,
                    customer_id: b.customer_id,
                  },
                })
              "
            >
              去分配
            </el-button>
          </span>
        </div>
        <ReceiptLedger
          :rows="ledger"
          @open="(r) => openDrawer(r.customer_id, r.contract_key)"
          @refund-legacy="openLegacyRefund"
        />
        <div class="panel-bottom-note">
          在右上角“登记回款”或合同详情里登记；一笔到账可以分到多份合同，每份合同内再按各门店剩余应收比例分摊。早期按门店登记的流水标为“历史”，只能关联退款冲正。
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
    <el-dialog v-model="batchOpen" title="批量设置应收截止日期" width="560px">
      <p class="dialog-lead">
        当前筛选出的合同里有 {{ batchTargets.length }}
        份还有门店订单没设应收截止日期。只补上缺的，已有截止日期的不会改动。
      </p>
      <el-form label-position="top">
        <el-form-item label="设置方式">
          <el-radio-group v-model="batchForm.mode">
            <el-radio value="OFFSET">按日期推算</el-radio>
            <el-radio value="FIXED">统一日期</el-radio>
          </el-radio-group>
        </el-form-item>
        <div v-if="batchForm.mode === 'OFFSET'" class="form-grid">
          <el-form-item label="基准日期">
            <el-select v-model="batchForm.base">
              <el-option label="合同签订日期（没有则用下单日期）" value="SIGNED" />
              <el-option label="门店下单日期" value="ORDER" />
            </el-select>
          </el-form-item>
          <el-form-item label="之后多少天到期">
            <el-input-number v-model="batchForm.days" :min="0" :max="720" :precision="0" />
          </el-form-item>
        </div>
        <el-form-item v-else label="应收截止日期" required>
          <el-date-picker v-model="batchForm.dueDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveBatchDue">设置</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="confirmDialog" title="设置应收截止日期" width="520px">
      <template v-if="active">
        <p class="dialog-lead">
          {{ active.contract_no || '未填合同号' }} · {{ active.customer_name }}：
          {{ active.projects - active.confirmed_projects + Number(active.undated || 0) }}
          个门店订单还没有应收截止日期。
        </p>
        <el-form label-position="top">
          <el-form-item label="应收截止日期" required>
            <el-date-picker v-model="dueDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="confirmContract">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="contractDialog"
      :title="cform.kind === 'RECEIPT' ? '登记合同回款' : '登记合同退款'"
      width="600px"
    >
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
          <el-form-item v-if="cform.kind === 'REFUND'" label="关联原回款" required>
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
        </el-form>
      </template>
      <template #footer>
        <el-button @click="contractDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveContract">登记</el-button>
      </template>
    </el-dialog>
    <ContractAttachments v-model="attachDialog" :contract="attachContract" @changed="reloadAll" />
    <ReceiptEntryDialog v-model="entryOpen" :contracts="contracts" :preset="entryPreset" @saved="reloadAll" />
    <InvoiceEntryDialog v-model="invoiceEntryOpen" :contracts="contracts" @saved="reloadAll" />
    <ContractDrawer
      ref="drawer"
      v-model="drawerOpen"
      :customer-id="drawerKey.customerId"
      :contract-key="drawerKey.contractKey"
      :initial-tab="drawerTab"
      @changed="load"
      @confirm="openConfirm"
      @receipt="(c) => openEntry({ customerId: c.customer_id, contractKey: c.contract_key })"
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
/* 标签页行：合同台账页的数据区要尽量多，标签与导入导出共用一行并压缩留白 */
.tab-bar {
  padding: 4px 24px 0;
  border-bottom: 1px solid #edf1ea;
}
.invoice-sum {
  padding: 8px 24px;
  font-size: 13px;
  color: #5d6a63;
}
.invoice-sum strong {
  color: #253731;
}
.clickable :deep(.el-table__row) {
  cursor: pointer;
}
.change-line {
  font-size: 13px;
  line-height: 1.6;
}
.tab-badge {
  margin-left: 4px;
  vertical-align: top;
}
.pending-batches {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 18px;
  margin: 10px 24px 0;
  padding: 8px 12px;
  border: 1px solid #ead9ae;
  border-radius: 6px;
  background: #fdf8ea;
  font-size: 13px;
  color: #5d4a12;
}
.pending-batches strong {
  font-weight: 600;
}
.pending-item b {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
