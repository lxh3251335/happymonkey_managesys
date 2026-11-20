<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Upload, ArrowDown } from '@element-plus/icons-vue'
import { upload } from '../api'

/** 基础资料 Excel 导出 / 模板 / 导入（先预览，确认后整批写入）。 */
const props = defineProps<{ dataset: string; title: string; canImport: boolean }>()
const emit = defineEmits<{ imported: [] }>()

interface Result {
  rows: string
  action: 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'ERROR'
  label: string
  messages: string[]
}
interface Report {
  created: number
  updated: number
  unchanged: number
  errors: number
  results: Result[]
}

const open = ref(false),
  file = ref<File | null>(null),
  input = ref<HTMLInputElement>(),
  report = ref<Report>(),
  busy = ref(false),
  view = ref<'changes' | 'all' | 'errors'>('changes')

const actionName = { CREATE: '新增', UPDATE: '更新', UNCHANGED: '无变化', ERROR: '错误' }
const actionClass = { CREATE: 'a-create', UPDATE: 'a-update', UNCHANGED: 'a-same', ERROR: 'a-error' }
const shown = computed(() =>
  (report.value?.results ?? []).filter((r) =>
    view.value === 'all' ? true : view.value === 'errors' ? r.action === 'ERROR' : r.action !== 'UNCHANGED',
  ),
)
const canConfirm = computed(
  () => !!report.value && report.value.errors === 0 && report.value.created + report.value.updated > 0,
)

function download(template: boolean) {
  const a = document.createElement('a')
  a.href = `/api/excel/${props.dataset}${template ? '?template=true' : ''}`
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}
function onCommand(c: string) {
  download(c === 'template')
}
function start() {
  report.value = undefined
  file.value = null
  if (input.value) input.value.value = ''
  view.value = 'changes'
  open.value = true
}
async function pick(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] || null
  report.value = undefined
  if (file.value) await run('preview')
}
async function run(action: 'preview' | 'import') {
  if (!file.value) return
  const form = new FormData()
  form.append('file', file.value)
  busy.value = true
  try {
    const r = await upload<Report>(`/excel/${props.dataset}/${action}`, form)
    if (action === 'preview') {
      report.value = r
      view.value = r.errors ? 'errors' : 'changes'
    } else {
      ElMessage.success(`${props.title}已导入：新增 ${r.created}，更新 ${r.updated}`)
      open.value = false
      emit('imported')
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <span class="excel-tools">
    <el-dropdown trigger="click" @command="onCommand">
      <el-button size="small">
        <el-icon><Download /></el-icon>
        导出 Excel
        <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="data">导出全部{{ title }}</el-dropdown-item>
          <el-dropdown-item command="template">下载空白导入模板</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <el-button v-if="canImport" size="small" @click="start">
      <el-icon><Upload /></el-icon>
      导入 Excel
    </el-button>
  </span>
  <el-dialog v-model="open" :title="`导入${title}`" width="860px" :close-on-click-modal="false" append-to-body>
    <div class="pick-row">
      <label class="file-pick">
        <input ref="input" type="file" accept=".xlsx" @change="pick" />
        <span>{{ file ? file.name : '选择 .xlsx 文件…' }}</span>
      </label>
      <el-button size="small" link type="primary" @click="download(true)">下载空白模板</el-button>
    </div>
    <div v-if="busy && !report" class="muted loading">正在读取并校验…</div>
    <template v-if="report">
      <div class="summary">
        <span class="a-create">新增 {{ report.created }}</span>
        <span class="a-update">更新 {{ report.updated }}</span>
        <span class="a-same">无变化 {{ report.unchanged }}</span>
        <span :class="report.errors ? 'a-error' : 'a-same'">错误 {{ report.errors }}</span>
        <el-radio-group v-model="view" size="small" class="view-switch">
          <el-radio-button value="changes">有变化</el-radio-button>
          <el-radio-button value="errors">只看错误</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="shown" size="small" max-height="380" empty-text="没有需要显示的行">
        <el-table-column label="位置" prop="rows" width="110" />
        <el-table-column label="处理" width="80">
          <template #default="{ row }">
            <span :class="['tag', actionClass[row.action as keyof typeof actionClass]]">
              {{ actionName[row.action as keyof typeof actionName] }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="资料" prop="label" min-width="200" show-overflow-tooltip />
        <el-table-column label="说明" min-width="300">
          <template #default="{ row }">
            <div v-for="(m, i) in row.messages" :key="i" :class="row.action === 'ERROR' ? 'danger-text' : 'muted'">
              {{ m }}
            </div>
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template #footer>
      <span v-if="report && report.errors" class="danger-text footer-note">请修正错误后重新选择文件</span>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="busy && !!report" :disabled="!canConfirm" @click="run('import')">
        确认导入{{ report ? `（${report.created + report.updated} 条）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.excel-tools {
  display: inline-flex;
  gap: 8px;
}
.pick-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.file-pick {
  position: relative;
  flex: 1;
  min-width: 0;
  padding: 6px 12px;
  border: 1px dashed #c9d3c2;
  border-radius: 4px;
  color: #5d6d58;
  font-size: 14px;
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
.loading {
  padding: 12px 0;
  font-size: 14px;
}
.summary {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
  font-size: 14px;
}
.view-switch {
  margin-left: auto;
}
.tag {
  display: inline-block;
  padding: 0 7px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 20px;
}
.a-create {
  color: #24634f;
}
.a-update {
  color: #3b5f8a;
}
.a-same {
  color: #66716a;
}
.a-error {
  color: #b23b2e;
}
.tag.a-create {
  background: #e6f0ea;
}
.tag.a-update {
  background: #e7eef7;
}
.tag.a-same {
  background: #f0f2ef;
}
.tag.a-error {
  background: #f9e4e1;
}
.footer-note {
  margin-right: 12px;
  font-size: 13px;
}
</style>
