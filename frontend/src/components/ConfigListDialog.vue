<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { post, upload } from '../api'
import type { Product } from '../types'
import { activeScenarios, loadDictionaries } from '../dictionaries'

/**
 * 按客户的配置清单带入报价明细：选客户发来的 Excel，系统读出每一行并对到产品库。
 * 对上的直接带入；有几个相近的、或对不上的，由人在这里选；产品库里没有的可以当场加进去。
 * 带入后清单文件会在保存报价时存到门店附件里。
 */
interface Row {
  row: number
  name: string
  specification: string
  model: string
  quantity: number | null
  unit: string
  note: string
  productId: string | null
  match: 'EXACT' | 'SIMILAR' | 'TEMPLATE' | 'MANUAL' | 'NONE'
  candidates: { id: string; label: string }[]
}
const props = defineProps<{
  modelValue: boolean
  products: Product[]
  /** 当前项目类型（或客户报价模板）里用到的产品：同名同规格有几个时，优先选这里面的。 */
  preferred?: string[]
}>()
const emit = defineEmits<{
  'update:modelValue': [boolean]
  apply: [{ lines: { productId: string; quantity: number }[]; file: File }]
  'product-added': []
}>()

const file = ref<File>(),
  rows = ref<Row[]>([]),
  sheet = ref(''),
  reading = ref(false),
  dragging = ref(false),
  scenario = ref(''),
  adding = ref<number | null>(null)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    file.value = undefined
    rows.value = []
    sheet.value = ''
    loadDictionaries()
      .then(() => (scenario.value ||= activeScenarios.value[0] || ''))
      .catch(() => {})
  },
)

async function read(f?: File) {
  if (!f) return
  if (!/\.xlsx?$/i.test(f.name)) return ElMessage.warning('目前只能读 Excel 格式的配置清单（.xlsx 或 .xls）')
  file.value = f
  reading.value = true
  try {
    const form = new FormData()
    form.append('file', f)
    const r = await upload<{ sheet: string; rows: Row[] }>('/quotes/config-list', form)
    sheet.value = r.sheet
    const preferred = new Set(props.preferred || [])
    rows.value = r.rows.map((x) => {
      const row: Row = { ...x, quantity: x.quantity == null ? null : Number(x.quantity) }
      // 同名同规格的产品有几个（型号或场景不同）：其中只有一个在当前项目类型的配置里，就选它
      if (!row.productId && preferred.size) {
        const inTemplate = row.candidates.filter((c) => preferred.has(c.id))
        if (inTemplate.length === 1) {
          row.productId = inTemplate[0].id
          row.match = 'TEMPLATE'
        }
      }
      return row
    })
  } catch (e) {
    rows.value = []
    ElMessage.error((e as Error).message)
  } finally {
    reading.value = false
  }
}
function onDrop(e: DragEvent) {
  dragging.value = false
  read(e.dataTransfer?.files?.[0])
}
function onPick(e: Event) {
  read((e.target as HTMLInputElement).files?.[0])
  ;(e.target as HTMLInputElement).value = ''
}

const label = (p: Product) => `${p.scenario} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}`
/** 有候选时先列候选，下面接全部产品，方便搜。 */
function options(r: Row) {
  const ids = new Set(r.candidates.map((c) => c.id))
  return [...r.candidates, ...props.products.filter((p) => !ids.has(p.id)).map((p) => ({ id: p.id, label: label(p) }))]
}
function state(r: Row) {
  if (!r.productId)
    return {
      text:
        r.candidates.length > 1
          ? `有 ${r.candidates.length} 个相近的产品，请选择`
          : r.candidates.length
            ? '有相近的产品，请选择'
            : '产品库里没有',
      cls: 'danger-text',
    }
  if (!r.quantity) return { text: '请填数量', cls: 'danger-text' }
  if (r.match === 'EXACT') return { text: '已对上', cls: 'ok' }
  if (r.match === 'MANUAL') return { text: '已选定', cls: 'ok' }
  if (r.match === 'TEMPLATE') return { text: '按项目类型的配置选的，请核对', cls: 'warn' }
  return { text: '名称相近，请核对', cls: 'warn' }
}
const ready = computed(() => rows.value.filter((r) => r.productId && r.quantity))
const pending = computed(() => rows.value.length - ready.value.length)

