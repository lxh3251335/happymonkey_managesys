<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import type { Contract } from '../types'
import { money, percent, day } from '../utils'
import { contractStatusName, invoiceStatusName } from '../dictionaries'
import { canSeeCost, session } from '../api'
import { useUrlState } from '../urlState'

/** 合同台账表：快捷筛选 + 条件筛选 + 合计；点击合同打开详情。 */
const props = withDefaults(
  defineProps<{
    contracts: Contract[]
    pageSize?: number
    compact?: boolean
    initialQuick?: string
    /** 显示“批量设置截止日”入口（对当前筛选结果中未设截止日的合同）。 */
    batchDue?: boolean
    /** 把筛选和页码写进地址栏（合同台账页用；首页里的小表不用）。 */
    syncUrl?: boolean
  }>(),
  {
    pageSize: 12,
    compact: false,
  },
)
const emit = defineEmits<{ open: [Contract]; 'batch-due': [Contract[]] }>()

/**
 * 快捷筛选：名称、条件。
 * 默认只摆出四项日常待办（逾期、未收、未开足票、未发完货）；拟定中、待审批变更有数量时才出现；
 * 其余收进“更多”，避免十个同样大小的按钮排成一行分不出主次。
 */
const QUICK: { key: string; label: string; test: (c: Contract) => boolean }[] = [
  { key: 'ALL', label: '全部', test: () => true },
  { key: 'DRAFT', label: '拟定中', test: (c) => c.status === 'DRAFT' },
  // 合同签订即有应收；没有截止日期的应收不计逾期，需要补上
  { key: 'UNDATED', label: '未设截止日', test: (c) => Number(c.undated) > 0 },
  { key: 'UNPAID', label: '有未收', test: (c) => Number(c.outstanding) > 0 },
  { key: 'OVERDUE', label: '已逾期', test: (c) => Number(c.overdue) > 0 },
  {
    key: 'UNINVOICED',
    label: '未开足票',
    test: (c) => c.status !== 'DRAFT' && c.status !== 'TERMINATED' && Number(c.uninvoiced) > 0,
  },
  { key: 'INV_UNPAID', label: '开票未回款', test: (c) => Number(c.invoiced_unreceived) > 0 },
  { key: 'UNSHIPPED', label: '未发完货', test: (c) => c.projects > c.shipped },
  { key: 'CHANGES', label: '待审批变更', test: (c) => c.pending_changes > 0 },
  {
    key: 'UNARCHIVED',
    label: '原件未归档',
    test: (c) => (c.status === 'SIGNED' || c.status === 'COMPLETED') && !c.archived,
  },
]

/** 常驻的待办。 */
const MAIN_QUICK = ['OVERDUE', 'UNPAID', 'UNINVOICED', 'UNSHIPPED']
/** 有数量时才出现的待办（需要人处理）。 */
const ALERT_QUICK = ['DRAFT', 'CHANGES']
/** 收进“更多”的。 */
const MORE_QUICK = ['UNDATED', 'INV_UNPAID', 'UNARCHIVED', 'DRAFT', 'CHANGES']
const quickOf = (key: string) => QUICK.find((q) => q.key === key)!

const quick = ref(props.initialQuick || 'ALL'),
  moreFilters = ref(false),
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
if (props.syncUrl) {
  const bind = useUrlState()
  bind('quick', quick, 'ALL')
  bind('q', keyword, '')
  bind('cust', customer, '')
  bind('co', company, '')
  bind('line', brand, '')
  bind('city', city, '')
  bind('owner', owner, '')
  bind('status', status, '')
  bind('period', period, '')
  bind('page', page, 1)
  // 藏在“更多条件”里的筛选有值时，展开它，免得看不到为什么数据变少了
  if (company.value || city.value || owner.value || status.value) moreFilters.value = true
}

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
    // “签订日期”列没有签订日期时显示下单日期，排序也按显示的日期
    const v = prop === 'signed_on' ? c.signed_on || c.order_date : (c as unknown as Record<string, unknown>)[prop]
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
const ratio = (a: number, b: number) => (b ? `${((a / b) * 100).toFixed(1)}%` : '—')
/** 毛利率分母：只算成本已知（有毛利）的合同。 */
const marginBase = computed(() =>
  filtered.value.filter((c) => c.profit != null).reduce((s, c) => s + Number(c.amount || 0), 0),
)
/** 合同行的第二行文字：客户 · 业务线 · 合同名称（没有名称时列出门店）。 */
const subtitle = (c: Contract) =>
  [
    c.customer_name,
    c.brands.join(' / ') || '未关联门店',
    c.title || c.items.map((i) => i.project_name).join('、') || '未填合同名称',
  ].join(' · ')
