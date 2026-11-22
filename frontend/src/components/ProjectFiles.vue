<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, canWrite, del, isAdmin, session, upload } from '../api'
import { dateTime } from '../utils'

/**
 * 门店的配置清单和设计图：客户给的清单、我们按清单做的设计。挂在门店上，换报价版本、生成合同后都还在。
 * PDF 和图片可以直接打开看，其余下载。
 */
interface FileRow {
  id: string
  category: 'CONFIG_LIST' | 'DESIGN' | 'OTHER'
  file_name: string
  extension: string
  size_bytes: number
  uploaded_by: string
  created_at: string
}
const props = defineProps<{ projectId: string }>()
const CATEGORY: Record<string, string> = { CONFIG_LIST: '客户配置清单', DESIGN: '设计图', OTHER: '其他' }
const files = ref<FileRow[]>([]),
  busy = ref('')

async function load() {
  files.value = props.projectId ? await api<FileRow[]>(`/projects/${props.projectId}/attachments`).catch(() => []) : []
}
watch(() => props.projectId, load, { immediate: true })

const href = (f: FileRow, inline = false) => `/api/project-attachments/${f.id}/file${inline ? '?inline=true' : ''}`
const previewable = (f: FileRow) => ['pdf', 'jpg', 'jpeg', 'png'].includes(f.extension)
const size = (n: number) =>
  n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`

async function add(category: 'CONFIG_LIST' | 'DESIGN', e: Event) {
  const input = e.target as HTMLInputElement
  const list = Array.from(input.files ?? [])
  input.value = ''
  if (!list.length) return
  busy.value = category
  let ok = 0
  for (const f of list) {
    try {
      const data = new FormData()
      data.append('category', category)
      data.append('file', f)
      await upload(`/projects/${props.projectId}/attachments`, data)
      ok++
    } catch (err) {
      ElMessage.error(`${f.name}：${(err as Error).message}`)
    }
  }
  busy.value = ''
  if (ok) {
    ElMessage.success(`已上传 ${ok} 个${CATEGORY[category]}`)
    await load()
  }
}
async function remove(f: FileRow) {
  try {
    await ElMessageBox.confirm(`删除“${f.file_name}”？`, '删除附件', { type: 'warning' })
  } catch {
    return
  }
  try {
    await del(`/project-attachments/${f.id}`)
    await load()
  } catch (err) {
    ElMessage.error((err as Error).message)
  }
}
</script>
<template>
  <section class="project-files">
    <div class="head">
      <strong>配置清单与设计图</strong>
      <template v-if="canWrite()">
        <label :class="['up', { busy: busy === 'DESIGN' }]">
          上传设计图
          <input type="file" multiple accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf" @change="add('DESIGN', $event)" />
        </label>
        <label :class="['up', { busy: busy === 'CONFIG_LIST' }]">
          上传配置清单
          <input type="file" multiple accept=".xlsx,.xls,.pdf,.jpg,.jpeg,.png" @change="add('CONFIG_LIST', $event)" />
        </label>
      </template>
    </div>
    <p v-if="!files.length" class="muted none">还没有配置清单或设计图。</p>
    <ul v-else>
      <li v-for="f in files" :key="f.id">
        <span class="cat">{{ CATEGORY[f.category] }}</span>
        <a
          :href="previewable(f) ? href(f, true) : href(f)"
          :target="previewable(f) ? '_blank' : undefined"
          class="name"
        >
          {{ f.file_name }}
        </a>
        <span class="muted meta">{{ size(f.size_bytes) }} · {{ f.uploaded_by }} · {{ dateTime(f.created_at) }}</span>
        <a v-if="previewable(f)" :href="href(f)" class="act">下载</a>
        <el-button
          v-if="isAdmin() || f.uploaded_by === session.user?.username"
          link
          type="danger"
          class="act"
          @click="remove(f)"
        >
          删除
        </el-button>
      </li>
    </ul>
  </section>
</template>
<style scoped>
.project-files {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid #e1e7de;
  border-radius: 8px;
  background: #fff;
}
.head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 8px;
}
.head strong {
  margin-right: auto;
  font-size: 14px;
}
.up {
  color: #24634f;
  font-size: 13px;
  cursor: pointer;
}
.up.busy {
  opacity: 0.5;
  pointer-events: none;
}
.up input {
  display: none;
}
.none {
  margin: 0;
  font-size: 13px;
}
ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  border-top: 1px solid #f0f3ef;
  font-size: 13px;
}
li:first-child {
  border-top: 0;
}
.cat {
  flex: none;
  padding: 1px 6px;
  border-radius: 4px;
  background: #edf4ef;
  color: #24634f;
  font-size: 12px;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
}
.meta {
  margin-left: auto;
  flex: none;
  font-size: 12px;
}
.act {
  flex: none;
  font-size: 13px;
  color: #24634f;
}
</style>
