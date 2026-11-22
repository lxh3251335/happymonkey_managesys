<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { api } from '../api'
import { openFromTopbar } from '../navigation'

/** 顶栏搜索：门店名、项目编号、运单号、合同号、报价编号、客户名，回车或点选直接打开。按 / 或 Ctrl+K 聚焦。 */
interface Hit {
  group: string
  title: string
  sub: string
  link: string
}
const router = useRouter()
const query = ref(''),
  input = ref<{ focus: () => void; blur: () => void }>()

let timer: number | undefined
function suggestions(q: string, done: (hits: Hit[]) => void) {
  window.clearTimeout(timer)
  if (!q.trim()) return done([])
  timer = window.setTimeout(
    async () => done(await api<Hit[]>(`/search?q=${encodeURIComponent(q.trim())}`).catch(() => [])),
    200,
  )
}
async function pick(hit: Record<string, unknown>) {
  query.value = ''
  input.value?.blur()
  await openFromTopbar(router, String(hit.link))
}
function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null
  const typing = !!target && (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.isContentEditable)
  if ((e.key === '/' && !typing) || (e.key.toLowerCase() === 'k' && (e.ctrlKey || e.metaKey))) {
    e.preventDefault()
    input.value?.focus()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>
<template>
  <el-autocomplete
    ref="input"
    v-model="query"
    class="global-search"
    popper-class="global-search-popper"
    :fetch-suggestions="suggestions"
    :trigger-on-focus="false"
    :prefix-icon="Search"
    value-key="title"
    placeholder="搜门店、合同、运单号、客户"
    title="按 / 或 Ctrl+K 直接搜索"
    highlight-first-item
    clearable
    aria-label="全局搜索"
    @select="pick"
  >
    <template #default="{ item }">
      <div class="hit">
        <span class="group">{{ item.group }}</span>
        <span class="text">
          <strong>{{ item.title }}</strong>
          <small v-if="item.sub">{{ item.sub }}</small>
        </span>
      </div>
    </template>
  </el-autocomplete>
</template>
<style scoped>
.hit {
  display: flex;
  align-items: center;
  gap: 10px;
  line-height: 1.35;
  padding: 4px 0;
}
.group {
  flex: none;
  min-width: 34px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #edf4ef;
  color: #24634f;
  font-size: 12px;
  text-align: center;
}
.text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.text strong {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.text small {
  color: var(--muted);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
<style>
/* 输入框的根元素拿不到组件的作用域标记，所以宽度写在这里 */
.el-autocomplete.global-search {
  flex: none;
  width: 280px;
}
.global-search-popper {
  min-width: 380px;
}
.global-search-popper li {
  height: auto;
  line-height: normal;
}
</style>
