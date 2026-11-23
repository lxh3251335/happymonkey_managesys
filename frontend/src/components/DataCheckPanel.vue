<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import { api } from '../api'
import { openFromTopbar } from '../navigation'

/**
 * 数据检查：一键把库里的资料过一遍，按“缺失 / 对不上 / 疑似重复或不规范”列出问题，
 * 每条可以直接打开对应记录去改，也能导出成 Excel 给补资料的同事。只读，不改数据。
 */
interface Finding {
  label: string
  detail: string
  link: string | null
}
interface Check {
  key: string
  group: 'MISSING' | 'MISMATCH' | 'SUSPECT'
  title: string
  level: 'ERROR' | 'WARN' | 'INFO'
  about: string
  count: number
  findings: Finding[]
}
interface Report {
  ranAt: string
  errors: number
  warnings: number
  infos: number
  total: number
  checks: Check[]
}

const groups: { key: Check['group']; title: string; about: string }[] = [
  { key: 'MISSING', title: '缺失', about: '该填的没填，会影响报价、发货或开票' },
  { key: 'MISMATCH', title: '对不上', about: '合同、门店、开票、回款之间的数字或关系不一致' },
  { key: 'SUSPECT', title: '疑似重复或不规范', about: '同一资料可能建了两次，或者日期、金额、电话填错了' },
]
const levelName = { ERROR: '错误', WARN: '提醒', INFO: '建议' }
const levelType = { ERROR: 'danger', WARN: 'warning', INFO: 'info' } as const

const router = useRouter()
const report = ref<Report>(),
  busy = ref(false),
  expanded = ref<string[]>([]),
  showClean = ref(false)

const byGroup = computed(() => {
  const r = report.value
  return groups.map((g) => {
    const checks = (r?.checks ?? []).filter((c) => c.group === g.key)
    return { ...g, checks, total: checks.reduce((s, c) => s + c.count, 0) }
  })
})
function shown(checks: Check[]) {
  return showClean.value ? checks : checks.filter((c) => c.count > 0)
}
async function run() {
  busy.value = true
  try {
    report.value = await api<Report>('/data-check')
    expanded.value = report.value.checks.filter((c) => c.count > 0 && c.level === 'ERROR').map((c) => c.key)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
function exportExcel() {
  const a = document.createElement('a')
  a.href = '/api/data-check/export'
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}
async function open(link: string | null) {
  if (link) await openFromTopbar(router, link)
}
onMounted(run)
</script>
<template>
  <div class="data-check">
    <div class="check-bar">
      <div class="check-text">
        <strong>数据检查</strong>
        <span class="muted">
          把库里的资料过一遍，按问题类型列出来，点“打开”直接去改；也可以导出给补资料的同事。不改任何数据。
        </span>
      </div>
      <el-checkbox v-model="showClean" size="small">显示没有问题的检查项</el-checkbox>
      <el-button size="small" :disabled="!report || !report.total" @click="exportExcel">
        <el-icon><Download /></el-icon>
        导出 Excel
      </el-button>
      <el-button size="small" type="primary" :loading="busy" @click="run">
        <el-icon><Refresh /></el-icon>
        重新检查
      </el-button>
    </div>
    <div v-if="busy && !report" class="muted loading">正在检查…</div>
    <template v-if="report">
      <div class="summary">
        <span class="muted">检查时间 {{ report.ranAt }}</span>
        <span :class="report.errors ? 'a-error' : 'a-same'">错误 {{ report.errors }}</span>
        <span :class="report.warnings ? 'a-warn' : 'a-same'">提醒 {{ report.warnings }}</span>
        <span class="a-same">建议 {{ report.infos }}</span>
        <span v-if="!report.total" class="a-create">全部检查项都没有发现问题</span>
      </div>
      <section v-for="g in byGroup" :key="g.key" class="group">
        <h3>
          {{ g.title }}
          <el-tag :type="g.total ? 'warning' : 'success'" size="small" effect="plain">{{ g.total }}</el-tag>
          <span class="muted group-about">{{ g.about }}</span>
        </h3>
        <div v-if="!shown(g.checks).length" class="muted empty">这一类没有发现问题</div>
        <el-collapse v-else v-model="expanded">
          <el-collapse-item v-for="c in shown(g.checks)" :key="c.key" :name="c.key" :disabled="!c.count">
            <template #title>
              <span class="check-title">
                <el-tag :type="c.count ? levelType[c.level] : 'success'" size="small" effect="plain">
                  {{ levelName[c.level] }}
                </el-tag>
                <strong>{{ c.title }}</strong>
                <span :class="c.count ? 'count' : 'muted'">{{ c.count ? `${c.count} 条` : '没有问题' }}</span>
              </span>
            </template>
            <p class="muted about">{{ c.about }}</p>
            <el-table :data="c.findings" size="small" max-height="360">
              <el-table-column label="资料" prop="label" min-width="260" show-overflow-tooltip />
              <el-table-column label="问题" prop="detail" min-width="300" show-overflow-tooltip />
              <el-table-column label="" width="70">
                <template #default="{ row }">
                  <el-button v-if="row.link" link type="primary" size="small" @click="open(row.link)">打开</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="c.count > c.findings.length" class="muted more">
              只列出前 {{ c.findings.length }} 条，共 {{ c.count }} 条；导出 Excel 也只含这些。
            </div>
          </el-collapse-item>
        </el-collapse>
      </section>
    </template>
  </div>
</template>
<style scoped>
.check-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid #dde5d8;
  border-radius: 6px;
  background: #f6f8f4;
}
.check-text {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}
.loading {
  padding: 12px 0;
  font-size: 14px;
}
.summary {
  display: flex;
  gap: 14px;
  margin-bottom: 6px;
  font-size: 14px;
}
.group {
  margin-top: 16px;
}
.group h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 6px;
  font-size: 15px;
}
.group-about {
  font-weight: normal;
  font-size: 13px;
}
.empty {
  padding: 6px 0 10px;
  font-size: 13px;
}
.check-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.count {
  color: #b23b2e;
}
.about {
  margin: 0 0 8px;
  font-size: 13px;
}
.more {
  margin-top: 6px;
  font-size: 12px;
}
.a-create {
  color: #24634f;
}
.a-warn {
  color: #9a6b12;
}
.a-same {
  color: #66716a;
}
.a-error {
  color: #b23b2e;
}
</style>
