<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import QuoteEditor from '../components/QuoteEditor.vue'
import QuoteDetail from '../components/QuoteDetail.vue'
import CostCorrection from '../components/CostCorrection.vue'
import CustomerTemplateDialog, { type TemplateSeed } from '../components/CustomerTemplateDialog.vue'
import { api, post, canWrite, canFinance, canSeeCost, session } from '../api'
import { activeCompanies, companyLabel, defaultCompany, loadDictionaries } from '../dictionaries'
import type { Quote, Product, Project } from '../types'
import { money, percent, afterDays, day, today } from '../utils'
const route = useRoute(),
  router = useRouter(),
  rows = ref<Quote[]>([]),
  projects = ref<Project[]>([]),
  products = ref<Product[]>([]),
  keyword = ref(''),
  customer = ref(''),
  // 默认只看跟进中的报价；从门店或“待补充”入口进来时看全部，避免已转合同的被筛掉
  quick = ref(route.query.project || route.query.incomplete === '1' ? 'ALL' : 'ACTIVE'),
  incomplete = ref(route.query.incomplete === '1'),
  page = ref(1),
  editor = ref(false),
  detail = ref<Quote>(),
  detailOpen = ref(false),
  seed = ref<Quote>(),
  error = ref(''),
  accepting = ref(false),
  dueDate = ref(afterDays(30)),
  busy = ref(false),
  fixing = ref(false),
  contractForm = ref({ contractNo: '', companyId: '', signedOn: '', paymentTerms: '' }),
  numberByRule = ref(true)
const showOwner = computed(() => session.user?.role !== 'SALES')
/** 客户确认后生成正式合同：确认应收、门店写入合同号、建立合同记录。 */
function openContract() {
  const q = detail.value
  if (!q) return
  contractForm.value = {
    contractNo: q.contract_no || '',
    companyId: q.company_id || defaultCompany.value?.id || '',
    signedOn: '',
    paymentTerms: '',
  }
  dueDate.value = afterDays(30)
  loadDictionaries().catch(() => {})
  accepting.value = true
  // 门店还没有合同号：按该客户的编号规律预填下一个合同号（可改）
  if (!q.contract_no && q.customer_id)
    api<{ number: string; byCustomerRule: boolean }>(
      `/contracts/next-number?customerId=${encodeURIComponent(q.customer_id)}`,
    )
      .then((r) => {
        if (accepting.value && !contractForm.value.contractNo) contractForm.value.contractNo = r.number
        numberByRule.value = r.byCustomerRule
      })
      .catch(() => {})
}
async function makeContract() {
  const q = detail.value
  if (!q) return
  if (q.status !== 'ACCEPTED' && !dueDate.value) return ElMessage.warning('请填写应收截止日期')
  busy.value = true
  try {
    const r = await post<{ customerId: string; contractKey: string }>(`/contracts/from-quote/${q.id}`, {
      contractNo: contractForm.value.contractNo || null,
      companyId: contractForm.value.companyId || null,
      signedOn: contractForm.value.signedOn || null,
      dueDate: q.status === 'ACCEPTED' ? null : dueDate.value,
      paymentTerms: contractForm.value.paymentTerms || null,
    })
    accepting.value = false
    detailOpen.value = false
    ElMessage.success(`合同 ${r.contractKey} 已生成`)
    router.push({ path: '/cash', query: { tab: 'contracts', customer: r.customerId, contract: r.contractKey } })
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
const projectId = computed(() => String(route.query.project || ''))
/**
 * 跟进阶段筛选：报价转成合同前都留在这里跟进；转合同后到合同台账、项目台账办理，这里仍可查。
 * “跟进中”= 草稿 + 待客户确认 + 客户已确认待生成合同。
 */
const STAGES: { key: string; label: string; test: (q: Quote) => boolean }[] = [
  { key: 'ACTIVE', label: '跟进中', test: (q) => ['DRAFT', 'ISSUED', 'ACCEPTED'].includes(q.stage) },
  { key: 'DRAFT', label: '草稿', test: (q) => q.stage === 'DRAFT' },
  { key: 'ISSUED', label: '待客户确认', test: (q) => q.stage === 'ISSUED' },
  { key: 'ACCEPTED', label: '待生成合同', test: (q) => q.stage === 'ACCEPTED' },
  { key: 'CONTRACTED', label: '已转合同', test: (q) => q.stage === 'CONTRACTED' },
  { key: 'LOST', label: '未成交', test: (q) => q.stage === 'LOST' },
  { key: 'ALL', label: '全部', test: () => true },
]
const customers = computed(() =>
  [...new Map(rows.value.map((q) => [q.customer_id || '', q.customer_name])).entries()]
    .filter(([id]) => id)
    .sort((a, b) => a[1].localeCompare(b[1], 'zh-CN')),
)
const base = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return rows.value.filter(
    (q) =>
      (!projectId.value || q.project_id === projectId.value) &&
      (!customer.value || q.customer_id === customer.value) &&
      (!incomplete.value || !q.complete) &&
      `${q.project_name}${q.customer_name}${q.code}`.toLowerCase().includes(k),
  )
})
const counts = computed(() => Object.fromEntries(STAGES.map((s) => [s.key, base.value.filter(s.test).length])))
const filtered = computed(() => base.value.filter((STAGES.find((s) => s.key === quick.value) || STAGES[0]).test))
watch([keyword, customer, quick, incomplete], () => (page.value = 1))
/** 待客户确认的报价过了有效期：需要重新报价或标记未成交。 */
const expired = (q: Quote) => q.stage === 'ISSUED' && !!q.valid_until && day(q.valid_until) < today()

