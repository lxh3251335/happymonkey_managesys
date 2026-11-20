<script setup lang="ts">
/** 页面标题行：标题与说明同一行，右侧放操作，尽量少占纵向空间。eyebrow 保留参数以兼容各页面，不再显示。 */
defineProps<{ eyebrow?: string; title: string; description?: string }>()
</script>
<template>
  <div class="page-heading">
    <div class="heading-text">
      <h1>{{ title }}</h1>
      <p v-if="description || $slots.description" :title="description">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div class="heading-actions"><slot /></div>
  </div>
</template>
