<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import StatusTag from './StatusTag.vue'
import type { Contract } from '../types'
import { money, percent, day } from '../utils'

const props = withDefaults(
  defineProps<{ contracts: Contract[]; actions?: boolean; attachments?: boolean; pageSize?: number }>(),
  {
    actions: false,
    attachments: false,
    pageSize: 10,
  },
)
const emit = defineEmits<{ confirm: [Contract]; receipt: [Contract]; refund: [Contract]; attachments: [Contract] }>()

const keyword = ref(''),
  status = ref(''),
  customer = ref(''),
  page = ref(1)
const customers = computed(() => [...new Set(props.contracts.map((c) => c.customer_name))].sort())
const filtered = computed(() =>
  props.contracts.filter(
    (c) =>
      (!status.value ||
        (status.value === 'UNPAID'
          ? c.outstanding > 0
          : status.value === 'OVERDUE'
            ? c.overdue > 0
            : c.confirmation === status.value)) &&
      (!customer.value || c.customer_name === customer.value) &&
      `${c.contract_no ?? ''}${c.customer_name}${c.items.map((i) => i.project_name).join('')}`.includes(
        keyword.value.trim(),
      ),
  ),
)
watch([keyword, status, customer], () => (page.value = 1))
const rows = computed(() => filtered.value.slice((page.value - 1) * props.pageSize, page.value * props.pageSize))
const sum = (k: 'amount' | 'received' | 'outstanding') => filtered.value.reduce((s, c) => s + Number(c[k]), 0)
const confirmName = { PENDING: '待确认', PARTIAL: '部分确认', CONFIRMED: '已确认' }
const confirmClass = { PENDING: 'tag-pending', PARTIAL: 'tag-partial', CONFIRMED: 'tag-done' }
</script>
<template>
  <div class="contract-table">
    <div class="filter-bar contract-filters">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索合同号、客户或门店" />
      <el-select v-model="customer" clearable placeholder="全部客户">
        <el-option v-for="c in customers" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="status" clearable placeholder="全部状态">
        <el-option label="待确认应收" value="PENDING" />
        <el-option label="部分确认" value="PARTIAL" />
        <el-option label="已确认" value="CONFIRMED" />
        <el-option label="仍有未收" value="UNPAID" />
        <el-option label="有逾期" value="OVERDUE" />
      </el-select>
      <span class="contract-sum">
        {{ filtered.length }} 份合同 · 合同额
        <strong>{{ money(sum('amount')) }}</strong>
        · 已到账
        <strong>{{ money(sum('received')) }}</strong>
        · 未收
        <strong>{{ money(sum('outstanding')) }}</strong>
      </span>
    </div>
    <el-table :data="rows" row-key="contract_key" size="default">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table :data="row.items" size="small" class="contract-items">
            <el-table-column label="门店" prop="project_name" min-width="240" show-overflow-tooltip />
            <el-table-column label="品牌" prop="brand" width="80" />
            <el-table-column label="下单日期" width="110">
              <template #default="{ row: i }">{{ day(i.order_date) }}</template>
            </el-table-column>
            <el-table-column label="发货" width="90">
              <template #default="{ row: i }">{{ i.shipping_status || '未登记' }}</template>
            </el-table-column>
            <el-table-column label="报价状态" width="110">
              <template #default="{ row: i }"><StatusTag :status="i.status" /></template>
            </el-table-column>
            <el-table-column label="金额" align="right" min-width="120">
              <template #default="{ row: i }">{{ money(i.amount) }}</template>
            </el-table-column>
            <el-table-column label="已到账" align="right" min-width="120">
              <template #default="{ row: i }">{{ money(i.received) }}</template>
            </el-table-column>
            <el-table-column label="未收" align="right" min-width="120">
              <template #default="{ row: i }">{{ money(i.outstanding) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column label="合同号" min-width="170">
        <template #default="{ row }">
          <strong v-if="row.contract_no">{{ row.contract_no }}</strong>
          <span v-else class="muted">未填合同号</span>
          <small class="cell-secondary">{{ row.customer_name }} · {{ row.brands.join(' / ') }}</small>
        </template>
      </el-table-column>
      <el-table-column label="下单" width="105">
        <template #default="{ row }">{{ day(row.order_date) }}</template>
      </el-table-column>
      <el-table-column label="门店 / 发货" width="100" align="center">
        <template #default="{ row }">{{ row.projects }} / {{ row.shipped }}</template>
      </el-table-column>
      <el-table-column label="合同额" align="right" min-width="125">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="已到账" align="right" min-width="125">
        <template #default="{ row }">{{ money(row.received) }}</template>
      </el-table-column>
      <el-table-column label="未收" align="right" min-width="125">
        <template #default="{ row }">
          <span :class="row.overdue > 0 ? 'danger-text' : ''">{{ money(row.outstanding) }}</span>
          <small v-if="row.overdue > 0" class="cell-secondary danger-text">逾期 {{ money(row.overdue) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="回款率" width="120">
        <template #default="{ row }">
          <div class="rate-cell">
            <span class="rate-track">
              <i :style="{ width: `${Math.min(100, (row.collection_rate || 0) * 100)}%` }"></i>
            </span>
            {{ percent(row.collection_rate) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="应收" width="100">
        <template #default="{ row }">
          <span :class="['confirm-tag', confirmClass[row.confirmation as keyof typeof confirmClass]]">
            {{ confirmName[row.confirmation as keyof typeof confirmName] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="actions || attachments" label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="attachments"
            link
            :type="row.attachments ? 'primary' : 'info'"
            @click="emit('attachments', row)"
          >
            附件{{ row.attachments ? ` ${row.attachments}` : '' }}
          </el-button>
          <template v-if="actions">
            <el-button v-if="row.confirmation !== 'CONFIRMED'" link type="primary" @click="emit('confirm', row)">
              确认应收
            </el-button>
            <template v-else>
              <el-button v-if="row.outstanding > 0" link type="primary" @click="emit('receipt', row)">
                登记回款
              </el-button>
              <el-button v-if="row.received > 0" link @click="emit('refund', row)">退款</el-button>
            </template>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="filtered.length > pageSize"
      v-model:current-page="page"
      class="contract-pager"
      layout="prev, pager, next, total"
      :total="filtered.length"
      :page-size="pageSize"
      small
    />
  </div>
</template>
<style scoped>
.contract-filters {
  flex-wrap: wrap;
  gap: 10px;
}
.contract-filters .el-input {
  max-width: 260px;
}
.contract-filters .el-select {
  width: 150px;
}
.contract-sum {
  margin-left: auto;
  color: #6b7a72;
  font-size: 12px;
}
.contract-sum strong {
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.contract-items {
  margin: 4px 0 8px 48px;
  width: calc(100% - 48px);
}
.rate-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  font-variant-numeric: tabular-nums;
}
.rate-track {
  flex: 0 0 44px;
  height: 6px;
  border-radius: 3px;
  background: #eef1ec;
  overflow: hidden;
}
.rate-track i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: #c27c0e;
}
.confirm-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.tag-pending {
  color: #8a6a1f;
  background: #f7efdb;
}
.tag-partial {
  color: #3b5f8a;
  background: #e7eef7;
}
.tag-done {
  color: #24634f;
  background: #e6f0ea;
}
.contract-pager {
  justify-content: flex-end;
  margin: 12px 24px 4px;
}
.cell-secondary {
  display: block;
}
</style>
