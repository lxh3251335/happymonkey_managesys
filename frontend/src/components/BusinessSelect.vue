<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { businesses, loadBusinesses } from '../business'

/** 业务线选择：按客户集团分组；停用的业务线只在当前值等于它时显示。 */
const props = withDefaults(
  defineProps<{
    modelValue: string
    unassigned?: boolean
    clearable?: boolean
    placeholder?: string
    size?: 'small' | 'default'
  }>(),
  { unassigned: true, clearable: false, placeholder: '选择业务线', size: 'default' },
)
const emit = defineEmits<{ 'update:modelValue': [string]; change: [string] }>()
onMounted(() => loadBusinesses())
const groups = computed(() =>
  businesses.groups
    .map((g) => ({
      ...g,
      lines: g.lines.filter((l) => (l.active && g.active) || l.name === props.modelValue),
    }))
    .filter((g) => g.lines.length),
)
function update(v: string) {
  emit('update:modelValue', v ?? '')
  emit('change', v ?? '')
}
</script>
<template>
  <el-select
    :model-value="modelValue"
    filterable
    :clearable="clearable"
    :placeholder="placeholder"
    :size="size"
    @update:model-value="update"
  >
    <el-option-group v-for="g in groups" :key="g.id" :label="g.name">
      <el-option v-for="l in g.lines" :key="l.id" :label="l.name" :value="l.name">
        {{ l.name }}
        <small v-if="!l.active" class="muted">（已停用）</small>
      </el-option>
    </el-option-group>
    <el-option v-if="unassigned" label="未指定" value="未指定" />
  </el-select>
</template>
