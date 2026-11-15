<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import BusinessSelect from '../components/BusinessSelect.vue'
import CitySelect from '../components/CitySelect.vue'
import { api, post, canWrite, isAdmin, session } from '../api'
import { activeOwners, dict, loadDictionaries } from '../dictionaries'
import type { Project, Partner, ProjectType } from '../types'
import { day, today } from '../utils'
const rows = ref<Project[]>([]),
  partners = ref<Partner[]>([]),
  types = ref<ProjectType[]>([]),
  keyword = ref(''),
  brand = ref(''),
  city = ref(''),
  owner = ref(''),
  selected = ref<Project[]>([]),
  assignDialog = ref(false),
  assign = ref({ ownerId: '', includeContracts: true }),
  dialog = ref(false),
  editing = ref(''),
  busy = ref(false),
  error = ref(''),
  page = ref(1),
  router = useRouter()
const fresh = () => ({
  name: '',
  brand: '未指定',
  customerId: '',
  externalId: '',
  orderDate: today(),
  note: '',
  contractNo: '',
  shippingStatus: '',
  city: '',
  projectTypeId: '',
  ownerId: '',
})
/** 商务只看得到自己的项目，不需要负责人筛选。 */
const showOwner = computed(() => session.user?.role !== 'SALES')
const cities = computed(() => [...new Set(rows.value.map((p) => p.city).filter((c): c is string => !!c))].sort())
const form = ref(fresh())
const filtered = computed(() =>
  rows.value.filter(
    (p) =>
      (!brand.value || p.brand === brand.value) &&
      (!city.value || (city.value === '未填' ? !p.city : p.city === city.value)) &&
      (!owner.value || (owner.value === '-' ? !p.owner_id : p.owner_id === owner.value)) &&
      `${p.name}${p.code}${p.customer_name}${p.external_id || ''}${p.city || ''}`
        .toLowerCase()
        .includes(keyword.value.toLowerCase()),
  ),
)
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
function open(p?: Project) {
  editing.value = p?.id || ''
  form.value = p
    ? {
        name: p.name,
        brand: p.brand,
        customerId: p.customer_id,
        externalId: p.external_id || '',
        orderDate: day(p.order_date) === '—' ? '' : day(p.order_date),
        note: p.note || '',
        contractNo: p.contract_no || '',
        shippingStatus: p.shipping_status || '',
        city: p.city || '',
        projectTypeId: p.project_type_id || '',
        ownerId: p.owner_id || '',
      }
    : fresh()
  dialog.value = true
}
async function save() {
  if (!form.value.name.trim() || !form.value.customerId) return ElMessage.warning('请填写项目名称并选择客户')
  busy.value = true
  try {
    const payload = {
      ...form.value,
      orderDate: form.value.orderDate || null,
      city: form.value.city || null,
      projectTypeId: form.value.projectTypeId || null,
      // 只有管理员可指定负责人；其他角色不提交，后端按本人 / 原值处理
      ownerId: isAdmin() ? form.value.ownerId : null,
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
onMounted(load)
</script>
<template>
  <div>
    <PageHeader
      eyebrow="PROJECT REGISTER"
      title="项目台账"
      description="以门店为单位沉淀业务，关联客户、报价和云智冷项目。"
    >
      <el-button v-if="canWrite()" type="primary" @click="open()">
        <el-icon><Plus /></el-icon>
        登记项目
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="panel">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索门店、客户、编号或云智冷 ID"
          :prefix-icon="Search"
          @input="page = 1"
        />
        <BusinessSelect v-model="brand" clearable placeholder="全部业务线" class="business-filter" @change="page = 1" />
        <el-select v-model="city" clearable filterable placeholder="全部城市" class="small-filter" @change="page = 1">
          <el-option label="未填城市" value="未填" />
          <el-option v-for="c in cities" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select
          v-if="showOwner"
          v-model="owner"
          clearable
          filterable
          placeholder="全部负责人"
          class="small-filter"
          @change="page = 1"
        >
          <el-option label="未分配负责人" value="-" />
          <el-option v-for="o in dict.owners" :key="o.id" :label="o.display_name" :value="o.id" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="isAdmin()" :disabled="!selected.length" @click="assignDialog = true">
          分配负责人{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <span class="filter-count">共 {{ filtered.length }} 个项目</span>
      </div>
      <el-table
        :data="filtered.slice((page - 1) * 12, page * 12)"
        row-key="id"
        @selection-change="(v: Project[]) => (selected = v)"
      >
        <el-table-column v-if="isAdmin()" type="selection" width="40" reserve-selection />
        <el-table-column label="项目 / 门店" prop="name" min-width="290" show-overflow-tooltip />
        <el-table-column label="客户" prop="customer_name" min-width="120" />
        <el-table-column label="业务线 / 类型" width="130">
          <template #default="{ row }">
            {{ row.brand }}
            <small class="cell-secondary">{{ row.project_type_name || '未设类型' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="城市" width="90">
          <template #default="{ row }">
            <span :class="row.city ? '' : 'muted'">{{ row.city || '未填' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="showOwner" label="负责人" width="90">
          <template #default="{ row }">
            <span :class="row.owner_name ? '' : 'muted'">{{ row.owner_name || '未分配' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="接单日期" width="120">
          <template #default="{ row }">{{ day(row.order_date) }}</template>
        </el-table-column>
        <el-table-column label="合同号 / 发货" min-width="170">
          <template #default="{ row }">
            {{ row.contract_no || '—' }}
            <small class="cell-secondary">{{ row.shipping_status || '未登记发货' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="云智冷项目 ID" min-width="160">
          <template #default="{ row }">
            <span :class="row.external_id ? 'mono' : 'muted'">{{ row.external_id || '待关联' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="报价版本" prop="quote_count" width="95" align="center" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canWrite()" link type="primary" @click="open(row)">编辑 / 关联</el-button>
            <el-button link type="primary" @click="router.push(`/quotes?project=${row.id}`)">报价</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="page"
          :page-size="12"
          :total="filtered.length"
          layout="total, prev, pager, next"
        />
      </div>
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑项目与云智冷关联' : '登记新项目'" width="600px">
      <el-form label-position="top">
        <el-form-item label="项目 / 门店名称" required>
          <el-input v-model="form.name" maxlength="300" placeholder="例如：小象上海长临路店" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="业务线" required>
            <BusinessSelect v-model="form.brand" />
          </el-form-item>
          <el-form-item label="客户" required>
            <el-select v-model="form.customerId" filterable :disabled="!!editing" placeholder="选择客户">
              <el-option
                v-for="c in partners.filter((p) => p.kind === 'CUSTOMER')"
                :key="c.id"
                :value="c.id"
                :label="c.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="城市">
            <CitySelect v-model="form.city" placeholder="留空按门店名称识别" />
          </el-form-item>
          <el-form-item label="项目类型">
            <el-select v-model="form.projectTypeId" clearable placeholder="如：小象前置仓">
              <el-option v-for="t in types" :key="t.id" :value="t.id" :label="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="接单日期">
            <el-date-picker v-model="form.orderDate" value-format="YYYY-MM-DD" type="date" />
          </el-form-item>
          <el-form-item label="云智冷项目 ID">
            <el-input v-model="form.externalId" maxlength="150" placeholder="可稍后关联" />
          </el-form-item>
          <el-form-item label="合同号">
            <el-input v-model="form.contractNo" maxlength="100" placeholder="签约后填写，计入合同额" />
          </el-form-item>
          <el-form-item v-if="isAdmin()" label="销售负责人">
            <el-select v-model="form.ownerId" clearable filterable placeholder="新建时默认为本人">
              <el-option v-for="o in activeOwners" :key="o.id" :label="o.display_name" :value="o.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="发货状态">
            <el-select v-model="form.shippingStatus" clearable placeholder="未登记">
              <el-option label="制作中" value="制作中" />
              <el-option label="已发货" value="已发货" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="项目备注">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
      </el-form>
      <div v-if="editing && !isAdmin()" class="inline-note">
        门店所属合同已签订时，调整合同号属于合同变更，须由管理员办理。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存项目</el-button>
      </template>
    </el-dialog>
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
.business-filter {
  width: 170px;
}
.small-filter {
  width: 140px;
}
</style>
