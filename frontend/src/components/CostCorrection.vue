<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { Quote } from '../types'
import { money, percent } from '../utils'

const props = defineProps<{ modelValue: boolean; quote?: Quote }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

interface Row {
  itemId: string
  name: string
  specification: string
  quantity: number
  amount: number | null
  materialCost: number | undefined
  laborCost: number | undefined
  financeCost: number | undefined
}
const rows = ref<Row[]>([]),
  reason = ref(''),
  busy = ref(false)
const n = (v: number | null) => (v == null ? undefined : Number(v))

watch(
  () => props.modelValue,
  (open) => {
    if (!open || !props.quote) return
    reason.value = ''
    rows.value = props.quote.items.map((i) => {
      const split = i.material_cost != null || i.labor_cost != null || i.finance_cost != null
      return {
        itemId: i.id,
        name: i.name,
        specification: i.specification,
        quantity: Number(i.quantity),
        amount: i.amount == null ? null : Number(i.amount),
        // 旧报价没有拆分：原单台成本先放在材料成本里
        materialCost: split ? n(i.material_cost) : n(i.unit_cost),
        laborCost: n(i.labor_cost),
        financeCost: n(i.finance_cost),
      }
    })
  },
)
const unit = (r: Row) =>
  r.materialCost == null && r.laborCost == null && r.financeCost == null
    ? null
    : (r.materialCost || 0) + (r.laborCost || 0) + (r.financeCost || 0)
const lineCost = (r: Row) => {
  const u = unit(r)
  return u == null ? null : Math.round(r.quantity * u * 100) / 100
}
const amount = computed(() => rows.value.reduce((s, r) => s + (r.amount || 0), 0))
const cost = computed(() =>
  rows.value.some((r) => lineCost(r) == null) ? null : rows.value.reduce((s, r) => s + lineCost(r)!, 0),
)

async function save() {
  if (!props.quote) return
  if (!reason.value.trim()) return ElMessage.warning('请填写修正原因，例如“供应商对账”')
  busy.value = true
  try {
    const r = await api<{ changed: number }>(`/quotes/${props.quote.id}/costs`, {
      method: 'PUT',
      body: JSON.stringify({
        reason: reason.value.trim(),
        items: rows.value.map((x) => ({
          itemId: x.itemId,
          materialCost: x.materialCost ?? null,
          laborCost: x.laborCost ?? null,
          financeCost: x.financeCost ?? null,
        })),
      }),
    })
    ElMessage.success(r.changed ? `已修正 ${r.changed} 行成本` : '成本没有变化')
    emit('update:modelValue', false)
    emit('saved')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="修正成本"
    width="980px"
    :close-on-click-modal="false"
  >
    <el-table :data="rows" size="small">
      <el-table-column label="产品 / 规格" min-width="200">
        <template #default="{ row }">
          {{ row.name }}
          <small class="muted">{{ row.specification }}</small>
        </template>
      </el-table-column>
      <el-table-column label="数量" prop="quantity" width="60" align="right" />
      <el-table-column label="材料成本" width="120">
        <template #default="{ row }">
          <el-input-number v-model="row.materialCost" :min="0" :precision="2" :controls="false" />
        </template>
      </el-table-column>
      <el-table-column label="人工成本" width="110">
        <template #default="{ row }">
          <el-input-number v-model="row.laborCost" :min="0" :precision="2" :controls="false" />
        </template>
      </el-table-column>
      <el-table-column label="财务成本" width="110">
        <template #default="{ row }">
          <el-input-number v-model="row.financeCost" :min="0" :precision="2" :controls="false" />
        </template>
      </el-table-column>
      <el-table-column label="单台成本" width="110" align="right">
        <template #default="{ row }">{{ unit(row) == null ? '待补充' : money(unit(row)) }}</template>
      </el-table-column>
      <el-table-column label="毛利率" width="88" align="right">
        <template #default="{ row }">
          {{ row.amount && lineCost(row) != null ? percent((row.amount - lineCost(row)!) / row.amount) : '—' }}
        </template>
      </el-table-column>
    </el-table>
    <div class="fix-total">
      报价 {{ money(amount) }} · 成本 {{ cost == null ? '待补充' : money(cost) }} · 毛利率
      <strong>{{ cost == null || !amount ? '待补充' : percent((amount - cost) / amount) }}</strong>
    </div>
    <el-form label-position="top">
      <el-form-item label="修正原因" required>
        <el-input v-model="reason" maxlength="300" placeholder="例如：按 11 月供应商对账单更新材料成本" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存修正</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.fix-total {
  margin: 12px 0;
  text-align: right;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.fix-total strong {
  font-size: 16px;
}
:deep(.el-input-number) {
  width: 100%;
}
small {
  margin-left: 6px;
}
</style>