/** 当前筛选结果里还有应收没设截止日期的合同。 */
const undated = computed(() => filtered.value.filter((c) => Number(c.undated) > 0))
const drafts = computed(() => filtered.value.filter((c) => c.status === 'DRAFT').length)
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
/** 平时收起的筛选条件（城市、负责人、合同状态、签约主体）里有没有正在生效的。 */
const hiddenActive = computed(() => [company, city, owner, status].filter((x) => x.value).length)
/** 当前在行内显示的快捷筛选：常驻四项 + 有数量的提醒项 + 从“更多”里选中的那一项。 */
const shownQuick = computed(() => {
  const keys = [...MAIN_QUICK, ...ALERT_QUICK.filter((k) => counts.value[k] > 0)]
  if (quick.value !== 'ALL' && !keys.includes(quick.value)) keys.push(quick.value)
  return keys.map(quickOf)
})
/** “更多”下拉里的项：不在行内显示的那些。 */
const moreQuick = computed(() => MORE_QUICK.filter((k) => !shownQuick.value.some((q) => q.key === k)).map(quickOf))
function reset() {
  quick.value = 'ALL'
  moreFilters.value = false
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
    <!-- 第一行：按待办看。只有“全部”和少数几项常驻，数量为 0 的变淡 -->
    <div class="quick-row todo-row">
      <button type="button" :class="['quick', { on: quick === 'ALL' }]" @click="quick = 'ALL'">
        全部
        <em>{{ counts.ALL }}</em>
      </button>
      <span class="todo-label">待办</span>
      <button
        v-for="q in shownQuick"
        :key="q.key"
        type="button"
        :class="[
          'quick',
          {
            on: quick === q.key,
            zero: !counts[q.key],
            danger: q.key === 'OVERDUE' && counts[q.key] > 0,
            warn: ALERT_QUICK.includes(q.key) && counts[q.key] > 0,
          },
        ]"
        @click="quick = quick === q.key ? 'ALL' : q.key"
      >
        {{ q.label }}
        <em>{{ counts[q.key] }}</em>
      </button>
      <el-dropdown v-if="moreQuick.length" trigger="click" @command="(k: string) => (quick = k)">
        <button type="button" class="quick more">
          更多
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="q in moreQuick" :key="q.key" :command="q.key">
              <span class="more-item">
                {{ q.label }}
                <em>{{ counts[q.key] }}</em>
              </span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <!-- 第二行：按条件找。常用的三个条件在外面，其余点“更多条件”展开 -->
    <div class="filter-bar contract-filters">
      <el-input v-model="keyword" clearable :prefix-icon="Search" placeholder="搜索合同号、名称、客户或门店" />
      <el-select v-model="customer" clearable filterable placeholder="客户">
        <el-option v-for="c in customers" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="brand" clearable filterable placeholder="业务线">
        <el-option v-for="c in brands" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="period" clearable placeholder="签订期间">
        <el-option v-for="p in periods" :key="p.key" :label="p.label" :value="p.key" />
      </el-select>
      <template v-if="moreFilters || hiddenActive">
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
        <el-select v-if="!compact && companies.length > 1" v-model="company" clearable placeholder="签约主体">
          <el-option label="未指定" value="-" />
          <el-option v-for="c in companies" :key="c" :label="c" :value="c" />
        </el-select>
      </template>
      <el-button v-if="!hiddenActive" link class="more-filters" @click="moreFilters = !moreFilters">
        {{ moreFilters ? '收起' : '更多条件' }}
      </el-button>
      <el-button v-if="anyFilter" link type="primary" @click="reset">清除筛选</el-button>
    </div>
    <!-- 合计随筛选变化：不筛选时即全部合同的总览 -->
    <div class="contract-sum">
      <span>
        <strong>{{ filtered.length }}</strong>
        份合同
        <small v-if="drafts">拟定中 {{ drafts }}</small>
      </span>
      <span>
        合同额
        <strong>{{ money(sum('amount')) }}</strong>
      </span>
      <span>
        已开票
        <strong>{{ money(sum('invoiced')) }}</strong>
        <small>{{ ratio(sum('invoiced'), sum('amount')) }}</small>
      </span>
      <span>
        已回款
        <strong>{{ money(sum('received')) }}</strong>
        <small>回款率 {{ ratio(sum('received'), sum('amount')) }}</small>
      </span>
      <span>
        未收
        <strong>{{ money(sum('outstanding')) }}</strong>
      </span>
      <span v-if="sum('overdue') > 0" class="danger-text">
        逾期
        <strong class="danger-text">{{ money(sum('overdue')) }}</strong>
      </span>
      <span v-if="canSeeCost() && !compact" title="毛利率只按成本已知的合同计算">
        毛利
        <strong>{{ money(sum('profit')) }}</strong>
        <small>{{ ratio(sum('profit'), marginBase) }}</small>
      </span>
      <el-button
        v-if="batchDue && !compact && undated.length"
        link
        type="primary"
        size="small"
        class="sum-action"
        @click="emit('batch-due', undated)"
      >
        批量设置截止日（{{ undated.length }} 份未设）
      </el-button>
    </div>
    <el-table
      :data="rows"
      row-key="contract_key"
      class="clickable-rows dense"
      @row-click="(row: Contract) => emit('open', row)"
      @sort-change="(s: any) => (sort = { prop: s.prop, order: s.order })"
    >
      <el-table-column label="合同" min-width="236">
        <template #default="{ row }">
          <!-- 第一行：合同号 + 状态标签（状态不再单占一列，笔记本宽度下不必横向滚动） -->
          <strong v-if="row.contract_no">{{ row.contract_no }}</strong>
          <span v-else class="muted">未填合同号</span>
          <span :class="['confirm-tag', 'gap', statusClass[row.status]]">{{ contractStatusName[row.status] }}</span>
          <el-tag v-if="row.pending_changes" size="small" type="warning" class="gap">变更待审批</el-tag>
          <!-- 第二行：客户 · 业务线 · 合同名称，过长截断，悬停看全 -->
          <small class="cell-secondary ellipsis" :title="subtitle(row)">{{ subtitle(row) }}</small>
        </template>
      </el-table-column>
      <el-table-column v-if="!compact && showOwner" label="负责人" min-width="78">
        <template #default="{ row }">
          <span :class="row.owner_name ? '' : 'muted'">{{ row.owner_name || '未分配' }}</span>
        </template>
      </el-table-column>
      <!-- 没有签订日期的合同（多为历史订单）显示下单日期，并注明 -->
      <el-table-column label="签订日期" prop="signed_on" min-width="104" sortable="custom">
        <template #default="{ row }">
          <template v-if="row.signed_on">{{ day(row.signed_on) }}</template>
          <template v-else-if="row.order_date">
            {{ day(row.order_date) }}
            <small class="cell-secondary">下单日期</small>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="发货进度" min-width="112">
        <template #default="{ row }">
          <span :class="['nowrap', row.projects && row.shipped >= row.projects ? 'muted' : '']">
            已发 {{ row.shipped }} / {{ row.projects }} 店
          </span>
        </template>
      </el-table-column>
      <el-table-column label="合同金额" prop="amount" align="right" min-width="110" sortable="custom">
        <template #default="{ row }">
          {{ money(row.amount) }}
          <small v-if="row.amount_gap && Number(row.amount_gap) !== 0" class="cell-secondary warn-text">
            订单差 {{ money(row.amount_gap) }}
          </small>
        </template>
      </el-table-column>
      <el-table-column label="已开票" prop="invoiced" align="right" min-width="104" sortable="custom">
        <template #default="{ row }">
          {{ money(row.invoiced) }}
          <small class="cell-secondary">{{ invoiceStatusName[row.invoice_status] }}</small>
        </template>
      </el-table-column>
      <el-table-column label="已回款" prop="received" align="right" min-width="104" sortable="custom">
        <template #default="{ row }">
          {{ money(row.received) }}
          <small class="cell-secondary">{{ percent(row.collection_rate) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="未收" prop="outstanding" align="right" min-width="104" sortable="custom">
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
        min-width="90"
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
.contract-table .quick-row {
  padding-top: 10px;
}
.todo-label {
  margin: 0 2px 0 10px;
  padding-left: 12px;
  border-left: 1px solid #d3dbd3;
  color: var(--muted);
  font-size: 13px;
  line-height: 16px;
}
/* 数量为 0 的待办变淡：位置不变，但不抢眼 */
.quick.zero:not(.on) {
  border-color: transparent;
  background: transparent;
  color: #717c75;
}
.quick.zero:not(.on) em {
  color: #717c75;
}
.quick.danger:not(.on) {
  border-color: #e3b3a8;
  color: #a8432f;
  background: #fdf5f3;
}
.quick.danger:not(.on) em {
  color: #a8432f;
  font-weight: 600;
}
.quick.more {
  border-color: transparent;
  background: transparent;
  color: var(--muted);
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.more-item {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  min-width: 120px;
}
.more-item em {
  font-style: normal;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.more-filters {
  color: var(--muted);
}
.contract-filters {
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 24px;
}
.contract-filters .el-input {
  max-width: 250px;
}
.contract-filters .el-select {
  width: 118px;
}
.contract-sum {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  padding: 6px 24px;
  border-top: 1px solid #edf1ea;
  background: #f8faf6;
  color: #5d6a63;
  font-size: 13px;
  line-height: 20px;
}
.contract-sum strong {
  margin-left: 2px;
  font-size: 14px;
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.contract-sum .sum-action {
  margin-left: auto;
}
.contract-sum small {
  margin-left: 4px;
  font-size: 12px;
  color: #66716a;
}
.clickable-rows :deep(.el-table__row) {
  cursor: pointer;
}
/* 紧凑行：每份合同两行文字，一屏多看几份 */
.dense :deep(th.el-table__cell) {
  height: 38px;
  padding: 4px 0;
}
.dense :deep(th.el-table__cell .cell) {
  white-space: nowrap;
}
.dense :deep(td.el-table__cell) {
  height: 50px;
  padding: 5px 0;
}
.dense :deep(td.el-table__cell .cell) {
  line-height: 20px;
}
.dense :deep(.cell-secondary) {
  margin-top: 0;
  line-height: 18px;
}
.confirm-tag {
  display: inline-block;
  padding: 0 7px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
  vertical-align: 1px;
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
  color: #5d6a63;
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
}
.contract-pager {
  justify-content: flex-end;
  margin: 12px 24px 4px;
}
.cell-secondary {
  display: block;
}
</style>
