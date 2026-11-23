<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Upload } from '@element-plus/icons-vue'
import { api, upload } from '../api'
import ExcelTools from './ExcelTools.vue'

/**
 * 数据导入：按顺序列出所有可导入的表，每张表可以单独导出 / 下载模板 / 导入；
 * 也可以下载一份包含全部表的完整模板，填好后整本导入（按表的顺序逐张检查、写入，任一张表有错整本不写入）。
 */
interface DatasetInfo {
  dataset: string
  title: string
  about: string
  canImport: boolean
}
interface Result {
  rows: string
  action: 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'ERROR'
  label: string
  messages: string[]
}
interface SheetReport {
  dataset: string | null
  title: string
  status: 'OK' | 'ERROR' | 'SKIPPED' | 'EMPTY' | 'IGNORED'
  report: { created: number; updated: number; unchanged: number; errors: number; results: Result[] } | null
  message: string | null
}
interface WorkbookReport {
  sheets: SheetReport[]
  created: number
  updated: number
  unchanged: number
  errors: number
}

const datasets = ref<DatasetInfo[]>([])
const open = ref(false),
  file = ref<File | null>(null),
  input = ref<HTMLInputElement>(),
  report = ref<WorkbookReport>(),
  busy = ref(false),
  expanded = ref<string[]>([])

const statusName = { OK: '可导入', ERROR: '有错误', SKIPPED: '未检查', EMPTY: '无数据', IGNORED: '已忽略' }
const statusType = { OK: 'success', ERROR: 'danger', SKIPPED: 'info', EMPTY: 'info', IGNORED: 'info' } as const
const actionName = { CREATE: '新增', UPDATE: '更新', UNCHANGED: '无变化', ERROR: '错误' }
const canConfirm = computed(
  () => !!report.value && report.value.errors === 0 && report.value.created + report.value.updated > 0,
)

async function load() {
  try {
    datasets.value = await api<DatasetInfo[]>('/excel/workbook/datasets')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
function downloadTemplate() {
  const a = document.createElement('a')
  a.href = '/api/excel/workbook/template'
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}
function start() {
  report.value = undefined
  file.value = null
  expanded.value = []
  if (input.value) input.value.value = ''
  open.value = true
}
async function pick(e: Event) {
  file.value = (e.target as HTMLInputElement).files?.[0] || null
  report.value = undefined
  if (file.value) await run('preview')
}
/** 预览结果里有变化或有错误的行（无变化的行不占地方）。 */
function shown(s: SheetReport) {
  return (s.report?.results ?? []).filter((r) => r.action !== 'UNCHANGED')
}
async function run(action: 'preview' | 'import') {
  if (!file.value) return
  const form = new FormData()
  form.append('file', file.value)
  busy.value = true
  try {
    const r = await upload<WorkbookReport>(`/excel/workbook/${action}`, form)
    if (action === 'preview') {
      report.value = r
      expanded.value = r.sheets.filter((s) => s.status === 'ERROR').map((s) => s.title)
    } else {
      ElMessage.success(`已整本导入：新增 ${r.created}，更新 ${r.updated}`)
      open.value = false
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="data-import">
    <div class="workbook-bar">
      <div class="workbook-text">
        <strong>整本导入</strong>
        <span class="muted">
          下载包含全部表的完整模板，填好后一次导入；按下面的顺序逐张检查并写入，任一张表有错整本不写入。
        </span>
      </div>
      <el-button size="small" @click="downloadTemplate">
        <el-icon><Download /></el-icon>
        下载完整模板
      </el-button>
      <el-button size="small" type="primary" @click="start">
        <el-icon><Upload /></el-icon>
        整本导入
      </el-button>
    </div>
    <el-table :data="datasets" size="small" class="dataset-table">
      <el-table-column label="顺序" type="index" width="56" />
      <el-table-column label="数据表" prop="title" width="150" />
      <el-table-column label="内容" prop="about" min-width="300" show-overflow-tooltip />
      <el-table-column label="单表操作" width="290">
        <template #default="{ row }">
          <ExcelTools :dataset="row.dataset" :title="row.title" :can-import="row.canImport" @imported="load" />
        </template>
      </el-table-column>
    </el-table>
  </div>
  <el-dialog v-model="open" title="整本导入" width="900px" :close-on-click-modal="false" append-to-body>
    <div class="pick-row">
      <label class="file-pick">
        <input ref="input" type="file" accept=".xlsx" @change="pick" />
        <span>{{ file ? file.name : '选择按完整模板填写的 .xlsx 文件…' }}</span>
      </label>
      <el-button size="small" link type="primary" @click="downloadTemplate">下载完整模板</el-button>
    </div>
    <div v-if="busy && !report" class="muted loading">正在逐张读取并校验…</div>
    <template v-if="report">
      <div class="summary">
        <span class="a-create">新增 {{ report.created }}</span>
        <span class="a-update">更新 {{ report.updated }}</span>
        <span class="a-same">无变化 {{ report.unchanged }}</span>
        <span :class="report.errors ? 'a-error' : 'a-same'">错误 {{ report.errors }}</span>
      </div>
      <el-collapse v-model="expanded">
        <el-collapse-item v-for="s in report.sheets" :key="s.title" :name="s.title">
          <template #title>
            <span class="sheet-title">
              <el-tag :type="statusType[s.status]" size="small" effect="plain">{{ statusName[s.status] }}</el-tag>
              <strong>{{ s.title }}</strong>
              <span v-if="s.report" class="muted">
                新增 {{ s.report.created }} · 更新 {{ s.report.updated }} · 无变化 {{ s.report.unchanged }}
                <template v-if="s.report.errors">· 错误 {{ s.report.errors }}</template>
              </span>
              <span v-else-if="s.message" :class="s.status === 'ERROR' ? 'danger-text' : 'muted'">{{ s.message }}</span>
            </span>
          </template>
          <el-table v-if="s.report" :data="shown(s)" size="small" max-height="300" empty-text="全部无变化">
            <el-table-column label="位置" prop="rows" width="110" />
            <el-table-column label="处理" width="80">
              <template #default="{ row }">{{ actionName[row.action as keyof typeof actionName] }}</template>
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
          <div v-else-if="s.message" :class="s.status === 'ERROR' ? 'danger-text' : 'muted'">{{ s.message }}</div>
        </el-collapse-item>
      </el-collapse>
    </template>
    <template #footer>
      <span v-if="report && report.errors" class="danger-text footer-note">请修正错误后重新选择文件</span>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="busy && !!report" :disabled="!canConfirm" @click="run('import')">
        确认整本导入{{ report ? `（${report.created + report.updated} 条）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.workbook-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid #dde5d8;
  border-radius: 6px;
  background: #f6f8f4;
}
.workbook-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}
.dataset-table :deep(.excel-tools .el-button) {
  margin-left: 0;
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
  gap: 14px;
  margin-bottom: 10px;
  font-size: 14px;
}
.sheet-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
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
.footer-note {
  margin-right: 12px;
  font-size: 13px;
}
</style>
