<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, canWrite, del, session, upload } from '../api'
import type { Product, ProductDrawing } from '../types'
import { day, fileSize } from '../utils'

const props = defineProps<{ modelValue: boolean; product?: Product }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()

const list = ref<ProductDrawing[]>([]),
  busy = ref(false),
  loading = ref(false),
  note = ref(''),
  file = ref<File | null>(null),
  input = ref<HTMLInputElement>()
const ACCEPT = '.pdf,.png,.jpg,.jpeg,.dwg,.dxf'
const MAX = 30 * 1024 * 1024

async function load() {
  if (!props.product) return
  loading.value = true
  try {
    list.value = await api<ProductDrawing[]>(`/products/${props.product.id}/drawings`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    list.value = []
    note.value = ''
    clearFile()
    load()
  },
)
function clearFile() {
  file.value = null
  if (input.value) input.value.value = ''
}
function pick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] || null
  if (f && f.size > MAX) {
    ElMessage.warning('单个图纸不能超过 30 MB')
    return clearFile()
  }
  file.value = f
}
async function submit() {
  if (!props.product || !file.value) return ElMessage.warning('请选择图纸文件')
  const form = new FormData()
  if (note.value.trim()) form.append('note', note.value.trim())
  form.append('file', file.value)
  busy.value = true
  try {
    await upload(`/products/${props.product.id}/drawings`, form)
    ElMessage.success('图纸已上传')
    note.value = ''
    clearFile()
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
const href = (d: ProductDrawing, inline = false) => `/api/products/drawings/${d.id}/file${inline ? '?inline=true' : ''}`
const previewable = (d: ProductDrawing) => ['pdf', 'png', 'jpg', 'jpeg'].includes(d.extension)
const canDelete = (d: ProductDrawing) => session.user?.role === 'ADMIN' || session.user?.username === d.uploaded_by
async function remove(d: ProductDrawing) {
  try {
    await ElMessageBox.confirm(`删除图纸“${d.file_name}”？列表不再显示，服务器保留文件以备审计。`, '删除图纸', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await del(`/products/drawings/${d.id}`)
    ElMessage.success('图纸已删除')
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    :title="`产品图纸 · ${product?.name || ''}`"
    width="760px"
  >
    <p v-if="product" class="muted sub">
      {{ product.scenario }} · {{ product.specification }}{{ product.model ? ` · ${product.model}` : '' }}
    </p>
    <div v-if="canWrite()" class="upload-row">
      <label class="file-pick">
        <input ref="input" type="file" :accept="ACCEPT" @change="pick" />
        <span>{{ file ? `${file.name}（${fileSize(file.size)}）` : '选择图纸文件…' }}</span>
      </label>
      <el-input v-model="note" maxlength="500" placeholder="版本 / 说明，如：V2 总装图" class="note" />
      <el-button type="primary" :loading="busy" :disabled="!file" @click="submit">上传</el-button>
    </div>
    <div class="inline-note">
      支持 PDF、PNG/JPG、DWG、DXF，单个不超过 30 MB。PDF 和图片可在线预览，CAD 文件下载后查看。
    </div>
    <el-table v-loading="loading" :data="list" empty-text="暂无图纸" size="small">
      <el-table-column label="文件" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="ext">{{ row.extension.toUpperCase() }}</span>
          {{ row.file_name }}
          <small class="cell-secondary muted">
            {{ fileSize(row.size_bytes) }}{{ row.note ? ` · ${row.note}` : '' }}
          </small>
        </template>
      </el-table-column>
      <el-table-column label="上传" width="130">
        <template #default="{ row }">
          {{ day(row.created_at) }}
          <small class="cell-secondary muted">{{ row.uploaded_by }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-link v-if="previewable(row)" :href="href(row, true)" target="_blank" type="primary" class="gap">
            预览
          </el-link>
          <el-link :href="href(row)" type="primary" class="gap">下载</el-link>
          <el-button v-if="canDelete(row)" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
<style scoped>
.sub {
  margin: -8px 0 12px;
  font-size: 12px;
}
.upload-row {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}
.file-pick {
  position: relative;
  flex: 1 1 220px;
  min-width: 0;
  padding: 6px 12px;
  border: 1px dashed #c9d3c2;
  border-radius: 4px;
  color: #5d6d58;
  font-size: 13px;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.file-pick input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.note {
  flex: 1 1 180px;
}
.ext {
  display: inline-block;
  min-width: 34px;
  margin-right: 6px;
  padding: 0 4px;
  border-radius: 3px;
  background: #eef2ea;
  color: #4a5f50;
  font-size: 10px;
  text-align: center;
}
.gap {
  margin-right: 10px;
}
.cell-secondary {
  display: block;
}
</style>
