<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import type { Contract } from '../types'
import { money, percent, day } from '../utils'
import { contractStatusName, invoiceStatusName } from '../dictionaries'
import { canSeeCost, session } from '../api'

/** 合同台账表：快捷筛选 + 条件筛选 + 合计；点击合同打开详情。 */
const props = withDefaults(
  defineProps<{ contracts: Contract[]; pageSize?: number; compact?: boolean; initialQuick?: string }>(),
  {
    pageSize: 12,
    compact: false,
  },
)
const emit = defineEmits<{ open: [Contract] }>()

/** 快捷筛选：名称、条件。 */
const QUICK: { key: string; label: string; test: (c: Contract) => boolean }[] = [
  { key: 'ALL', label: '全部', test: () => true },
  { key: 'DRAFT', label: '拟定中', test: (c) => c.status === 'DRAFT' },
  { key: 'PENDING', label: '待确认应收', test: (c) => c.projects > 0 && c.confirmation !== 'CONFIRMED' },
  { key: 'UNPAID', label: '有未收', test: (c) => Number(c.outstanding) > 0 },
  { key: 'OVERDUE', label: '已逾期', test: (c) => Number(c.overdue) > 0 },
  {
    key: 'UNINVOICED',
    label: '未开足票',
    test: (c) => c.status !== 'DRAFT' && c.status !== 'TERMINATED' && Number(c.uninvoiced) > 0,
  },
  { key: 'INV_UNPAID', label: '开票未到款', test: (c) => Number(c.invoiced_unreceived) > 0 },
  { key: 'UNSHIPPED', label: '未发完货', test: (c) => c.projects > c.shipped },
  { key: 'CHANGES', label: '待审批变更', test: (c) => c.pending_changes > 0 },
  {
    key: 'UNARCHIVED',
    label: '原件未归档',
    test: (c) => (c.status === 'SIGNED' || c.status === 'COMPLETED') && !c.archived,
  },
]

const quick = ref(props.initialQuick || 'ALL'),
  keyword = ref(''),
  customer = ref(''),
  company = ref(''),
  brand = ref(''),
  city = ref(''),
  owner = ref(''),
  status = ref(''),
  period = ref(''),
  page = ref(1),
  sort = ref<{ prop: string; order: 'ascending' | 'descending' | null }>({ prop: '', order: null })

const showOwner = computed(() => session.user?.role !== 'SALES')
const uniq = (xs: (string | null | undefined)[]) =>
  [...new Set(xs.filter((x): x is string => !!x))].sort((a, b) => a.localeCompare(b, 'zh-CN'))
const customers = computed(() => uniq(props.contracts.map((c) => c.customer_name)))
const companies = computed(() => uniq(props.contracts.map((c) => c.company_name)))
const brands = computed(() => uniq(props.contracts.flatMap((c) => c.brands)))
const cities = computed(() => uniq(props.contracts.flatMap((c) => c.cities)))
const owners = computed(() => uniq(props.contracts.map((c) => c.owner_name)))

/** 签订月份（无签订日期按下单月份）。 */
const monthOf = (c: Contract) => String(c.signed_on || c.order_date || '').slice(0, 7)
const ym = (y: number, m: number) => `${y}-${String(m).padStart(2, '0')}`
/** 签订期间：本月、上月、本季度、今年、去年，以及数据中出现的各年份。 */
const periods = computed(() => {
  const now = new Date(),
    y = now.getFullYear(),
    m = now.getMonth() + 1,
    q = Math.floor((m - 1) / 3) * 3 + 1
  const last = m === 1 ? ym(y - 1, 12) : ym(y, m - 1)
  const out = [
    { key: 'this-month', label: '本月', from: ym(y, m), to: ym(y, m) },
    { key: 'last-month', label: '上月', from: last, to: last },
    { key: 'this-quarter', label: '本季度', from: ym(y, q), to: ym(y, q + 2) },
    { key: 'this-year', label: '今年', from: ym(y, 1), to: ym(y, 12) },
    { key: 'last-year', label: '去年', from: ym(y - 1, 1), to: ym(y - 1, 12) },
  ]
  const years = [...new Set(props.contracts.map((c) => monthOf(c).slice(0, 4)).filter((x) => /^\d{4}$/.test(x)))]
    .map(Number)
    .filter((x) => x < y - 1)
    .sort((a, b) => b - a)
  for (const x of years) out.push({ key: `y${x}`, label: `${x} 年`, from: ym(x, 1), to: ym(x, 12) })
  out.push({ key: 'none', label: '无日期', from: '', to: '' })
  return out
})

