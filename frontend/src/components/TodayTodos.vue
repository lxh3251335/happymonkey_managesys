<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { money } from '../utils'
import { onDataChanged } from '../dataEvents'

/** 首页顶部的今日待办：今天需要有人处理的事，按当前角色排好顺序，点一下直接到对应的列表。 */
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

async function load() {
  items.value = await api<Todo[]>('/todos').catch(() => [])
  loaded.value = true
}
onDataChanged(load)
onMounted(load)
</script>
<template>
  <section v-if="loaded" class="today" aria-label="今日待办">
    <h2>今日待办</h2>
    <p v-if="!items.length" class="none">今天没有需要处理的事。</p>
    <div v-else class="cards">
      <button
        v-for="i in items"
        :key="i.key"
        type="button"
        :class="['card', { urgent: URGENT.has(i.key) }]"
        @click="router.push(i.link)"
      >
        <span class="label">{{ i.label }}</span>
        <span class="count">
          <strong>{{ i.count }}</strong>
          {{ i.unit }}
        </span>
        <span v-if="i.amount != null" class="amount">{{ money(i.amount) }}</span>
      </button>
    </div>
  </section>
</template>
<style scoped>
.today {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
h2 {
  margin: 0;
  flex: none;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}
.none {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 132px;
  padding: 9px 14px;
  border: 1px solid #d3dbd3;
  border-left: 3px solid #8baf9d;
  border-radius: 8px;
  background: #fff;
  color: var(--ink);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.card:hover {
  border-color: #24634f;
  background: #f5f9f6;
}
.card:focus-visible {
  outline: 2px solid #24634f;
  outline-offset: 2px;
}
.card.urgent {
  border-left-color: #b54a2c;
}
.label {
  font-size: 13px;
  color: #33443b;
}
.count {
  font-size: 13px;
  color: var(--muted);
}
.count strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.urgent .count strong {
  color: #a23f24;
}
.amount {
  font-size: 13px;
  color: #33443b;
  font-variant-numeric: tabular-nums;
}
</style>
