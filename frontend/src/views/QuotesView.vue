<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import QuoteEditor from '../components/QuoteEditor.vue'
import QuoteDetail from '../components/QuoteDetail.vue'
import CostCorrection from '../components/CostCorrection.vue'
import { api, post, canWrite, canFinance, canSeeCost, session } from '../api'
import { activeCompanies, companyLabel, defaultCompany, loadDictionaries } from '../dictionaries'
import type { Quote, Product, Project } from '../types'
import { money, percent, statusName, afterDays } from '../utils'
const route = useRoute(),
  router = useRouter(),
  rows = ref<Quote[]>([]),
  projects = ref<Project[]>([]),
  products = ref<Product[]>([]),
  keyword = ref(''),
  status = ref(''),
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
  contractForm = ref({ contractNo: '', companyId: '', signedOn: '', paymentTerms: '' })
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
const filtered = computed(() =>
  rows.value.filter(
    (q) =>
      (!projectId.value || q.project_id === projectId.value) &&
      (!status.value || q.status === status.value) &&
      (!incomplete.value || !q.complete) &&
      `${q.project_name}${q.customer_name}${q.code}`.toLowerCase().includes(keyword.value.toLowerCase()),
  ),
)
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
          ? '保留每一版报价，让销售价格、内部成本和利润清晰可查。'
          : '保留每一版报价；成本由系统按供应商成本模板核算，财务复核。'
      "
    >
      <el-button v-if="canWrite()" type="primary" @click="create()">
        <el-icon><Plus /></el-icon>
        新建报价
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          clearable
          :prefix-icon="Search"
          placeholder="搜索项目、客户或报价编号"
          @input="page = 1"
        />
        <el-select v-model="status" clearable placeholder="全部状态" @change="page = 1">
          <el-option v-for="(label, key) in statusName" :key="key" :label="label" :value="key" />
        </el-select>
        <el-checkbox v-if="canSeeCost()" v-model="incomplete" @change="page = 1">仅看待补充</el-checkbox>
        <span class="filter-count">{{ filtered.length }} 份报价</span>
      </div>
      <el-table :data="filtered.slice((page - 1) * 12, page * 12)">
        <el-table-column label="项目 / 门店" prop="project_name" min-width="260" show-overflow-tooltip />
        <el-table-column label="客户" prop="customer_name" min-width="115" />
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
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><StatusTag :status="row.status" /></template>
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
      <QuoteDetail v-if="detail" :quote="detail" />
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
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
              placeholder="留空自动编号，如 HT20261115-01"
            />
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
