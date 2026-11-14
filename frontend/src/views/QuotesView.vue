<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import QuoteEditor from '../components/QuoteEditor.vue'
import QuoteDetail from '../components/QuoteDetail.vue'
import { api, post, canWrite } from '../api'
import type { Quote, Product, Project } from '../types'
import { money, percent, statusName, afterDays } from '../utils'
const route = useRoute(),
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
  busy = ref(false)
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
function printQuote() {
  if (detail.value) window.open(`/print/${detail.value.id}`, '_blank', 'noopener,noreferrer')
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
      description="保留每一版报价，让销售价格、内部成本和利润清晰可查。"
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
        <el-checkbox v-model="incomplete" @change="page = 1">仅看待补充</el-checkbox>
        <span class="filter-count">{{ filtered.length }} 份报价</span>
      </div>
      <el-table :data="filtered.slice((page - 1) * 12, page * 12)">
        <el-table-column label="项目 / 门店" prop="project_name" min-width="260" show-overflow-tooltip />
        <el-table-column label="客户" prop="customer_name" min-width="115" />
        <el-table-column label="版本" width="70">
          <template #default="{ row }">V{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="报价金额" align="right" min-width="135">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="成本" align="right" min-width="125">
          <template #default="{ row }">{{ money(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="预计利润" align="right" min-width="125">
          <template #default="{ row }">
            <span :class="row.profit < 0 ? 'danger-text' : 'profit-text'">{{ money(row.profit) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="销售毛利率" align="right" width="120">
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
        <el-button v-if="canWrite()" @click="create(true)">复制为新版本</el-button>
        <el-button v-if="detail && detail.status !== 'DRAFT'" @click="printQuote">生成对客报价单</el-button>
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
          @click="accepting = true"
        >
          确认订单与应收
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="accepting" title="确认客户订单" width="480px">
      <p>确认后，该版本金额将建立应收。每个项目只允许一份已确认订单，请核实客户确认凭据及金额。</p>
      <el-form label-position="top">
        <el-form-item label="应收截止日期" required>
          <el-date-picker v-model="dueDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="accepting = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="transition('accept')">确认并建立应收</el-button>
      </template>
    </el-dialog>
  </div>
</template>
