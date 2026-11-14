<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { api, post, put, canWrite } from '../api'
import type { Product, ProjectType, Quote } from '../types'
import { money } from '../utils'

const props = defineProps<{ products: Product[] }>()
const types = ref<ProjectType[]>([]),
  quotes = ref<Quote[]>([]),
  dialog = ref(false),
  busy = ref(false),
  editingId = ref(''),
  copyFrom = ref('')
interface Line {
  productId: string
  quantity: number
  unitPrice: number | undefined
}
const form = ref({ name: '', brand: '小象', note: '', active: true, items: [] as Line[] })

async function load() {
  try {
    types.value = await api<ProjectType[]>('/project-types')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)

const setPrice = (t: ProjectType) => t.items.reduce((s, i) => s + Number(i.quantity) * Number(i.unit_price || 0), 0)
const formTotal = computed(() => form.value.items.reduce((s, i) => s + i.quantity * (i.unitPrice || 0), 0))
const label = (p: Product) => `${p.scenario} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}`
/** 同品牌的最近报价可作为配置来源。 */
const sourceQuotes = computed(() => quotes.value.filter((q) => q.brand === form.value.brand).slice(0, 300))

async function open(t?: ProjectType) {
  editingId.value = t?.id || ''
  copyFrom.value = ''
  form.value = t
    ? {
        name: t.name,
        brand: t.brand,
        note: t.note || '',
        active: t.active,
        items: t.items.map((i) => ({
          productId: i.product_id,
          quantity: Number(i.quantity),
          unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
        })),
      }
    : { name: '', brand: '小象', note: '', active: true, items: [{ productId: '', quantity: 1, unitPrice: undefined }] }
  dialog.value = true
  if (!quotes.value.length) quotes.value = await api<Quote[]>('/quotes').catch(() => [])
}
async function copyQuote() {
  if (!copyFrom.value) return
  try {
    const q = await api<Quote>(`/quotes/${copyFrom.value}`)
    form.value.items = q.items.map((i) => ({
      productId: i.product_id,
      quantity: Number(i.quantity),
      unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
    }))
    ElMessage.success(`已带入 ${q.project_name} 的 ${q.items.length} 项配置和售价`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function save() {
  const f = form.value
  if (!f.name.trim()) return ElMessage.warning('请填写类型名称')
  if (f.items.some((i) => !i.productId || !i.quantity)) return ElMessage.warning('请补全配置中的产品和数量')
  busy.value = true
  try {
    const body = {
      ...f,
      items: f.items.map((i) => ({ productId: i.productId, quantity: i.quantity, unitPrice: i.unitPrice ?? null })),
    }
    if (editingId.value) await put(`/project-types/${editingId.value}`, body)
    else await post('/project-types', body)
    dialog.value = false
    await load()
    ElMessage.success('项目类型已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div>
    <div class="manager-bar">
      <span class="muted">每种门店的标准配置和对客标准售价。新建报价选择项目类型后自动带出，报价中可逐行修改。</span>
      <el-button v-if="canWrite()" type="primary" size="small" @click="open()">新增项目类型</el-button>
    </div>
    <el-table :data="types">
      <el-table-column label="项目类型" min-width="150">
        <template #default="{ row }">
          <strong>{{ row.name }}</strong>
          <el-tag v-if="!row.active" size="small" type="info" class="gap">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="品牌" prop="brand" width="80" />
      <el-table-column label="标准配置" min-width="320">
        <template #default="{ row }">
          <span v-if="!row.items.length" class="muted">未设置配置</span>
          <span v-else class="config-list">
            {{ row.items.map((i: { name: string; quantity: number }) => `${i.name}×${Number(i.quantity)}`).join('、') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="整套标准售价" width="130" align="right">
        <template #default="{ row }">{{ row.items.length ? money(setPrice(row)) : '—' }}</template>
      </el-table-column>
      <el-table-column label="供应商模板" prop="supplier_templates" width="100" align="right" />
      <el-table-column label="门店" prop="projects" width="70" align="right" />
      <el-table-column v-if="canWrite()" label="操作" width="80" fixed="right">
        <template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialog"
      :title="editingId ? '编辑项目类型' : '新增项目类型'"
      width="900px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <div class="type-grid">
          <el-form-item label="类型名称" required>
            <el-input v-model="form.name" maxlength="100" placeholder="如：小象前置仓" />
          </el-form-item>
          <el-form-item label="品牌">
            <el-select v-model="form.brand">
              <el-option v-for="b in ['小象', '快乐猴', '其他']" :key="b" :value="b" :label="b" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-form-item label="从已有报价带入配置（可选）">
          <div class="copy-row">
            <el-select v-model="copyFrom" filterable clearable placeholder="选择一个同品牌门店的报价">
              <el-option
                v-for="q in sourceQuotes"
                :key="q.id"
                :value="q.id"
                :label="`${q.project_name} · V${q.version} · ${money(q.amount)}`"
              />
            </el-select>
            <el-button :disabled="!copyFrom" @click="copyQuote">带入</el-button>
          </div>
        </el-form-item>
        <el-table :data="form.items" size="small">
          <el-table-column label="产品 / 规格" min-width="320">
            <template #default="{ row }">
              <el-select v-model="row.productId" filterable placeholder="选择标准产品">
                <el-option v-for="p in props.products" :key="p.id" :value="p.id" :label="label(p)" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="100">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="1" :precision="0" :controls="false" />
            </template>
          </el-table-column>
          <el-table-column label="标准售价（元）" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.unitPrice" :min="0" :precision="2" :controls="false" placeholder="待定" />
            </template>
          </el-table-column>
          <el-table-column label="小计" width="120" align="right">
            <template #default="{ row }">{{ money(row.quantity * (row.unitPrice || 0)) }}</template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-button :icon="Delete" link type="danger" aria-label="删除" @click="form.items.splice($index, 1)" />
            </template>
          </el-table-column>
        </el-table>
        <div class="type-foot">
          <el-button
            :icon="Plus"
            size="small"
            @click="form.items.push({ productId: '', quantity: 1, unitPrice: undefined })"
          >
            添加产品
          </el-button>
          <span>
            整套标准售价
            <strong>{{ money(formTotal) }}</strong>
          </span>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.manager-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 24px;
  font-size: 12px;
}
.gap {
  margin-left: 6px;
}
.config-list {
  font-size: 12px;
  color: #4a5a52;
}
.type-grid {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 0 16px;
}
.copy-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.copy-row .el-select {
  flex: 1;
}
.type-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 10px 0 14px;
  font-size: 13px;
}
.type-foot strong {
  font-size: 16px;
  margin-left: 8px;
  font-variant-numeric: tabular-nums;
}
:deep(.el-input-number) {
  width: 100%;
}
</style>
