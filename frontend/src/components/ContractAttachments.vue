<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, canAttach, del, session, upload } from '../api'
import type { Contract, ContractAttachment } from '../types'
import { attachmentCategory, day, fileSize, money } from '../utils'

const props = defineProps<{ modelValue: boolean; contract?: Contract }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()

const list = ref<ContractAttachment[]>([]),
  busy = ref(false),
  loading = ref(false),
  category = ref('CONTRACT'),
  note = ref(''),
  file = ref<File | null>(null),
  input = ref<HTMLInputElement>()
const ACCEPT = '.pdf,.jpg,.jpeg,.png,.doc,.docx,.xls,.xlsx,.ofd'
const MAX = 20 * 1024 * 1024

async function load() {
  if (!props.contract) return
  loading.value = true
  try {
    const q = new URLSearchParams({ customerId: props.contract.customer_id, contractKey: props.contract.contract_key })
    list.value = await api<ContractAttachment[]>(`/contracts/attachments?${q}`)
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
    category.value = 'CONTRACT'
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
    ElMessage.warning('单个附件不能超过 20 MB')
    return clearFile()
  }
  file.value = f
}
async function submit() {
  if (!props.contract || !file.value) return ElMessage.warning('请选择要上传的文件')
  const form = new FormData()
  form.append('customerId', props.contract.customer_id)
  form.append('contractKey', props.contract.contract_key)
  form.append('category', category.value)
  if (note.value.trim()) form.append('note', note.value.trim())
  form.append('file', file.value)
  busy.value = true
  try {
    await upload('/contracts/attachments', form)
    ElMessage.success('附件已上传')
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
const href = (a: ContractAttachment, inline = false) =>
  `/api/contracts/attachments/${a.id}/file${inline ? '?inline=true' : ''}`
const previewable = (a: ContractAttachment) => ['pdf', 'jpg', 'jpeg', 'png'].includes(a.extension)
const canDelete = (a: ContractAttachment) => session.user?.role === 'ADMIN' || session.user?.username === a.uploaded_by
async function remove(a: ContractAttachment) {
  try {
    await ElMessageBox.confirm(`删除附件“${a.file_name}”？删除后列表不再显示，服务器保留文件以备审计。`, '删除附件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await del(`/contracts/attachments/${a.id}`)
    ElMessage.success('附件已删除')
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
    :title="`合同附件 · ${contract?.contract_no || '未填合同号'}`"
    width="820px"
  >
    <p v-if="contract" class="muted attach-sub">
      {{ contract.customer_name }} · {{ contract.projects }} 个门店 · 合同额 {{ money(contract.amount) }}
    </p>
    <div v-if="canAttach()" class="attach-upload">
      <el-select v-model="category" class="attach-category">
        <el-option v-for="(name, key) in attachmentCategory" :key="key" :label="name" :value="key" />
      </el-select>
      <label class="attach-file">
        <input ref="input" type="file" :accept="ACCEPT" @change="pick" />
        <span>{{ file ? `${file.name}（${fileSize(file.size)}）` : '选择文件…' }}</span>
      </label>
      <el-input v-model="note" maxlength="500" placeholder="备注（可选），如：双方盖章版" class="attach-note" />
      <el-button type="primary" :loading="busy" :disabled="!file" @click="submit">上传</el-button>
    </div>
    <div class="inline-note">
      支持 PDF、JPG/PNG、Word、Excel、OFD，单个不超过 20 MB。同一文件不能重复上传到同一合同。
    </div>
    <el-table v-loading="loading" :data="list" empty-text="暂无附件">
      <el-table-column label="类别" width="110">
        <template #default="{ row }">{{ attachmentCategory[row.category] }}</template>
      </el-table-column>
      <el-table-column label="文件" min-width="230" show-overflow-tooltip>
        <template #default="{ row }">
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
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-link v-if="previewable(row)" :href="href(row, true)" target="_blank" type="primary" class="attach-action">
            预览
          </el-link>
          <el-link :href="href(row)" type="primary" class="attach-action">下载</el-link>
          <el-button v-if="canDelete(row)" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
<style scoped>
.attach-sub {
  margin: -8px 0 14px;
  font-size: 12px;
}
.attach-upload {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.attach-category {
  width: 130px;
}
.attach-note {
  flex: 1 1 180px;
}
.attach-file {
  position: relative;
  flex: 1 1 200px;
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
.attach-file input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.attach-action {
  margin-right: 10px;
}
.cell-secondary {
  display: block;
}
</style>
