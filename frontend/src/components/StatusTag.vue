<script setup lang="ts">
import { stageName, statusName } from '../utils'
/** kind="stage" 时按报价跟进阶段显示（待客户确认、待生成合同、已转合同…），否则按报价状态显示。 */
defineProps<{ status: string; kind?: 'status' | 'stage' }>()
</script>
<template>
  <span :class="['status-tag', status.toLowerCase()]">
    <i></i>
    {{ (kind === 'stage' ? stageName[status] : statusName[status]) || status }}
  </span>
</template>
