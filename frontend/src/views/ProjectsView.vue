<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CopyDocument, Plus, Refresh, Search } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import BusinessSelect from '../components/BusinessSelect.vue'
import CitySelect from '../components/CitySelect.vue'
import ExcelTools from '../components/ExcelTools.vue'
import ProjectDeliveryDialog from '../components/ProjectDeliveryDialog.vue'
import { api, post, canApprove, canWrite, isAdmin, session } from '../api'
import { activeOwners, dict, loadDictionaries } from '../dictionaries'
import type { Partner, Project, ProjectType } from '../types'
import { copyText, day, deliveryText, today } from '../utils'

/**
 * 项目台账：门店项目管理——登记、负责人、收货信息、预计发货、发货与物流。
 * 报价、合同、开票、回款等财务事项在“客户报价”“合同台账”中办理。
 */
const route = useRoute(),
  router = useRouter()
const rows = ref<Project[]>([]),
  partners = ref<Partner[]>([]),
  types = ref<ProjectType[]>([]),
  error = ref(''),
  busy = ref(false),
  page = ref(1)

// ---------- 筛选 ----------
const PAGE = 12
const keyword = ref(''),
  customer = ref(String(route.query.customer || '')),
  brand = ref(''),
  city = ref(''),
  owner = ref(''),
  quick = ref('ALL')
const todayStr = today()
const inDays = (n: number) => {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const shipped = (p: Project) => p.shipping_status === '已发货'
const lateShip = (p: Project) => !shipped(p) && !!p.expected_ship_on && day(p.expected_ship_on) < todayStr
const QUICK: { key: string; label: string; test: (p: Project) => boolean }[] = [
  { key: 'ALL', label: '全部', test: () => true },
  { key: 'PENDING', label: '待发货', test: (p) => !shipped(p) },
  {
    key: 'WEEK',
    label: '7 天内预计发货',
    test: (p) =>
      !shipped(p) &&
      !!p.expected_ship_on &&
      day(p.expected_ship_on) >= todayStr &&
      day(p.expected_ship_on) <= inDays(7),
  },
  { key: 'LATE', label: '超期未发', test: lateShip },
  { key: 'MAKING', label: '制作中', test: (p) => p.shipping_status === '制作中' },
  { key: 'SHIPPED', label: '已发货', test: shipped },
  { key: 'NO_ADDRESS', label: '缺收货信息', test: (p) => !p.ship_address || !p.consignee || !p.consignee_phone },
  { key: 'NO_TRACKING', label: '已发货缺运单', test: (p) => shipped(p) && !p.tracking_no },
]
const showOwner = computed(() => session.user?.role !== 'SALES')
const customers = computed(() => partners.value.filter((p) => p.kind === 'CUSTOMER'))
const cities = computed(() => [...new Set(rows.value.map((p) => p.city).filter((c): c is string => !!c))].sort())
const base = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return rows.value.filter(
    (p) =>
      (!customer.value || p.customer_id === customer.value) &&
      (!brand.value || p.brand === brand.value) &&
      (!city.value || (city.value === '未填' ? !p.city : p.city === city.value)) &&
      (!owner.value || (owner.value === '-' ? !p.owner_id : p.owner_id === owner.value)) &&
      (!k ||
        [
          p.name,
          p.code,
          p.customer_name,
          p.external_id,
          p.city,
          p.contract_no,
          p.consignee,
          p.consignee_phone,
          p.ship_address,
          p.tracking_no,
        ]
          .filter(Boolean)
          .join(' ')
          .toLowerCase()
          .includes(k)),
  )
})
const counts = computed(() => Object.fromEntries(QUICK.map((q) => [q.key, base.value.filter(q.test).length])))
const filtered = computed(() => base.value.filter((QUICK.find((q) => q.key === quick.value) || QUICK[0]).test))
watch([keyword, customer, brand, city, owner, quick], () => (page.value = 1))

