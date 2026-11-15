<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { api, canWrite } from '../api'
import CustomerTemplateDialog, { type TemplateSeed } from './CustomerTemplateDialog.vue'
import type { CustomerTemplate, ProjectType } from '../types'
import { day, money } from '../utils'

/** 客户报价模板列表：每个客户在每种项目类型下的协议配置与售价。 */
const templates = ref<CustomerTemplate[]>([]),
  types = ref<ProjectType[]>([]),
  keyword = ref(''),
  dialog = ref(false),
  seed = ref<TemplateSeed>()

async function load() {
  try {
    ;[templates.value, types.value] = await Promise.all([
      api<CustomerTemplate[]>('/customer-templates'),
      api<ProjectType[]>('/project-types'),
    ])
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return templates.value.filter((t) => !k || `${t.customer_name}${t.project_type_name}`.toLowerCase().includes(k))
})
const setPrice = (t: CustomerTemplate) =>
  t.items.reduce((s, i) => s + Number(i.quantity) * Number(i.unit_price || 0), 0)
/** 同样的产品与数量按项目类型标准售价计算的金额；模板里有标准配置以外的产品或缺标准价时无法对照。 */
function standardPrice(t: CustomerTemplate) {
  const type = types.value.find((x) => x.id === t.project_type_id)
  let sum = 0
  for (const i of t.items) {
    const p = type?.items.find((x) => x.product_id === i.product_id)?.unit_price
    if (p == null) return null
    sum += Number(i.quantity) * Number(p)
  }
  return sum
}
function open(t?: CustomerTemplate) {
  seed.value = t ? { customerId: t.customer_id, projectTypeId: t.project_type_id } : undefined
  dialog.value = true
}
</script>
<template>
  <div>
    <div class="manager-bar">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索客户或项目类型" class="search" />
      <span class="muted">
        客户有模板时，新建报价按模板带出产品、数量和协议价；没有模板的客户按项目类型的标准配置带出。
      </span>
      <el-button v-if="canWrite()" type="primary" size="small" @click="open()">新增客户报价模板</el-button>
    </div>
    <el-table :data="filtered" empty-text="还没有客户报价模板">
      <el-table-column label="客户" prop="customer_name" min-width="160" show-overflow-tooltip />
      <el-table-column label="项目类型" prop="project_type_name" min-width="130" />
      <el-table-column label="产品" width="80" align="right">
        <template #default="{ row }">{{ row.items.length }} 项</template>
      </el-table-column>
      <el-table-column label="整套协议价" width="118" align="right">
        <template #default="{ row }">{{ money(setPrice(row)) }}</template>
      </el-table-column>
      <el-table-column label="按标准售价" width="118" align="right">
        <template #default="{ row }">
          <span class="muted">{{ standardPrice(row) == null ? '—' : money(standardPrice(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="与标准价相差" width="118" align="right">
        <template #default="{ row }">
          <template v-if="standardPrice(row) != null">
            <span :class="setPrice(row) < standardPrice(row)! ? 'danger-text' : ''">
              {{ setPrice(row) >= standardPrice(row)! ? '+' : '' }}{{ money(setPrice(row) - standardPrice(row)!) }}
            </span>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="note" min-width="110" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新" width="105">
        <template #default="{ row }">{{ day(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
        <template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>
    <CustomerTemplateDialog v-model="dialog" :seed="seed" @saved="load" />
  </div>
</template>
<style scoped>
.search {
  width: 220px;
  flex: none;
}
</style>
