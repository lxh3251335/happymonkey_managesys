<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../utils'
import { onDataChanged } from '../dataEvents'
import { openFromTopbar } from '../navigation'

/** 顶栏的今日待办：今天需要有人处理的事，按当前角色排好顺序；点开是清单，点一项直接到对应的列表。 */
interface Todo {
  key: string
  label: string
  count: number
  unit: string
  amount: number | null
  link: string
}
const router = useRouter()
const items = ref<Todo[]>([]),
  loaded = ref(false)
// 需要尽快处理的用醒目的颜色
const URGENT = new Set(['CHANGES', 'OVERDUE', 'LATE_SHIP'])
const total = computed(() => items.value.length)
function go(link: string) {
  document.body.click() // 关掉弹出的清单
  openFromTopbar(router, link)
}

async function load() {
  items.value = await api<Todo[]>('/todos').catch(() => [])
  loaded.value = true
}
onDataChanged(load)
onMounted(load)
</script>
<template>
  <el-popover
    v-if="loaded"
    trigger="click"
    placement="bottom-end"
    :width="320"
    popper-class="today-popper"
    @show="load"
  >
    <template #reference>
      <button type="button" :class="['today-btn', { has: items.length }]" aria-label="今日待办">
        <el-icon><Bell /></el-icon>
        待办
        <em v-if="items.length">{{ total }}</em>
      </button>
    </template>
    <p v-if="!items.length" class="none">今天没有需要处理的事。</p>
    <ul v-else class="list">
      <li v-for="i in items" :key="i.key">
        <button type="button" :class="{ urgent: URGENT.has(i.key) }" @click="go(i.link)">
          <span class="label">{{ i.label }}</span>
          <span class="count">
            {{ i.count }} {{ i.unit }}
            <template v-if="i.amount != null">· {{ money(i.amount) }}</template>
          </span>
        </button>
      </li>
    </ul>
  </el-popover>
</template>
<style scoped>
.today-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border: 1px solid #d3dbd3;
  border-radius: 6px;
  background: #fff;
  color: #33443b;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.today-btn:hover {
  border-color: #24634f;
  color: #24634f;
}
.today-btn em {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: #b54a2c;
  color: #fff;
  font-style: normal;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}
.none {
  margin: 4px 0;
  color: var(--muted);
  font-size: 13px;
}
.list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.list button {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
  width: 100%;
  padding: 8px 6px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.list button:hover {
  background: #f3f6f2;
}
.list .urgent .label::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 50%;
  background: #b54a2c;
  vertical-align: middle;
}
.count {
  flex: none;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
</style>
