<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import ExcelTools from '../components/ExcelTools.vue'
import ProjectTypeManager from '../components/ProjectTypeManager.vue'
import SupplierTemplateManager from '../components/SupplierTemplateManager.vue'
import CustomerTemplateManager from '../components/CustomerTemplateManager.vue'
import TemplateManager from '../components/TemplateManager.vue'
import { api, canSeeCost, canWrite, isAdmin } from '../api'
import type { Product } from '../types'

/**
 * 报价配置：报价所需的全部设置集中在一处。
 * 项目类型（标准配置与售价）→ 客户报价模板（某客户的协议配置与售价，优先于标准配置）→ 供应商成本模板（报价成本的唯一来源）
 * → 报价单版式（抬头与条款）。供应商报价页（SupplierOffers）与供应商成本重叠且无数据，已从页面收起，组件与接口保留。
 */
const route = useRoute(),
  router = useRouter()
const tabs = computed(() =>
  [
    { name: 'types', label: '项目类型', show: true },
    { name: 'customers', label: '客户报价模板', show: true },
    { name: 'costs', label: '供应商成本', show: canSeeCost() },
    { name: 'templates', label: '报价单版式', show: isAdmin() },
  ].filter((t) => t.show),
)
const tab = ref(tabs.value.some((t) => t.name === route.query.tab) ? String(route.query.tab) : 'types')
const products = ref<Product[]>([]),
  reloadKey = ref(0),
  typeManager = ref<InstanceType<typeof ProjectTypeManager>>()
/** 从“供应商成本”跳到“项目类型”并打开该类型的编辑。 */
async function configure(typeId: string) {
  tab.value = 'types'
  switchTab('types')
  await nextTick()
  await typeManager.value?.openById(typeId)
}
const excel = computed(() =>
  tab.value === 'types'
    ? { dataset: 'project-types', title: '项目类型', canImport: canWrite() }
    : tab.value === 'costs'
      ? { dataset: 'supplier-templates', title: '供应商成本模板', canImport: canSeeCost() }
      : null,
)
function switchTab(name: string | number) {
  router.replace({ query: { ...route.query, tab: String(name) } })
}
onMounted(async () => {
  try {
    products.value = await api<Product[]>('/products')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
})
</script>
<template>
  <div>
    <PageHeader title="报价配置">
      <ExcelTools
        v-if="excel"
        :key="excel.dataset"
        :dataset="excel.dataset"
        :title="excel.title"
        :can-import="excel.canImport"
        @imported="reloadKey++"
      />
    </PageHeader>
    <section class="panel setup-panel">
      <el-tabs v-model="tab" @tab-change="switchTab">
        <el-tab-pane v-for="t in tabs" :key="t.name" :label="t.label" :name="t.name" />
      </el-tabs>
      <ProjectTypeManager v-if="tab === 'types'" ref="typeManager" :key="`t${reloadKey}`" :products="products" />
      <CustomerTemplateManager v-else-if="tab === 'customers'" />
      <SupplierTemplateManager v-else-if="tab === 'costs'" :key="`c${reloadKey}`" @configure="configure" />
      <TemplateManager v-else-if="tab === 'templates'" />
    </section>
  </div>
</template>
<style scoped>
.setup-panel {
  padding: 4px 24px 20px;
}
</style>
