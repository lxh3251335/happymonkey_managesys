<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import ContractTable from '../components/ContractTable.vue'
import ContractAttachments from '../components/ContractAttachments.vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, canFinance, canWrite } from '../api'
import type { CashEntry, Contract, ContractReceipt, Quote } from '../types'
import { money, today, day, afterDays } from '../utils'
const entries = ref<CashEntry[]>([]),
  balances = ref<Quote[]>([]),
  keyword = ref(''),
  route = useRoute(),
  tab = ref(String(route.query.tab || 'contracts')),
  contracts = ref<Contract[]>([]),
  receipts = ref<ContractReceipt[]>([]),
  dialog = ref(false),
  attachDialog = ref(false),
  attachContract = ref<Contract>(),
  busy = ref(false),
  error = ref('')
const fresh = () => ({
  quotationId: '',
  kind: 'RECEIPT',
  amount: 0,
  paidOn: today(),
  reference: '',
  note: '',
  reversesId: null as string | null,
})
const form = ref(fresh())
const filteredBalances = computed(() =>
  balances.value.filter((q) => `${q.project_name}${q.customer_name}${q.code}`.includes(keyword.value)),
)
const filteredEntries = computed(() =>
  entries.value.filter((q) => `${q.project_name}${q.customer_name}${q.reference}`.includes(keyword.value)),
)
const refundable = computed(() =>
  entries.value.filter((e) => e.kind === 'RECEIPT' && e.quotation_id === form.value.quotationId),
)
const total = computed(() => balances.value.reduce((s, q) => s + Number(q.outstanding), 0))
const received = computed(() => balances.value.reduce((s, q) => s + Number(q.received), 0))
async function load() {
  try {
    ;[entries.value, balances.value, contracts.value, receipts.value] = await Promise.all([
      api<CashEntry[]>('/cash'),
      api<Quote[]>('/cash/balances'),
      api<Contract[]>('/contracts'),
      api<ContractReceipt[]>('/contracts/receipts'),
    ])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
function open(id = '') {
  form.value = { ...fresh(), quotationId: id }
  dialog.value = true
}
async function save() {
  if (!form.value.quotationId || !form.value.reference.trim() || form.value.amount <= 0)
    return ElMessage.warning('请选择订单，填写正数金额及流水号')
  busy.value = true
  try {
    await post('/cash', form.value)
    dialog.value = false
    ElMessage.success('收付款流水已登记')
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
  receipts.value.filter(
    (r) =>
      r.kind === 'RECEIPT' &&
      active.value &&
      r.customer_id === active.value.customer_id &&
      r.contract_no === active.value.contract_key &&
      Number(r.amount) > Number(r.refunded),
  ),
)
function openConfirm(c: Contract) {
  active.value = c
  dueDate.value = afterDays(30)
  confirmDialog.value = true
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
    ElMessage.success(`已确认 ${r.confirmed} 个门店订单的应收`)
    await load()
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
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader
      eyebrow="RECEIVABLES & CASH"
      title="回款与应收"
      description="以客户确认订单为依据，登记每一笔回款，跟踪未收余额。"
    >
      <el-button v-if="canFinance()" type="primary" @click="open()">
        <el-icon><Plus /></el-icon>
        登记回款 / 退款
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="cash-metrics">
      <div class="panel compact-metric">
        <span>客户净回款</span>
        <strong>{{ money(received) }}</strong>
        <small>累计收款减累计退款</small>
      </div>
      <div class="panel compact-metric">
        <span>剩余应收</span>
        <strong>{{ money(total) }}</strong>
        <small>{{ balances.length }} 份已确认订单</small>
      </div>
      <div class="panel compact-metric">
        <span>逾期订单</span>
        <strong>
          {{ balances.filter((q) => q.overdue).length }}
          <em>份</em>
        </strong>
        <small>到期且仍有未收余额</small>
      </div>
    </div>
    <section class="panel">
      <div class="filter-bar">
        <el-tabs v-model="tab" class="table-tabs">
          <el-tab-pane label="合同台账" name="contracts" />
          <el-tab-pane label="合同收款" name="receipts" />
          <el-tab-pane label="门店应收" name="balances" />
          <el-tab-pane label="收付款流水" name="entries" />
        </el-tabs>
        <el-input
          v-if="tab === 'balances' || tab === 'entries'"
          v-model="keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索客户、项目或流水号"
        />
      </div>
      <template v-if="tab === 'contracts'">
        <ContractTable
          :contracts="contracts"
          :actions="canFinance() || canWrite()"
          attachments
          @attachments="(c) => ((attachContract = c), (attachDialog = true))"
          @confirm="openConfirm"
          @receipt="
            (c) => (canFinance() ? openContract(c, 'RECEIPT') : ElMessage.warning('登记回款需要财务或管理员权限'))
          "
          @refund="
            (c) => (canFinance() ? openContract(c, 'REFUND') : ElMessage.warning('登记退款需要财务或管理员权限'))
          "
        />
        <div class="panel-bottom-note">
          按合同整笔登记回款时，系统按各门店剩余应收比例自动分摊到门店订单，分摊流水号为“凭证号#序号”。
        </div>
      </template>
      <el-table v-else-if="tab === 'receipts'" :data="receipts">
        <el-table-column label="收付款日期" width="125">
          <template #default="{ row }">{{ day(row.paid_on) }}</template>
        </el-table-column>
        <el-table-column label="合同号 / 客户" min-width="220">
          <template #default="{ row }">
            {{ row.contract_no.startsWith('#') ? '未填合同号' : row.contract_no }}
            <small class="cell-secondary">{{ row.customer_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <span :class="row.kind === 'RECEIPT' ? 'profit-text' : 'danger-text'">
              {{ row.kind === 'RECEIPT' ? '合同回款' : '合同退款' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="金额" align="right" width="150">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="分摊门店" align="right" width="95" prop="allocations" />
        <el-table-column label="凭证号" prop="reference" min-width="160" show-overflow-tooltip />
        <el-table-column label="备注" prop="note" min-width="160" show-overflow-tooltip />
      </el-table>
      <template v-else-if="tab === 'balances'">
        <el-table :data="filteredBalances">
          <el-table-column label="项目" prop="project_name" min-width="260" show-overflow-tooltip />
          <el-table-column label="客户" prop="customer_name" min-width="115" />
          <el-table-column label="确认金额" align="right" min-width="140">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="净回款" align="right" min-width="140">
            <template #default="{ row }">{{ money(row.received) }}</template>
          </el-table-column>
          <el-table-column label="剩余应收" align="right" min-width="140">
            <template #default="{ row }">
              <strong>{{ money(row.outstanding) }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="应收截止" width="130">
            <template #default="{ row }">
              <span :class="row.overdue ? 'danger-text' : ''">
                {{ day(row.due_date) }}{{ row.overdue ? ' 逾期' : '' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column v-if="canFinance()" label="操作" width="95">
            <template #default="{ row }">
              <el-button link type="primary" @click="open(row.id)">登记回款</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="panel-bottom-note">
          历史报价和已发货状态不自动构成应收。请先在客户报价中确认订单、金额及应收日期。
        </div>
      </template>
      <el-table v-else-if="tab === 'entries'" :data="filteredEntries">
        <el-table-column label="收付款日期" width="125">
          <template #default="{ row }">{{ day(row.paid_on) }}</template>
        </el-table-column>
        <el-table-column label="项目 / 客户" min-width="270">
          <template #default="{ row }">
            {{ row.project_name }}
            <small class="cell-secondary">{{ row.customer_name }}</small>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span :class="row.kind === 'RECEIPT' ? 'profit-text' : 'danger-text'">
              {{ row.kind === 'RECEIPT' ? '客户回款' : '客户退款' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="金额" align="right" width="150">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="凭证 / 流水号" prop="reference" min-width="170" show-overflow-tooltip />
        <el-table-column label="备注" prop="note" min-width="180" show-overflow-tooltip />
      </el-table>
    </section>
    <el-dialog v-model="dialog" title="登记客户收付款" width="600px">
      <el-form label-position="top">
        <el-form-item label="已确认订单" required>
          <el-select v-model="form.quotationId" filterable @change="form.reversesId = null">
            <el-option
              v-for="q in balances"
              :key="q.id"
              :value="q.id"
              :label="`${q.project_name} / ${q.customer_name} / 余 ${money(q.outstanding)}`"
            />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="流水类型">
            <el-select v-model="form.kind">
              <el-option label="客户回款" value="RECEIPT" />
              <el-option label="客户退款" value="REFUND" />
            </el-select>
          </el-form-item>
          <el-form-item label="金额（元）" required>
            <el-input-number v-model="form.amount" :min="0.01" :max="99999999999" :precision="2" :controls="false" />
          </el-form-item>
          <el-form-item label="收付款日期" required>
            <el-date-picker v-model="form.paidOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="凭证 / 银行流水号" required>
            <el-input v-model="form.reference" maxlength="150" />
          </el-form-item>
        </div>
        <el-form-item v-if="form.kind === 'REFUND'" label="关联原收款流水" required>
          <el-select v-model="form.reversesId" filterable>
            <el-option
              v-for="e in refundable"
              :key="e.id"
              :value="e.id"
              :label="`${e.reference} / ${money(e.amount)}`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" type="textarea" maxlength="1000" /></el-form-item>
        <div class="inline-note">已入账流水保留历史。错误收款请通过关联退款进行冲正，不直接修改或删除原流水。</div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">登记流水</el-button>
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
    <ContractAttachments v-model="attachDialog" :contract="attachContract" @changed="load" />
  </div>
</template>
