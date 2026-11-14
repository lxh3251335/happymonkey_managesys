<script setup lang="ts">
import { statusName } from '../utils'
defineProps<{ status: string }>()
</script>
<template>
  <span :class="['status-tag', status.toLowerCase()]">
    <i></i>
    {{ statusName[status] || status }}
  </span>
</template>
