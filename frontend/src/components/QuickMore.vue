<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'

/** 快捷标签太多时，不常用的收进“更多”下拉；选中其中一项后，当前项显示在按钮上。 */
defineProps<{
  items: { key: string; label: string; count: number }[]
  current: string
}>()
const emit = defineEmits<{ pick: [string] }>()
</script>
<template>
  <el-dropdown v-if="items.length" trigger="click" @command="(k: string) => emit('pick', k)">
    <button type="button" :class="['quick', 'more', { on: items.some((i) => i.key === current) }]">
      {{ items.find((i) => i.key === current)?.label || '更多' }}
      <em v-if="items.some((i) => i.key === current)">{{ items.find((i) => i.key === current)?.count }}</em>
      <el-icon><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="i in items" :key="i.key" :command="i.key">
          <span class="more-item">
            {{ i.label }}
            <em>{{ i.count }}</em>
          </span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
<style scoped>
.quick.more:not(.on) {
  border-color: transparent;
  background: transparent;
  color: var(--muted);
}
.quick.more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.more-item {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  min-width: 120px;
}
.more-item em {
  font-style: normal;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
</style>
