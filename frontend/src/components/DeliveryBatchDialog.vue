<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { Project } from '../types'
import { today } from '../utils'
import { matchStores, parseTrackingLines } from '../parsing/delivery'

/**
 * 批量粘贴运单：供应商或快递一次发来多行“门店 + 运单号”，整段贴进来，
 * 按门店名称（或项目编号、合同号）对到门店，核对后一起登记为已发货。
 */
const props = defineProps<{ modelValue: boolean; projects: Project[] }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

interface Row {
  line: string
  hint: string
  courier: string
  courierGuessed: boolean
  trackingNo: string
  /** 选中的门店；对不上或有多个候选时为空，由人来选。 */
  projectId: string
  candidates: Project[]
}
const text = ref(''),
  rows = ref<Row[]>([]),
  couriers = ref<string[]>([]),
  busy = ref(false)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    text.value = ''
    rows.value = []
    if (!couriers.value.length) couriers.value = await api<string[]>('/projects/couriers').catch(() => [])
  },
)
watch(text, (t) => {
  rows.value = parseTrackingLines(t, couriers.value).map((l) => {
    const candidates = matchStores(l.hint, props.projects)
    return {
      line: l.line,
      hint: l.hint,
      courier: l.courier || '',
      courierGuessed: !!l.courierGuessed,
      trackingNo: l.trackingNo || '',
      projectId: candidates.length === 1 ? candidates[0].id : '',
      candidates,
    }
  })
})

const byId = computed(() => new Map(props.projects.map((p) => [p.id, p])))
/** 同一门店被多行选中。 */
const duplicated = computed(() => {
  const seen = new Set<string>(),
    dup = new Set<string>()
  for (const r of rows.value) if (r.projectId) (seen.has(r.projectId) ? dup : seen).add(r.projectId)
  return dup
})
function problem(r: Row): string {
  if (!r.trackingNo) return '没有认出运单号'
  if (!r.projectId) return r.candidates.length > 1 ? '有多个门店相近，请选择' : '没有对上门店，请选择'
  if (duplicated.value.has(r.projectId)) return '这个门店出现了多次'
  return ''
}
function note(r: Row): string {
  const old = byId.value.get(r.projectId)?.tracking_no
  if (old && old !== r.trackingNo) return `将替换原运单 ${old}`
  if (old === r.trackingNo) return '与已登记的运单相同'
  return r.courierGuessed ? '快递公司按单号推断' : ''
}
const ready = computed(() => rows.value.filter((r) => !problem(r)))

async function save() {
  if (!ready.value.length) return
  busy.value = true
  let done = 0
  const failed: string[] = []
  for (const r of ready.value) {
    const p = byId.value.get(r.projectId)!
    try {
      await api(`/projects/${p.id}/delivery`, {
        method: 'PUT',
        body: JSON.stringify({
          status: '已发货',
          expectedShipOn: p.expected_ship_on ? String(p.expected_ship_on).slice(0, 10) : null,
          // 已有发货日期的保留，没有的记今天
          shippedOn: p.shipped_on ? String(p.shipped_on).slice(0, 10) : today(),
          courier: r.courier,
          trackingNo: r.trackingNo.replace(/\s/g, ''),
          consignee: p.consignee || '',
          consigneePhone: p.consignee_phone || '',
          shipAddress: p.ship_address || '',
        }),
      })
      done++
    } catch (e) {
      failed.push(`${p.name}：${(e as Error).message}`)
    }
  }
  busy.value = false
  if (done) emit('saved')
  if (failed.length)
    return ElMessage.error({ message: `已登记 ${done} 个，${failed.length} 个失败：${failed[0]}`, duration: 8000 })
  ElMessage.success(`已为 ${done} 个门店登记运单并标记已发货`)
  emit('update:modelValue', false)
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="批量粘贴运单"
    width="min(940px, 96vw)"
    :close-on-click-modal="false"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-input
      v-model="text"
      type="textarea"
      :rows="4"
      placeholder="每行一个门店和它的运单号，可以带快递公司。例如：&#10;小象成都盛泰站 顺丰 SF1234567890123&#10;快乐猴杭州临平上环桥店 DPK202611150001"
    />
    <el-table v-if="rows.length" :data="rows" size="small" class="editor-table" max-height="340">
      <el-table-column label="粘贴的内容" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.hint || row.line }}</template>
      </el-table-column>
      <el-table-column label="门店" min-width="226">
        <template #default="{ row }">
          <el-select v-model="row.projectId" filterable clearable placeholder="选择门店">
            <!-- 有候选时先列候选；没有对上时可以在全部门店里搜 -->
            <el-option
              v-for="p in row.candidates.length ? row.candidates : projects"
              :key="p.id"
              :value="p.id"
              :label="p.name"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="快递公司" width="132">
        <template #default="{ row }">
          <el-select v-model="row.courier" filterable allow-create clearable default-first-option placeholder="未认出">
            <el-option v-for="c in couriers" :key="c" :label="c" :value="c" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="运单号" width="170">
        <template #default="{ row }"><el-input v-model="row.trackingNo" class="mono" /></template>
      </el-table-column>
      <el-table-column label="说明" min-width="190">
        <template #default="{ row }">
          <span v-if="problem(row)" class="danger-text">{{ problem(row) }}</span>
          <span v-else :class="note(row) ? 'warn' : 'ok'">{{ note(row) || '可以登记' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <span v-if="rows.length" class="muted count">
        共 {{ rows.length }} 行，{{ ready.length }} 行可以登记{{
          rows.length > ready.length ? `，${rows.length - ready.length} 行需要处理（不处理则跳过）` : ''
        }}
      </span>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!ready.length" @click="save">
        登记 {{ ready.length || '' }} 个门店
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.count {
  float: left;
  line-height: 36px;
  font-size: 13px;
}
.warn {
  color: #96670f;
}
.ok {
  color: #24634f;
}
</style>
