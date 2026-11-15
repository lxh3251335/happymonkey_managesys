<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { ReceiptRow } from '../types'
import { day, money } from '../utils'
import { canFinance } from '../api'

/**
 * 回款流水：合同收款 / 退款（展开看分摊到的门店）+ 未关联合同的历史门店流水。
 * 回款统一按合同登记；历史门店流水只能关联退款冲正。
 */
const props = defineProps<{ rows: ReceiptRow[] }>()
const emit = defineEmits<{ open: [ReceiptRow]; refundLegacy: [ReceiptRow] }>()

const PAGE = 15
const keyword = ref(''),
  customer = ref(''),
  kind = ref(''),
  period = ref(''),
  page = ref(1)
const customers = computed(() =>
  [...new Set(props.rows.map((r) => r.customer_name))].sort((a, b) => a.localeCompare(b, 'zh-CN')),
)
const months = computed(() => [...new Set(props.rows.map((r) => String(r.paid_on).slice(0, 7)))].sort().reverse())
const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return props.rows.filter(
    (r) =>
      (!customer.value || r.customer_name === customer.value) &&
      (!kind.value || `${r.source}:${r.kind}` === kind.value || (kind.value === 'STORE' && r.source === 'STORE')) &&
      (!period.value || String(r.paid_on).startsWith(period.value)) &&
      (!k ||
        [
          r.reference,
          r.contract_key,
          r.customer_name,
          r.note,
          r.project_name,
          ...r.allocations.map((a) => a.project_name),
        ]
          .filter(Boolean)
          .join(' ')
          .toLowerCase()
          .includes(k)),
  )
})
watch([keyword, customer, kind, period], () => (page.value = 1))
const sum = (k: 'RECEIPT' | 'REFUND') =>
  filtered.value.filter((r) => r.kind === k).reduce((s, r) => s + Number(r.amount), 0)
const contractLabel = (key: string) => (key.startsWith('#') ? '未填合同号' : key)
const typeName = (r: ReceiptRow) =>
  `${r.source === 'CONTRACT' ? '合同' : '门店（历史）'}${r.kind === 'RECEIPT' ? '回款' : '退款'}`
</script>
<template>
  <div>
    <div class="filter-bar ledger-filters">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="凭证号、合同号、客户或门店" />
      <el-select v-model="customer" clearable filterable placeholder="全部客户">
        <el-option v-for="c in customers" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="kind" clearable placeholder="全部类型">
        <el-option label="合同回款" value="CONTRACT:RECEIPT" />
        <el-option label="合同退款" value="CONTRACT:REFUND" />
        <el-option label="历史门店流水" value="STORE" />
      </el-select>
      <el-select v-model="period" clearable placeholder="全部月份">
        <el-option v-for="m in months" :key="m" :label="m" :value="m" />
      </el-select>
      <span class="ledger-sum">
        {{ filtered.length }} 笔 · 收款
        <strong>{{ money(sum('RECEIPT')) }}</strong>
        · 退款
        <strong>{{ money(sum('REFUND')) }}</strong>
        · 净回款
        <strong>{{ money(sum('RECEIPT') - sum('REFUND')) }}</strong>
      </span>
    </div>
    <el-table :data="filtered.slice((page - 1) * PAGE, page * PAGE)" row-key="id" empty-text="暂无回款流水">
      <el-table-column type="expand" width="36">
        <template #default="{ row }">
          <div class="alloc">
            <template v-if="row.allocations.length">
              <div class="alloc-title">分摊到 {{ row.allocations.length }} 个门店订单</div>
              <div v-for="a in row.allocations" :key="a.reference" class="alloc-line">
                <span>{{ a.project_name }}</span>
                <span class="muted">{{ a.reference }}</span>
                <strong>{{ money(a.amount) }}</strong>
              </div>
            </template>
            <div v-else class="muted">
              {{
                row.source === 'STORE' ? `门店：${row.project_name}（早期按门店登记，未关联合同收款）` : '无分摊明细'
              }}
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="日期" width="108">
        <template #default="{ row }">{{ day(row.paid_on) }}</template>
      </el-table-column>
      <el-table-column label="合同 / 客户" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" class="cell-link" @click="emit('open', row)">
            {{ contractLabel(row.contract_key) }}
          </el-button>
          <small class="cell-secondary">{{ row.customer_name }}</small>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <span :class="row.kind === 'RECEIPT' ? 'profit-text' : 'danger-text'">{{ typeName(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="金额" align="right" width="140">
        <template #default="{ row }">{{ row.kind === 'REFUND' ? '-' : '' }}{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="已退" align="right" width="110">
        <template #default="{ row }">
          <span v-if="row.kind === 'RECEIPT' && Number(row.refunded) > 0" class="danger-text">
            {{ money(row.refunded) }}
          </span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="凭证号" prop="reference" min-width="150" show-overflow-tooltip />
      <el-table-column label="备注" prop="note" min-width="140" show-overflow-tooltip />
      <el-table-column v-if="canFinance()" label="" width="90">
        <template #default="{ row }">
          <el-button
            v-if="row.source === 'STORE' && row.kind === 'RECEIPT' && Number(row.amount) > Number(row.refunded)"
            link
            @click="emit('refundLegacy', row)"
          >
            退款冲正
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="filtered.length > PAGE"
      v-model:current-page="page"
      class="ledger-pager"
      layout="prev, pager, next, total"
      :total="filtered.length"
      :page-size="PAGE"
      small
    />
  </div>
</template>
<style scoped>
.ledger-filters {
  flex-wrap: wrap;
  gap: 8px;
}
.ledger-filters > .el-input {
  max-width: 240px;
}
.ledger-filters > .el-select {
  width: 130px;
}
.ledger-sum {
  margin-left: auto;
  font-size: 12px;
  color: #6b7a72;
}
.ledger-sum strong {
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.alloc {
  padding: 4px 24px 8px 60px;
  font-size: 13px;
}
.alloc-title {
  color: #6b7a72;
  margin-bottom: 4px;
}
.alloc-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 160px 120px;
  gap: 12px;
  padding: 2px 0;
}
.alloc-line strong {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.cell-link {
  height: auto;
  padding: 0;
}
.cell-secondary {
  display: block;
}
.ledger-pager {
  justify-content: flex-end;
  margin: 12px 24px 4px;
}
</style>
