<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { api, canWrite, put } from '../api'
import type { Product, ProductComponent } from '../types'

/**
 * 标准品的电气件清单（像菜品的原材料）：名称、型号、品牌、数量。只作技术资料，和图纸一起看，不参与报价和成本。
 * 可以从 Excel 里复制几列直接粘贴进来。
 */
const props = defineProps<{ modelValue: boolean; product?: Product }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()

const rows = ref<ProductComponent[]>([]),
  loading = ref(false),
  busy = ref(false),
  pasteOpen = ref(false),
  pasted = ref('')
const editable = computed(() => canWrite())
const blank = (): ProductComponent => ({ name: '', model: '', brand: '', quantity: 1, unit: '个', note: '' })

watch(
  () => props.modelValue,
  async (open) => {
    if (!open || !props.product) return
    loading.value = true
    pasteOpen.value = false
    pasted.value = ''
    try {
      rows.value = (await api<ProductComponent[]>(`/products/${props.product.id}/components`)).map((r) => ({
        ...r,
        quantity: Number(r.quantity),
      }))
    } catch (e) {
      rows.value = []
      ElMessage.error((e as Error).message)
    } finally {
      loading.value = false
    }
  },
)

/** 粘贴：每行一项，列之间用 Tab（从 Excel 复制）或两个以上空格隔开，顺序为 名称、型号、品牌、数量、单位、备注。 */
function applyPaste() {
  const added: ProductComponent[] = []
  for (const line of pasted.value.split(/\r?\n/)) {
    if (!line.trim()) continue
    const cols = line.includes('\t') ? line.split('\t') : line.trim().split(/\s{2,}/)
    const [name, model, brand, qty, unit, note] = cols.map((c) => c.trim())
    if (!name || /^(名称|元件|电气件|器件)/.test(name)) continue
    const n = Number(String(qty || '').replace(/[^\d.]/g, ''))
    added.push({
      name,
      model: model || '',
      brand: brand || '',
      quantity: n > 0 ? n : 1,
      unit: unit || '个',
      note: note || '',
    })
  }
  if (!added.length) return ElMessage.warning('没有读到内容。每行一项：名称、型号、品牌、数量、单位、备注')
  rows.value = [...rows.value.filter((r) => r.name.trim()), ...added]
  pasteOpen.value = false
  pasted.value = ''
  ElMessage.success(`已加入 ${added.length} 项，请核对后保存`)
}

async function save() {
  if (!props.product) return
  const items = rows.value.filter((r) => r.name.trim())
  if (items.some((r) => !(Number(r.quantity) > 0))) return ElMessage.warning('数量要大于 0')
  busy.value = true
  try {
    await put(`/products/${props.product.id}/components`, { items })
    ElMessage.success('电气件清单已保存')
    emit('changed')
    emit('update:modelValue', false)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="`电气件清单 · ${product?.name || ''} ${product?.specification || ''}${product?.model ? ' / ' + product.model : ''}`"
    width="min(920px, 96vw)"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="editable" class="tools">
      <el-button size="small" :icon="Plus" @click="rows.push(blank())">添加一项</el-button>
      <el-button size="small" @click="pasteOpen = !pasteOpen">从 Excel 粘贴</el-button>
    </div>
    <div v-if="pasteOpen" class="paste">
      <el-input
        v-model="pasted"
        type="textarea"
        :rows="5"
        placeholder="从 Excel 复制几列粘贴到这里，顺序：名称、型号、品牌、数量、单位、备注（后面几列可以没有）"
      />
      <el-button size="small" type="primary" @click="applyPaste">加入清单</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rows"
      size="small"
      class="editor-table"
      max-height="440"
      empty-text="还没有电气件"
    >
      <el-table-column label="名称" min-width="160">
        <template #default="{ row }">
          <el-input v-if="editable" v-model="row.name" placeholder="如 断路器" />
          <span v-else>{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="型号" min-width="150">
        <template #default="{ row }">
          <el-input v-if="editable" v-model="row.model" />
          <span v-else>{{ row.model }}</span>
        </template>
      </el-table-column>
      <el-table-column label="品牌" width="110">
        <template #default="{ row }">
          <el-input v-if="editable" v-model="row.brand" />
          <span v-else>{{ row.brand }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="90">
        <template #default="{ row }">
          <el-input-number v-if="editable" v-model="row.quantity" :min="0" :precision="0" :controls="false" />
          <span v-else>{{ row.quantity }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单位" width="72">
        <template #default="{ row }">
          <el-input v-if="editable" v-model="row.unit" />
          <span v-else>{{ row.unit }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="120">
        <template #default="{ row }">
          <el-input v-if="editable" v-model="row.note" />
          <span v-else>{{ row.note }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="editable" label="" width="44">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="rows.splice($index, 1)" />
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ editable ? '取消' : '关闭' }}</el-button>
      <el-button v-if="editable" type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.tools {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.paste {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}
</style>