const base = computed(() =>
  props.contracts.filter((c) => {
    const k = keyword.value.trim().toLowerCase()
    if (
      k &&
      !`${c.contract_no ?? ''}${c.title ?? ''}${c.content ?? ''}${c.customer_name}${c.items
        .map((i) => i.project_name)
        .join('')}`
        .toLowerCase()
        .includes(k)
    )
      return false
    if (customer.value && c.customer_name !== customer.value) return false
    if (company.value && (company.value === '-' ? c.company_name : c.company_name !== company.value)) return false
    if (brand.value && !c.brands.includes(brand.value)) return false
    if (city.value && !c.cities.includes(city.value)) return false
    if (owner.value && (owner.value === '-' ? c.owner_name : c.owner_name !== owner.value)) return false
    if (status.value && c.status !== status.value) return false
    if (period.value) {
      const range = periods.value.find((p) => p.key === period.value)
      const m = monthOf(c)
      if (!range) return false
      if (range.key === 'none' ? !!m : !m || m < range.from || m > range.to) return false
    }
    return true
  }),
)
const counts = computed(() => Object.fromEntries(QUICK.map((q) => [q.key, base.value.filter(q.test).length])))
const filtered = computed(() => {
  const q = QUICK.find((x) => x.key === quick.value) || QUICK[0]
  const rows = base.value.filter(q.test)
  const { prop, order } = sort.value
  if (!prop || !order) return rows
  const val = (c: Contract) => {
    const v = (c as unknown as Record<string, unknown>)[prop]
    return typeof v === 'number' || (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v)) ? Number(v) : String(v ?? '')
  }
  return [...rows].sort((a, b) => {
    const x = val(a),
      y = val(b)
    const r = x < y ? -1 : x > y ? 1 : 0
    return order === 'ascending' ? r : -r
  })
})
watch([quick, keyword, customer, company, brand, city, owner, status, period], () => (page.value = 1))
const rows = computed(() => filtered.value.slice((page.value - 1) * props.pageSize, page.value * props.pageSize))
const sum = (k: keyof Contract) => filtered.value.reduce((s, c) => s + Number(c[k] || 0), 0)
const anyFilter = computed(
  () =>
    !!(
      keyword.value ||
      customer.value ||
      company.value ||
      brand.value ||
      city.value ||
      owner.value ||
      status.value ||
      period.value
    ) || quick.value !== 'ALL',
)
function reset() {
  quick.value = 'ALL'
  keyword.value = customer.value = company.value = brand.value = city.value = owner.value = status.value = ''
  period.value = ''
}
const statusClass: Record<string, string> = {
  DRAFT: 'tag-pending',
  SIGNED: 'tag-partial',
  COMPLETED: 'tag-done',
  TERMINATED: 'tag-muted',
}
defineExpose({ filtered })
</script>
<template>
  <div class="contract-table">
    <div class="quick-row">
      <button
        v-for="q in QUICK"
        :key="q.key"
        type="button"
        :class="['quick', { on: quick === q.key }]"
        @click="quick = q.key"
      >
        {{ q.label }}
        <em>{{ counts[q.key] }}</em>
      </button>
    </div>
    <div class="filter-bar contract-filters">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="合同号、名称、内容、客户或门店" />
      <el-select v-model="customer" clearable filterable placeholder="客户">
        <el-option v-for="c in customers" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-if="!compact" v-model="company" clearable placeholder="签约主体">
        <el-option label="未指定" value="-" />
        <el-option v-for="c in companies" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="brand" clearable filterable placeholder="业务线">
        <el-option v-for="c in brands" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="city" clearable filterable placeholder="城市">
        <el-option v-for="c in cities" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-if="showOwner && !compact" v-model="owner" clearable filterable placeholder="负责人">
        <el-option label="未分配" value="-" />
        <el-option v-for="c in owners" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-if="!compact" v-model="status" clearable placeholder="合同状态">
        <el-option v-for="(n, k) in contractStatusName" :key="k" :label="n" :value="k" />
      </el-select>
      <el-select v-model="period" clearable placeholder="签订期间">
        <el-option v-for="p in periods" :key="p.key" :label="p.label" :value="p.key" />
      </el-select>
      <el-button v-if="anyFilter" link type="primary" @click="reset">清除筛选</el-button>
    </div>
    <div class="contract-sum">
      <span>
        <strong>{{ filtered.length }}</strong>
        份合同
      </span>
      <span>
        合同额
        <strong>{{ money(sum('amount')) }}</strong>
      </span>
      <span>
        已开票
        <strong>{{ money(sum('invoiced')) }}</strong>
      </span>
      <span>
        已到款
        <strong>{{ money(sum('received')) }}</strong>
      </span>
      <span>
        未收
        <strong>{{ money(sum('outstanding')) }}</strong>
      </span>
      <span v-if="sum('overdue') > 0" class="danger-text">
        逾期
        <strong class="danger-text">{{ money(sum('overdue')) }}</strong>
      </span>
    </div>
    <el-table
      :data="rows"
      row-key="contract_key"
      class="clickable-rows"
      @row-click="(row: Contract) => emit('open', row)"
      @sort-change="(s: any) => (sort = { prop: s.prop, order: s.order })"
    >
      <el-table-column label="合同" min-width="230">
        <template #default="{ row }">
          <strong v-if="row.contract_no">{{ row.contract_no }}</strong>
          <span v-else class="muted">未填合同号</span>
          <el-tag v-if="row.pending_changes" size="small" type="warning" class="gap">变更待审批</el-tag>
          <small class="cell-secondary ellipsis">
            {{
              row.title || row.items.map((i: { project_name: string }) => i.project_name).join('、') || '未填合同名称'
            }}
          </small>
          <small class="cell-secondary">{{ row.customer_name }} · {{ row.brands.join(' / ') || '未关联门店' }}</small>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <span :class="['confirm-tag', statusClass[row.status]]">{{ contractStatusName[row.status] }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact" label="签约主体 / 负责人" width="140">
        <template #default="{ row }">
          <span :class="row.company_name ? '' : 'muted'">{{ row.company_name || '未指定' }}</span>
          <small class="cell-secondary">{{ row.owner_name || '未分配' }}</small>
        </template>
      </el-table-column>
      <el-table-column label="签订 / 下单" prop="signed_on" width="108" sortable="custom">
        <template #default="{ row }">
          {{ day(row.signed_on) }}
          <small class="cell-secondary">{{ day(row.order_date) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="门店 / 发货" width="88" align="center">
        <template #default="{ row }">{{ row.projects }} / {{ row.shipped }}</template>
      </el-table-column>
      <el-table-column label="合同金额" prop="amount" align="right" min-width="120" sortable="custom">
        <template #default="{ row }">
          {{ money(row.amount) }}
          <small v-if="row.amount_gap && Number(row.amount_gap) !== 0" class="cell-secondary warn-text">
            订单差 {{ money(row.amount_gap) }}
          </small>
        </template>
      </el-table-column>
      <el-table-column label="已开票" prop="invoiced" align="right" min-width="118" sortable="custom">
        <template #default="{ row }">
          {{ money(row.invoiced) }}
          <small class="cell-secondary">{{ invoiceStatusName[row.invoice_status] }}</small>
        </template>
      </el-table-column>
      <el-table-column label="已到款" prop="received" align="right" min-width="118" sortable="custom">
        <template #default="{ row }">
          {{ money(row.received) }}
          <small class="cell-secondary">{{ percent(row.collection_rate) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="未收" prop="outstanding" align="right" min-width="118" sortable="custom">
        <template #default="{ row }">
          <span :class="row.overdue > 0 ? 'danger-text' : ''">{{ money(row.outstanding) }}</span>
          <small v-if="row.overdue > 0" class="cell-secondary danger-text">逾期 {{ row.overdue_days }} 天</small>
        </template>
      </el-table-column>
      <el-table-column
        v-if="!compact && canSeeCost()"
        label="毛利率"
        prop="gross_margin"
        align="right"
        width="96"
        sortable="custom"
      >
        <template #default="{ row }">
          <span :class="row.gross_margin != null && row.gross_margin < 0 ? 'danger-text' : ''">
            {{ row.gross_margin == null ? '—' : percent(row.gross_margin) }}
          </span>
          <small class="cell-secondary">
            {{ row.cost_source === 'ACTUAL' ? '实际成本' : row.cost_source === 'ESTIMATED' ? '报价预估' : '成本待补' }}
          </small>
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
  gap: 8px;
}
.contract-filters .el-input {
  max-width: 230px;
}
.contract-filters .el-select {
  width: 112px;
}
.contract-sum {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 0 24px 8px;
  color: #6b7a72;
  font-size: 12px;
}
.contract-sum strong {
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.clickable-rows :deep(.el-table__row) {
  cursor: pointer;
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
.tag-muted {
  color: #6b7a72;
  background: #eef1ec;
}
.warn-text {
  color: #a0661b;
}
.gap {
  margin-left: 6px;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}
.contract-pager {
  justify-content: flex-end;
  margin: 12px 24px 4px;
}
.cell-secondary {
  display: block;
}
</style>