// ---------- 未成交 / 恢复跟进 ----------
const LOST_REASONS = ['价格未谈拢', '客户选择了其他供应商', '项目取消或延期', '重复报价 / 录入有误']
const losing = ref(false),
  lostReason = ref('')
function openLost() {
  lostReason.value = ''
  losing.value = true
}
async function markLost() {
  if (!detail.value) return
  if (!lostReason.value.trim()) return ElMessage.warning('请选择或填写未成交原因')
  busy.value = true
  try {
    await post(`/quotes/${detail.value.id}/lose`, { reason: lostReason.value.trim() })
    losing.value = false
    await saved(detail.value.id)
    ElMessage.success('已标记为未成交，可在“未成交”中查看或恢复跟进')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function reopen() {
  if (!detail.value) return
  busy.value = true
  try {
    await post(`/quotes/${detail.value.id}/reopen`, {})
    await saved(detail.value.id)
    ElMessage.success('已恢复跟进')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

// ---------- 存为客户报价模板 ----------
const templateDialog = ref(false),
  templateSeed = ref<TemplateSeed>()
function saveAsTemplate() {
  const q = detail.value
  if (!q) return
  templateSeed.value = {
    customerId: q.customer_id,
    projectTypeId: q.project_type_id || '',
    items: q.items.map((i) => ({ product_id: i.product_id, quantity: i.quantity, unit_price: i.unit_price })),
    from: `明细来自报价 ${q.code}（${q.project_name}），保存后该客户此项目类型的新报价按它带出。`,
  }
  templateDialog.value = true
}
function more(command: string) {
  if (command === 'template') saveAsTemplate()
  else if (command === 'lost') openLost()
  else if (command === 'reopen') reopen()
}
async function load() {
  try {
    ;[rows.value, projects.value, products.value] = await Promise.all([
      api<Quote[]>('/quotes'),
      api<Project[]>('/projects'),
      api<Product[]>('/products'),
    ])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function show(id: string) {
  try {
    detail.value = await api<Quote>(`/quotes/${id}`)
    detailOpen.value = true
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
function create(copy = false) {
  seed.value = copy ? detail.value : undefined
  editor.value = true
}
async function saved(id: string) {
  await load()
  await show(id)
}
async function transition(action: string) {
  if (!detail.value) return
  busy.value = true
  try {
    await post(`/quotes/${detail.value.id}/${action}`, { dueDate: action === 'accept' ? dueDate.value : null })
    accepting.value = false
    await saved(detail.value.id)
    ElMessage.success(action === 'issue' ? '报价已出具，可生成对客报价单' : '订单已确认，已建立应收')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
/** 一键输出：草稿先确认出具（固定模板内容），再打开报价单或合同打印页。 */
async function output(kind: 'quote' | 'contract') {
  const q = detail.value
  if (!q) return
  if (q.status === 'DRAFT') {
    if (!canWrite()) return ElMessage.warning('草稿需由商务或管理员出具后才能输出')
    try {
      await ElMessageBox.confirm('输出前需要先出具此报价（出具后不能再修改，只能复制为新版本）。继续？', '出具报价', {
        confirmButtonText: '出具并输出',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    // 先同步打开窗口，避免出具请求返回后被浏览器拦截弹窗
    const win = window.open('about:blank', '_blank')
    busy.value = true
    try {
      await post(`/quotes/${q.id}/issue`, {})
      await saved(q.id)
      const url = kind === 'quote' ? `/print/${q.id}` : `/print/${q.id}/contract`
      // 浏览器拦截新窗口时，在当前页打开
      if (win) win.location.href = url
      else window.location.assign(url)
    } catch (e) {
      win?.close()
      ElMessage.error((e as Error).message)
    } finally {
      busy.value = false
    }
    return
  }
  window.open(kind === 'quote' ? `/print/${q.id}` : `/print/${q.id}/contract`, '_blank', 'noopener,noreferrer')
}
onMounted(async () => {
  await load()
  if (route.query.new === '1') create()
  if (route.query.detail) await show(String(route.query.detail))
})
</script>
<template>
  <div>
    <PageHeader
      eyebrow="CUSTOMER QUOTATIONS"
      title="客户报价"
      :description="
        canSeeCost()
          ? '合同签订前的报价都在这里跟进；客户确认并生成合同后，转入合同台账和项目台账办理。'
          : '合同签订前的报价都在这里跟进；生成合同后转入合同台账和项目台账。成本由系统核算，财务复核。'
      "
    >
      <el-button v-if="canWrite()" type="primary" @click="create()">
        <el-icon><Plus /></el-icon>
        新建报价
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="panel">
      <div class="quick-row">
        <template v-for="s in STAGES" :key="s.key">
          <span v-if="s.key === 'CONTRACTED'" class="quick-sep"></span>
          <button type="button" :class="['quick', { on: quick === s.key }]" @click="quick = s.key">
            {{ s.label }}
            <em>{{ counts[s.key] }}</em>
          </button>
        </template>
      </div>
      <div class="filter-bar quote-filters">
        <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索项目、客户或报价编号" />
        <el-select v-model="customer" clearable filterable placeholder="全部客户">
          <el-option v-for="[id, name] in customers" :key="id" :label="name" :value="id" />
        </el-select>
        <el-checkbox v-if="canSeeCost()" v-model="incomplete">仅看待补充</el-checkbox>
        <span class="filter-count">{{ filtered.length }} 份报价</span>
      </div>
      <el-table
        :data="filtered.slice((page - 1) * 12, page * 12)"
        :empty-text="quick === 'ACTIVE' ? '没有跟进中的报价' : '没有符合条件的报价'"
      >
        <el-table-column label="项目 / 门店" prop="project_name" min-width="230" show-overflow-tooltip />
        <el-table-column label="客户" prop="customer_name" min-width="115" />
        <el-table-column label="阶段" width="150">
          <template #default="{ row }">
            <StatusTag :status="row.stage" kind="stage" />
            <small v-if="row.stage === 'LOST' && row.lost_reason" class="cell-secondary" :title="row.lost_reason">
              {{ row.lost_reason }}
            </small>
            <small
              v-else-if="row.stage === 'ISSUED' && row.valid_until"
              :class="['cell-secondary', { 'danger-text': expired(row) }]"
            >
              有效期至 {{ day(row.valid_until) }}{{ expired(row) ? ' 已过期' : '' }}
            </small>
            <small v-else-if="row.stage === 'CONTRACTED' && row.contract_no" class="cell-secondary">
              {{ row.contract_no }}
            </small>
          </template>
        </el-table-column>
        <el-table-column v-if="showOwner" label="负责人" width="90">
          <template #default="{ row }">
            <span :class="row.owner_name ? '' : 'muted'">{{ row.owner_name || '未分配' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="70">
          <template #default="{ row }">V{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="报价金额" align="right" min-width="135">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost()" label="成本" align="right" min-width="125">
          <template #default="{ row }">{{ money(row.cost) }}</template>
        </el-table-column>
        <el-table-column v-if="canSeeCost()" label="预计利润" align="right" min-width="125">
          <template #default="{ row }">
            <span :class="row.profit < 0 ? 'danger-text' : 'profit-text'">{{ money(row.profit) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canSeeCost()" label="销售毛利率" align="right" width="120">
          <template #default="{ row }">{{ percent(row.gross_margin) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="85">
          <template #default="{ row }"><el-button link type="primary" @click="show(row.id)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :total="filtered.length"
          :page-size="12"
          layout="total, prev, pager, next"
        />
      </div>
    </div>
    <QuoteEditor
      v-model="editor"
      :projects="projects"
      :products="products"
      :project-id="projectId"
      :seed="seed"
      @saved="saved"
    />
    <el-dialog v-model="detailOpen" title="报价详情" width="1100px">
      <div v-if="detail?.stage === 'LOST'" class="inline-note warning">
        该报价已标记为未成交{{ detail.lost_at ? `（${day(detail.lost_at)}）` : '' }}：{{ detail.lost_reason }}
      </div>
      <QuoteDetail v-if="detail" :quote="detail" />
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-dropdown v-if="canWrite() && detail" class="more-menu" trigger="click" @command="more">
          <el-button>
            更多
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="template">存为客户报价模板</el-dropdown-item>
              <el-dropdown-item v-if="['DRAFT', 'ISSUED', 'ACCEPTED'].includes(detail.stage)" command="lost">
                标记未成交
              </el-dropdown-item>
              <el-dropdown-item v-if="detail.stage === 'LOST'" command="reopen">恢复跟进</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          v-if="detail?.contract_no && detail.customer_id"
          @click="
            router.push({
              path: '/cash',
              query: { tab: 'contracts', customer: detail.customer_id, contract: detail.contract_no },
            })
          "
        >
          查看合同 {{ detail.contract_no }}
        </el-button>
        <el-button v-if="canWrite()" @click="create(true)">复制为新版本</el-button>
        <el-button v-if="canFinance() && detail" @click="fixing = true">修正成本</el-button>
        <el-button v-if="detail" :loading="busy" @click="output('quote')">输出报价单</el-button>
        <el-button v-if="detail" :loading="busy" @click="output('contract')">输出合同</el-button>
        <el-button
          v-if="canWrite() && detail?.status === 'DRAFT'"
          type="primary"
          :loading="busy"
          @click="transition('issue')"
        >
          出具报价
        </el-button>
        <el-button
          v-if="canWrite() && ['ISSUED', 'HISTORICAL'].includes(detail?.status || '')"
          type="primary"
          @click="openContract"
        >
          客户确认 · 生成合同
        </el-button>
        <el-button
          v-if="canWrite() && detail?.status === 'ACCEPTED' && !detail?.contract_no"
          type="primary"
          @click="openContract"
        >
          生成合同
        </el-button>
      </template>
    </el-dialog>
    <CostCorrection v-model="fixing" :quote="detail" @saved="detail && saved(detail.id)" />
    <CustomerTemplateDialog v-model="templateDialog" :seed="templateSeed" />
    <el-dialog v-model="losing" title="标记未成交" width="480px">
      <p v-if="detail" class="dialog-lead">
        {{ detail.project_name }} · V{{ detail.version }} ·
        {{ money(detail.amount) }}。标记后退出“跟进中”，不计入待确认金额；之后可以恢复跟进。
      </p>
      <el-form label-position="top">
        <el-form-item label="未成交原因" required>
          <el-select
            v-model="lostReason"
            filterable
            allow-create
            default-first-option
            placeholder="选择常见原因，或直接输入"
          >
            <el-option v-for="r in LOST_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="detail?.stage === 'ACCEPTED'" class="inline-note warning">
        该报价客户已确认并建立了应收；标记未成交会撤销这笔应收。
      </div>
      <template #footer>
        <el-button @click="losing = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="markLost">确认未成交</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="accepting" title="客户确认 · 生成正式合同" width="560px">
      <p class="dialog-lead" v-if="detail">
        {{ detail.project_name }} ·
        {{ money(detail.amount) }}。确认后该版本金额建立应收，门店写入合同号并在合同台账中建立合同。
      </p>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="合同号">
            <el-input
              v-model="contractForm.contractNo"
              maxlength="100"
              :disabled="!!detail?.contract_no"
              placeholder="自动编号"
            />
            <small v-if="!detail?.contract_no" class="muted">
              {{
                numberByRule
                  ? '已按该客户的合同号规律自动编号，可修改。'
                  : '该客户还没有合同代码，暂用通用编号；可在 基础资料 → 客户 中填写合同代码。'
              }}
            </small>
          </el-form-item>
          <el-form-item label="我方签约主体">
            <el-select v-model="contractForm.companyId">
              <el-option v-for="c in activeCompanies" :key="c.id" :value="c.id" :label="companyLabel(c)" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="detail?.status !== 'ACCEPTED'" label="应收截止日期" required>
            <el-date-picker v-model="dueDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="签订日期">
            <el-date-picker
              v-model="contractForm.signedOn"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="未签订先留空"
            />
          </el-form-item>
        </div>
        <el-form-item label="付款条款">
          <el-input
            v-model="contractForm.paymentTerms"
            maxlength="1000"
            placeholder="如：发货前预付 30%，验收后 30 日内付清"
          />
        </el-form-item>
      </el-form>
      <div class="inline-note">
        填了签订日期即为“已签订”，否则为“拟定中”（可继续修改，签订时补填日期）。已签订合同的任何修改都需管理员批准，并保留完整修改记录。
      </div>
      <template #footer>
        <el-button @click="accepting = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="makeContract">生成合同</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.quote-filters > .el-input {
  max-width: 300px;
}
.quote-filters > .el-select {
  width: 200px;
}
.more-menu {
  margin: 0 12px;
  vertical-align: middle;
}
</style>
