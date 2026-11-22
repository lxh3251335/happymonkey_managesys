<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  configText,
  displayRows,
  groupOf,
  groupPayload,
  linesOfBundle,
  nextGroupNo,
  rowClass,
  setSets,
  spanGroups,
  syncPerSet,
  type DisplayRow,
  type GroupDraft,
} from '../bundles'
import type { Bundle } from '../types'
import { MagicStick, ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { api, post, put, canWrite } from '../api'
import type { Product, ProjectType, Quote } from '../types'
import { money } from '../utils'
import BusinessSelect from './BusinessSelect.vue'

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
  /** 属于哪个组合；不在组合里时为空。 */
  group?: GroupDraft
}
const form = ref({ name: '', brand: '未指定', note: '', active: true, items: [] as Line[] })

/** 标准组合：可以整组加进标准配置。 */
const bundles = ref<Bundle[]>([])
const byId = computed(() => new Map(props.products.map((p) => [p.id, p])))
function addBundle(id: string) {
  const b = bundles.value.find((x) => x.id === id)
  if (!b) return
  const added: Line[] = linesOfBundle(b, nextGroupNo(form.value.items)).map((l) => ({
    ...l,
    unitPrice: byId.value.get(l.productId)?.reference_price ?? undefined,
  }))
  form.value.items = [...form.value.items.filter((i) => i.productId), ...added]
}
const rows = computed(() => displayRows(form.value.items))
const spanMethod = spanGroups(() => 5)
function removeRow(row: DisplayRow<Line>) {
  if (row.kind === 'group') form.value.items = form.value.items.filter((l) => l.group?.no !== row.no)
  else form.value.items.splice(form.value.items.indexOf(row.line), 1)
}
const groupTotal = (ls: Line[]) => ls.reduce((s, l) => s + l.quantity * (l.unitPrice || 0), 0)
const typeConfig = (t: ProjectType) =>
  configText(t.items.map((i) => ({ ...i, quantity: Number(i.quantity), group: groupOf(i) })))

async function load() {
  try {
    types.value = await api<ProjectType[]>('/project-types')
    bundles.value = (await api<Bundle[]>('/bundles').catch(() => [])).filter((b) => b.active)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)

const setPrice = (t: ProjectType) => t.items.reduce((s, i) => s + Number(i.quantity) * Number(i.unit_price || 0), 0)
const formTotal = computed(() => form.value.items.reduce((s, i) => s + i.quantity * (i.unitPrice || 0), 0))
const label = (p: Product) => `${p.scenario} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}`
/** 同业务线的最近报价可作为配置来源。 */
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
          group: groupOf(i),
        })),
      }
    : {
        name: '',
        brand: '未指定',
        note: '',
        active: true,
        items: [{ productId: '', quantity: 1, unitPrice: undefined }],
      }
  suggestion.value = null
  dialog.value = true
  if (!quotes.value.length) quotes.value = await api<Quote[]>('/quotes').catch(() => [])
}
// ---------- 从历史报价推荐配置 ----------
interface SuggestItem {
  product_id: string
  name: string
  specification: string
  model: string
  quantity: number
  unit_price: number | null
}
interface Suggestion {
  brand: string
  stores: number
  options: { stores: number; items: SuggestItem[] }[]
}
const suggestion = ref<Suggestion | null>(null),
  suggesting = ref(false)
async function suggest() {
  suggesting.value = true
  try {
    suggestion.value = await api<Suggestion>(`/project-types/suggest?brand=${encodeURIComponent(form.value.brand)}`)
    if (!suggestion.value.options.length) ElMessage.info('该业务线还没有历史报价，请手动添加产品')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    suggesting.value = false
  }
}
function applySuggestion(items: SuggestItem[]) {
  form.value.items = items.map((i) => ({
    productId: i.product_id,
    quantity: Number(i.quantity),
    unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
  }))
  suggestion.value = null
  ElMessage.success(`已带入 ${items.length} 项配置和常用售价，可再调整`)
}
/** 从“供应商成本”跳转过来时，直接打开指定类型的编辑。 */
async function openById(id: string) {
  if (!types.value.length) await load()
  const t = types.value.find((x) => x.id === id)
  if (t) await open(t)
}
defineExpose({ openById })