/** 把清单里这一行加进产品库，并选中它。 */
async function addProduct(r: Row) {
  if (!scenario.value) return ElMessage.warning('请先选择新产品的应用场景')
  adding.value = r.row
  try {
    const { id } = await post<{ id: string }>('/products', {
      scenario: scenario.value,
      name: r.name.trim(),
      specification: r.specification.trim(),
      model: r.model.trim(),
      unit: r.unit.trim() || '台',
      mainstream: false,
    })
    r.productId = id
    r.match = 'EXACT'
    emit('product-added')
    ElMessage.success(`已加入产品库：${r.name}`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    adding.value = null
  }
}

function apply() {
  if (!file.value || !ready.value.length) return
  emit('apply', {
    lines: ready.value.map((r) => ({ productId: r.productId!, quantity: Number(r.quantity) })),
    file: file.value,
  })
  emit('update:modelValue', false)
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="按客户配置清单带入"
    width="min(1080px, 96vw)"
    top="4vh"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div
      :class="['drop', { dragging }]"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <template v-if="file">
        <strong>{{ file.name }}</strong>
        <span v-if="sheet" class="muted">工作表“{{ sheet }}”，{{ rows.length }} 行</span>
      </template>
      <span v-else class="muted">把客户发来的配置清单（Excel）拖到这里，或</span>
      <label class="pick">
        {{ file ? '换一个文件' : '选择文件' }}
        <input type="file" accept=".xlsx,.xls" @change="onPick" />
      </label>
    </div>
    <div v-if="rows.some((r) => !r.productId && !r.candidates.length)" class="scenario-row">
      <span>新产品的应用场景</span>
      <el-select v-model="scenario" filterable size="small" class="scenario">
        <el-option v-for="s in activeScenarios" :key="s" :label="s" :value="s" />
      </el-select>
    </div>
    <el-table v-if="rows.length" v-loading="reading" :data="rows" size="small" class="editor-table" max-height="440">
      <el-table-column label="清单里写的" min-width="230">
        <template #default="{ row }">
          <div class="src">
            <strong>{{ row.name }}</strong>
            <span class="muted">
              {{ [row.specification, row.model].filter(Boolean).join(' / ') || '没写规格' }}
              <template v-if="row.note">· {{ row.note }}</template>
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="对应的产品" min-width="300">
        <template #default="{ row }">
          <div class="pick-product">
            <el-select
              v-model="row.productId"
              filterable
              clearable
              placeholder="选择产品"
              @change="row.match = row.productId ? 'MANUAL' : 'NONE'"
            >
              <el-option v-for="o in options(row)" :key="o.id" :value="o.id" :label="o.label" />
            </el-select>
            <el-button
              v-if="!row.productId && !row.candidates.length"
              size="small"
              :loading="adding === row.row"
              @click="addProduct(row)"
            >
              加入产品库
            </el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="96">
        <template #default="{ row }">
          <el-input-number v-model="row.quantity" :min="0" :precision="0" :controls="false" />
        </template>
      </el-table-column>
      <el-table-column label="单位" prop="unit" width="64" />
      <el-table-column label="" width="150">
        <template #default="{ row }">
          <span :class="state(row).cls">{{ state(row).text }}</span>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <span v-if="rows.length" class="muted count">
        {{ ready.length }} 行可以带入{{ pending ? `，${pending} 行还没选产品或没填数量（不处理则不带入）` : '' }}
      </span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!ready.length" @click="apply">
        带入 {{ ready.length || '' }} 项（替换当前明细）
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.drop {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px dashed #b9c6bc;
  border-radius: 8px;
  background: #f7f9f6;
  font-size: 14px;
}
.drop.dragging {
  border-color: #24634f;
  background: #edf4ef;
}
.pick {
  color: #24634f;
  cursor: pointer;
}
.pick input {
  display: none;
}
.scenario-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 0;
  font-size: 13px;
}
.scenario {
  width: 180px;
}
.editor-table {
  margin-top: 12px;
}
.src {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
.src .muted {
  font-size: 12px;
}
.pick-product {
  display: flex;
  gap: 8px;
}
.pick-product .el-select {
  flex: 1;
}
.count {
  float: left;
  line-height: 32px;
  font-size: 13px;
}
.ok {
  color: #24634f;
}
.warn {
  color: #96670f;
}
</style>
