<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, canWrite } from '../api'
import type { Project, Partner } from '../types'
import { day, today } from '../utils'
const rows = ref<Project[]>([]),
  partners = ref<Partner[]>([]),
  keyword = ref(''),
  brand = ref(''),
  dialog = ref(false),
  editing = ref(''),
  busy = ref(false),
  error = ref(''),
  page = ref(1),
  router = useRouter()
const fresh = () => ({ name: '', brand: '小象', customerId: '', externalId: '', orderDate: today(), note: '' })
const form = ref(fresh())
const filtered = computed(() =>
  rows.value.filter(
    (p) =>
      (!brand.value || p.brand === brand.value) &&
      `${p.name}${p.code}${p.customer_name}${p.external_id || ''}`.toLowerCase().includes(keyword.value.toLowerCase()),
  ),
)
async function load() {
  try {
    ;[rows.value, partners.value] = await Promise.all([api<Project[]>('/projects'), api<Partner[]>('/partners')])
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
      }
    : fresh()
  dialog.value = true
}
async function save() {
  if (!form.value.name.trim() || !form.value.customerId) return ElMessage.warning('请填写项目名称并选择客户')
  busy.value = true
  try {
    const payload = { ...form.value, orderDate: form.value.orderDate || null }
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
        <el-select v-model="brand" clearable placeholder="全部品牌" @change="page = 1">
          <el-option v-for="s in ['小象', '快乐猴', '其他', '未指定']" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <span class="filter-count">共 {{ filtered.length }} 个项目</span>
      </div>
      <el-table :data="filtered.slice((page - 1) * 12, page * 12)">
        <el-table-column label="项目 / 门店" prop="name" min-width="290" show-overflow-tooltip />
        <el-table-column label="客户" prop="customer_name" min-width="120" />
        <el-table-column label="品牌" prop="brand" width="95" />
        <el-table-column label="接单日期" width="120">
          <template #default="{ row }">{{ day(row.order_date) }}</template>
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
          <el-form-item label="所属品牌" required>
            <el-select v-model="form.brand">
              <el-option v-for="b in ['小象', '快乐猴', '其他', '未指定']" :key="b" :value="b" :label="b" />
            </el-select>
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
          <el-form-item label="接单日期">
            <el-date-picker v-model="form.orderDate" value-format="YYYY-MM-DD" type="date" />
          </el-form-item>
          <el-form-item label="云智冷项目 ID">
            <el-input v-model="form.externalId" maxlength="150" placeholder="可稍后关联" />
          </el-form-item>
        </div>
        <el-form-item label="项目备注">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存项目</el-button>
      </template>
    </el-dialog>
  </div>
</template>
