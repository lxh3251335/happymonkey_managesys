<script setup lang="ts">
import { Filter, Search } from '@element-plus/icons-vue'

/**
 * 台账类页面统一的一行工具栏：左边是快捷标签（默认插槽），右边是搜索框和“筛选”按钮，
 * 条件收在按钮弹出的面板里（filters 插槽：label 和控件成对放），按钮上显示已设的条件数。
 * 面板里的下拉框要加 :teleported="false"，否则点选项会被当成点在面板外而关掉面板。
 * actions 插槽放右边的其他按钮（如批量操作），count 插槽放数量说明。
 */
defineProps<{
  /** 搜索关键词 */
  modelValue: string
  placeholder?: string
  /** “筛选”面板里已设的条件数 */
  activeFilters: number
  /** 任一筛选（含标签、关键词）生效时显示“清除” */
  anyFilter: boolean
  panelWidth?: number
}>()
const emit = defineEmits<{ 'update:modelValue': [string]; reset: [] }>()
</script>
<template>
  <div class="table-toolbar">
    <div class="quick-row">
      <slot />
    </div>
    <div class="toolbar-right">
      <slot name="count" />
      <slot name="actions" />
      <el-input
        :model-value="modelValue"
        clearable
        :prefix-icon="Search"
        :placeholder="placeholder || '搜索'"
        class="toolbar-search"
        @update:model-value="emit('update:modelValue', $event)"
      />
      <el-popover
        v-if="$slots.filters"
        trigger="click"
        placement="bottom-end"
        :width="panelWidth || 300"
        popper-class="filter-popper"
      >
        <template #reference>
          <el-button :class="['filter-btn', { active: activeFilters }]">
            <el-icon><Filter /></el-icon>
            筛选
            <em v-if="activeFilters">{{ activeFilters }}</em>
          </el-button>
        </template>
        <div class="filter-panel">
          <slot name="filters" />
        </div>
      </el-popover>
      <el-button v-if="anyFilter" link type="primary" @click="emit('reset')">清除</el-button>
    </div>
  </div>
</template>
<style scoped>
.table-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  padding: 10px 24px 8px;
}
.table-toolbar .quick-row {
  padding: 0;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.toolbar-search {
  width: 210px;
}
.filter-btn em {
  margin-left: 4px;
  min-width: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: #24634f;
  color: #fff;
  font-style: normal;
  font-size: 12px;
  line-height: 18px;
}
.filter-btn.active {
  border-color: #24634f;
  color: #24634f;
}
.filter-panel {
  display: grid;
  grid-template-columns: 64px 1fr;
  align-items: center;
  gap: 8px 10px;
}
.filter-panel :deep(label) {
  color: var(--muted);
  font-size: 13px;
}
.filter-panel :deep(.el-select),
.filter-panel :deep(.el-checkbox) {
  width: 100%;
}
.filter-panel :deep(.span-2) {
  grid-column: 1 / -1;
}
</style>