async function copyQuote() {
  if (!copyFrom.value) return
  try {
    const q = await api<Quote>(`/quotes/${copyFrom.value}`)
    form.value.items = q.items.map((i) => ({
      productId: i.product_id,
      quantity: Number(i.quantity),
      unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
      group: groupOf(i),
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
      items: f.items.map((i) => ({
        productId: i.productId,
        quantity: i.quantity,
        unitPrice: i.unitPrice ?? null,
        group: groupPayload(i.group),
      })),
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
      <el-button v-if="canWrite()" type="primary" size="small" @click="open()">新增项目类型</el-button>
    </div>
    <el-table :data="types">
      <el-table-column label="项目类型" min-width="150">
        <template #default="{ row }">
          <strong>{{ row.name }}</strong>
          <el-tag v-if="!row.active" size="small" type="info" class="gap">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="业务线" prop="brand" width="110" />
      <el-table-column label="标准配置" min-width="320">
        <template #default="{ row }">
          <span v-if="!row.items.length" class="muted">未设置配置</span>
          <span v-else class="config-list">{{ typeConfig(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="整套标准售价" width="130" align="right">
        <template #default="{ row }">{{ row.items.length ? money(setPrice(row)) : '—' }}</template>
      </el-table-column>
      <el-table-column label="成本模板数" prop="supplier_templates" width="100" align="right" />
      <el-table-column label="使用门店数" prop="projects" width="100" align="right" />
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
          <el-form-item label="业务线">
            <BusinessSelect v-model="form.brand" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <div class="suggest-box">
          <div class="suggest-head">
            <span>不知道怎么填？按「{{ form.brand }}」的历史报价，找出最多门店使用的配置和常用售价。</span>
            <el-button size="small" type="primary" plain :icon="MagicStick" :loading="suggesting" @click="suggest">
              从历史报价推荐配置
            </el-button>
          </div>
          <div v-if="suggestion && suggestion.options.length" class="suggest-list">
            <div class="muted">共分析 {{ suggestion.stores }} 家门店的报价，最常见的配置：</div>
            <div v-for="(o, i) in suggestion.options" :key="i" class="suggest-option">
              <span class="suggest-rank">{{ o.stores }} 店</span>
              <span class="suggest-items">
                {{
                  o.items
                    .map(
                      (x) =>
                        `${x.name}×${Number(x.quantity)}（${x.unit_price == null ? '无售价' : money(x.unit_price)}）`,
                    )
                    .join('、')
                }}
              </span>
              <el-button size="small" link type="primary" @click="applySuggestion(o.items)">用这个</el-button>
            </div>
          </div>
        </div>
        <el-form-item label="或从某一份报价带入配置（可选）">
          <div class="copy-row">
            <el-select v-model="copyFrom" filterable clearable placeholder="选择一个同业务线门店的报价">
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
        <el-table :data="rows" row-key="key" :span-method="spanMethod" :row-class-name="rowClass" size="small">
          <el-table-column label="产品 / 规格" min-width="320">
            <template #default="{ row }">
              <div v-if="row.kind === 'group'" class="group-head">
                <span class="group-name">组合 · {{ row.name }}</span>
                <span class="group-sets">
                  <el-input-number
                    :model-value="row.sets"
                    :min="1"
                    :precision="0"
                    :controls="false"
                    size="small"
                    @change="(v: number | undefined) => setSets(form.items, row.no, v)"
                  />
                  套
                </span>
                <span class="muted">{{ row.lines.length }} 种标准品 · 小计 {{ money(groupTotal(row.lines)) }}</span>
                <el-button link type="danger" size="small" @click="removeRow(row)">移除组合</el-button>
              </div>
              <el-select v-else v-model="row.line.productId" filterable placeholder="选择标准产品">
                <el-option v-for="p in props.products" :key="p.id" :value="p.id" :label="label(p)" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="100">
            <template #default="{ row }">
              <el-input-number
                v-if="row.kind === 'line'"
                v-model="row.line.quantity"
                :min="1"
                :precision="0"
                :controls="false"
                @change="syncPerSet(row.line)"
              />
            </template>
          </el-table-column>
          <el-table-column label="标准售价（元）" width="150">
            <template #default="{ row }">
              <el-input-number
                v-if="row.kind === 'line'"
                v-model="row.line.unitPrice"
                :min="0"
                :precision="2"
                :controls="false"
                placeholder="待定"
              />
            </template>
          </el-table-column>
          <el-table-column label="小计" width="120" align="right">
            <template #default="{ row }">
              <template v-if="row.kind === 'line'">{{ money(row.line.quantity * (row.line.unitPrice || 0)) }}</template>
            </template>
          </el-table-column>
          <el-table-column width="60" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.kind === 'line'"
                :icon="Delete"
                link
                type="danger"
                aria-label="删除"
                @click="removeRow(row)"
              />
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
          <el-dropdown trigger="click" @command="addBundle">
            <el-button size="small">
              添加组合
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="!bundles.length" disabled>还没有标准组合</el-dropdown-item>
                <el-dropdown-item v-for="b in bundles" :key="b.id" :command="b.id">
                  {{ b.scenario }} / {{ b.name }}（{{ b.items.length }} 种标准品）
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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
.suggest-box {
  border: 1px dashed #c9d3c2;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  font-size: 14px;
}
.suggest-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.suggest-list {
  margin-top: 8px;
}
.suggest-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 6px 0;
  border-top: 1px solid #eef1ec;
}
.suggest-rank {
  flex: 0 0 48px;
  color: #24634f;
  font-weight: 600;
}
.suggest-items {
  flex: 1;
  color: #33463e;
}
.gap {
  margin-left: 6px;
}
.config-list {
  font-size: 13px;
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
  font-size: 14px;
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