async function load() {
  try {
    ;[rows.value, partners.value, types.value] = await Promise.all([
      api<Project[]>('/projects'),
      api<Partner[]>('/partners'),
      api<ProjectType[]>('/project-types'),
      loadDictionaries(),
    ]).then(([a, b, c]) => [a, b, c] as const)
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}

// ---------- 登记 / 编辑 ----------
const dialog = ref(false),
  editing = ref('')
const fresh = () => ({
  name: '',
  brand: '未指定',
  customerId: customer.value || '',
  externalId: '',
  orderDate: today(),
  note: '',
  contractNo: '',
  city: '',
  projectTypeId: '',
  ownerId: '',
  consignee: '',
  consigneePhone: '',
  shipAddress: '',
  expectedShipOn: '',
})
const form = ref(fresh())
function open(p?: Project) {
  editing.value = p?.id || ''
  form.value = p
    ? {
        name: p.name,
        brand: p.brand,
        customerId: p.customer_id,
        externalId: p.external_id || '',
        orderDate: p.order_date ? day(p.order_date) : '',
        note: p.note || '',
        contractNo: p.contract_no || '',
        city: p.city || '',
        projectTypeId: p.project_type_id || '',
        ownerId: p.owner_id || '',
        consignee: '',
        consigneePhone: '',
        shipAddress: '',
        expectedShipOn: '',
      }
    : fresh()
  dialog.value = true
}
async function save() {
  const f = form.value
  if (!f.name.trim() || !f.customerId) return ElMessage.warning('请填写门店名称并选择客户')
  busy.value = true
  try {
    const payload = {
      name: f.name,
      brand: f.brand,
      customerId: f.customerId,
      externalId: f.externalId,
      orderDate: f.orderDate || null,
      note: f.note,
      contractNo: f.contractNo,
      city: f.city || null,
      projectTypeId: f.projectTypeId || null,
      // 只有管理员可指定负责人；其他角色不提交，后端按本人 / 原值处理
      ownerId: canApprove() ? f.ownerId : null,
      // 新登记时一并录入收货信息；修改发货物流请用“发货物流”
      delivery: editing.value
        ? null
        : {
            consignee: f.consignee,
            consigneePhone: f.consigneePhone,
            shipAddress: f.shipAddress,
            expectedShipOn: f.expectedShipOn || null,
          },
    }
    if (editing.value) await api(`/projects/${editing.value}`, { method: 'PUT', body: JSON.stringify(payload) })
    else await post('/projects', payload)
    dialog.value = false
    ElMessage.success('项目信息已保存')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

// ---------- 发货物流 ----------
const deliveryOpen = ref(false),
  deliveryProject = ref<Project | null>(null)
function openDelivery(p: Project) {
  deliveryProject.value = p
  deliveryOpen.value = true
}
async function copyDelivery(p: Project) {
  if (!p.ship_address && !p.consignee) return ElMessage.warning('该门店还没有收货信息')
  if (await copyText(deliveryText(p))) ElMessage.success('邮寄信息已复制')
  else ElMessage.warning('浏览器未允许复制，请手动选择文本')
}

// ---------- 负责人 ----------
const selected = ref<Project[]>([]),
  assignDialog = ref(false),
  assign = ref({ ownerId: '', includeContracts: true })
async function saveAssign() {
  busy.value = true
  try {
    const r = await post<{ projects: number; contracts: number }>('/projects/owner', {
      projectIds: selected.value.map((p) => p.id),
      ownerId: assign.value.ownerId || null,
      includeContracts: assign.value.includeContracts,
    })
    assignDialog.value = false
    ElMessage.success(`已分配 ${r.projects} 个门店、${r.contracts} 份合同`)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}

const goContract = (p: Project) =>
  router.push({ path: '/cash', query: { tab: 'contracts', customer: p.customer_id, contract: p.contract_no } })

onMounted(async () => {
  await load()
  // 从合同台账跳转：直接打开该门店的发货物流
  const target = route.query.delivery && rows.value.find((p) => p.id === route.query.delivery)
  if (target) openDelivery(target)
})
</script>
<template>
  <div>
    <PageHeader
      eyebrow="PROJECT REGISTER"
      title="项目台账"
      description="门店项目执行：负责人、收货信息、预计发货与物流跟踪。"
    >
      <ExcelTools dataset="projects" title="项目台账" :can-import="isAdmin()" @imported="load" />
      <el-button v-if="canWrite()" type="primary" @click="open()">
        <el-icon><Plus /></el-icon>
        登记项目
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="panel">
      <div class="quick-row">
        <button
          v-for="q in QUICK"
          :key="q.key"
          type="button"
          :class="['quick', { on: quick === q.key, warn: q.key === 'LATE' && counts.LATE > 0 }]"
          @click="quick = q.key"
        >
          {{ q.label }}
          <em>{{ counts[q.key] }}</em>
        </button>
      </div>
      <div class="filter-bar project-filters">
        <el-input
          v-model="keyword"
          clearable
          :prefix-icon="Search"
          placeholder="门店、编号、收货人、电话、地址或运单号"
        />
        <el-select v-model="customer" clearable filterable placeholder="全部客户">
          <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <BusinessSelect v-model="brand" clearable placeholder="全部业务线" class="business-filter" />
        <el-select v-model="city" clearable filterable placeholder="全部城市">
          <el-option label="未填城市" value="未填" />
          <el-option v-for="c in cities" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-if="showOwner" v-model="owner" clearable filterable placeholder="全部负责人">
          <el-option label="未分配负责人" value="-" />
          <el-option v-for="o in dict.owners" :key="o.id" :label="o.display_name" :value="o.id" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="canApprove()" :disabled="!selected.length" @click="assignDialog = true">
          分配负责人{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <span class="filter-count">共 {{ filtered.length }} 个项目</span>
      </div>
      <el-table
        :data="filtered.slice((page - 1) * PAGE, page * PAGE)"
        row-key="id"
        @selection-change="(v: Project[]) => (selected = v)"
      >
        <el-table-column v-if="canApprove()" type="selection" width="40" reserve-selection />
        <el-table-column label="项目 / 门店" min-width="230">
          <template #default="{ row }">
            <span class="ellipsis-line" :title="row.name">{{ row.name }}</span>
            <small class="cell-secondary">
              {{ row.customer_name }} · {{ row.brand }}{{ row.city ? ` · ${row.city}` : '' }}
            </small>
          </template>
        </el-table-column>
        <el-table-column v-if="showOwner" label="负责人" width="84">
          <template #default="{ row }">
            <span :class="row.owner_name ? '' : 'muted'">{{ row.owner_name || '未分配' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="接单 / 合同" width="156">
          <template #default="{ row }">
            {{ day(row.order_date) }}
            <el-button v-if="row.contract_no" link type="primary" class="cell-link" @click="goContract(row)">
              {{ row.contract_no }}
            </el-button>
            <small v-else class="cell-secondary muted">未签合同</small>
          </template>
        </el-table-column>
        <el-table-column label="发货" width="132">
          <template #default="{ row }">
            <span
              :class="['ship-tag', row.shipping_status === '已发货' ? 'done' : row.shipping_status ? 'making' : 'none']"
            >
              {{ row.shipping_status || '未登记' }}
            </span>
            <small v-if="row.shipping_status === '已发货' && row.shipped_on" class="cell-secondary">
              {{ day(row.shipped_on) }} 发出
            </small>
            <small v-else-if="row.expected_ship_on" :class="['cell-secondary', { 'danger-text': lateShip(row) }]">
              预计 {{ day(row.expected_ship_on) }}{{ lateShip(row) ? ' 已超期' : '' }}
            </small>
          </template>
        </el-table-column>
        <el-table-column label="物流" min-width="150">
          <template #default="{ row }">
            <template v-if="row.tracking_no">
              {{ row.courier || '快递' }}
              <small class="cell-secondary mono">{{ row.tracking_no }}</small>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="收货信息" min-width="230">
          <template #default="{ row }">
            <template v-if="row.consignee || row.ship_address">
              {{ row.consignee || '—' }} {{ row.consignee_phone || '' }}
              <small class="cell-secondary ellipsis-line" :title="row.ship_address">
                {{ row.ship_address || '未填地址' }}
              </small>
            </template>
            <span v-else class="muted">未填</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="272" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite()" link type="primary" @click="openDelivery(row)">发货物流</el-button>
            <el-button link :icon="CopyDocument" @click="copyDelivery(row)">复制</el-button>
            <el-button v-if="canWrite()" link @click="open(row)">编辑</el-button>
            <el-button link @click="router.push(`/quotes?project=${row.id}`)">报价</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="PAGE"
          :total="filtered.length"
          layout="total, prev, pager, next"
        />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑项目' : '登记新项目'" width="640px">
      <el-form label-position="top">
        <el-form-item label="门店名称" required>
          <el-input v-model="form.name" maxlength="300" placeholder="例如：小象上海长临路店" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="客户" required>
            <el-select v-model="form.customerId" filterable :disabled="!!editing" placeholder="选择客户">
              <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务线" required><BusinessSelect v-model="form.brand" /></el-form-item>
          <el-form-item label="城市"><CitySelect v-model="form.city" placeholder="留空按门店名称识别" /></el-form-item>
          <el-form-item label="项目类型">
            <el-select v-model="form.projectTypeId" clearable placeholder="如：小象前置仓">
              <el-option v-for="t in types" :key="t.id" :value="t.id" :label="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="接单日期">
            <el-date-picker v-model="form.orderDate" value-format="YYYY-MM-DD" type="date" />
          </el-form-item>
          <el-form-item v-if="canApprove()" label="销售负责人">
            <el-select v-model="form.ownerId" clearable filterable placeholder="新建时默认为本人">
              <el-option v-for="o in activeOwners" :key="o.id" :label="o.display_name" :value="o.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="合同号">
            <el-input v-model="form.contractNo" maxlength="100" placeholder="通常由报价生成合同时自动写入" />
          </el-form-item>
          <el-form-item label="云智冷项目 ID">
            <el-input v-model="form.externalId" maxlength="150" placeholder="可稍后关联" />
          </el-form-item>
        </div>
        <template v-if="!editing">
          <div class="section">收货信息</div>
          <div class="form-grid">
            <el-form-item label="收货人"><el-input v-model="form.consignee" maxlength="60" /></el-form-item>
            <el-form-item label="收货电话"><el-input v-model="form.consigneePhone" maxlength="40" /></el-form-item>
            <el-form-item label="预计发货日期">
              <el-date-picker v-model="form.expectedShipOn" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </div>
          <el-form-item label="收货地址"><el-input v-model="form.shipAddress" maxlength="300" /></el-form-item>
        </template>
        <el-form-item label="项目备注">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="2000" />
        </el-form-item>
      </el-form>
      <div v-if="editing" class="inline-note">
        收货信息、发货与快递单号请在“发货物流”中维护。门店所属合同已签订时，调整合同号须由管理员办理。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存项目</el-button>
      </template>
    </el-dialog>

    <ProjectDeliveryDialog v-model="deliveryOpen" :project="deliveryProject" @saved="load" />

    <el-dialog v-model="assignDialog" title="分配销售负责人" width="460px">
      <p class="dialog-lead">已选 {{ selected.length }} 个门店。负责人只能查看和办理自己负责的门店、报价与合同。</p>
      <el-form label-position="top">
        <el-form-item label="负责人">
          <el-select v-model="assign.ownerId" clearable filterable placeholder="留空为取消分配">
            <el-option v-for="o in activeOwners" :key="o.id" :label="o.display_name" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-checkbox v-model="assign.includeContracts">同时把这些门店所属合同的负责人一并调整</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveAssign">确认分配</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.quick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 24px 0;
}
.quick {
  border: 1px solid #dfe6dc;
  background: #fff;
  color: #4b5a52;
  border-radius: 14px;
  padding: 3px 10px;
  font-size: 12px;
  line-height: 18px;
  cursor: pointer;
}
.quick em {
  font-style: normal;
  color: #8a958e;
  margin-left: 2px;
  font-variant-numeric: tabular-nums;
}
.quick.warn {
  border-color: #e7c77d;
  color: #8a6a1f;
}
.quick.on {
  background: #24634f;
  border-color: #24634f;
  color: #fff;
}
.quick.on em {
  color: #d5e7de;
}
.project-filters {
  flex-wrap: wrap;
  gap: 8px;
}
.project-filters > .el-input {
  max-width: 280px;
}
.project-filters > .el-select,
.business-filter {
  width: 140px;
}
.ship-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.ship-tag.done {
  color: #24634f;
  background: #e6f0ea;
}
.ship-tag.making {
  color: #3b5f8a;
  background: #e7eef7;
}
.ship-tag.none {
  color: #6b7a72;
  background: #eef1ec;
}
.cell-secondary {
  display: block;
}
.cell-link {
  display: block;
  height: auto;
  padding: 0;
  font-size: 12px;
}
.ellipsis-line {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.section {
  margin: 4px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #253731;
}
</style>
