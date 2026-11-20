<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { cityGroups, dict, loadDictionaries } from '../dictionaries'

/** 城市选择：来自城市字典，按省份分组；可按城市名、省份或别名（区县）搜索。 */
const model = defineModel<string>({ default: '' })
withDefaults(defineProps<{ placeholder?: string; clearable?: boolean }>(), {
  placeholder: '选择城市',
  clearable: true,
})
const query = ref('')
onMounted(() => loadDictionaries())
const groups = computed(() => {
  const q = query.value.trim()
  return cityGroups(model.value)
    .map((g) => ({
      province: g.province,
      cities: g.cities.filter((c) => !q || `${c.name}${c.province || ''}${c.aliases || ''}`.includes(q)),
    }))
    .filter((g) => g.cities.length)
})
const aliasHint = (name: string) => dict.cities.find((c) => c.name === name)?.aliases || ''
</script>
<template>
  <el-select
    v-model="model"
    filterable
    :filter-method="(q: string) => (query = q)"
    :clearable="clearable"
    :placeholder="placeholder"
    @visible-change="(v: boolean) => !v && (query = '')"
  >
    <el-option-group v-for="g in groups" :key="g.province" :label="g.province">
      <el-option v-for="c in g.cities" :key="c.id" :value="c.name" :label="c.name">
        <span>{{ c.name }}</span>
        <small v-if="query && aliasHint(c.name).includes(query)" class="alias">含 {{ query }}</small>
      </el-option>
    </el-option-group>
  </el-select>
</template>
<style scoped>
.alias {
  float: right;
  color: #66716a;
  font-size: 13px;
}
</style>
