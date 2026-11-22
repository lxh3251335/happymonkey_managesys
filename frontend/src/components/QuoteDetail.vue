<script setup lang="ts">
import { computed } from 'vue'
import type { Quote } from '../types'
import { displayRows, groupOf, rowClass, spanGroups } from '../bundles'
import { money, unitMoney, percent, day, taxName } from '../utils'
import StatusTag from './StatusTag.vue'
import ProjectFiles from './ProjectFiles.vue'
import { canSeeCost } from '../api'
const props = defineProps<{ quote: Quote }>()
const rows = computed(() =>
  displayRows(props.quote.items.map((i) => ({ ...i, quantity: Number(i.quantity), group: groupOf(i) }))),
)
const spanMethod = spanGroups(() => (seeCost ? 8 : 5))
const groupTotal = (xs: { amount: number | null }[]) => xs.reduce((s, x) => s + Number(x.amount || 0), 0)
/** 成本与毛利只对系统管理员、管理层、财务展示（后端对其他角色已去掉这些字段）。 */
const seeCost = canSeeCost()
const split = (n: number | null) => (n == null ? '—' : Number(n).toLocaleString('zh-CN', { maximumFractionDigits: 2 }))
</script>
<template>
  <div>
    <div class="detail-banner">
      <div>
        <span class="eyebrow">QUOTATION / V{{ quote.version }}</span>
        <h2>{{ quote.project_name }}</h2>
        <p>
          {{ quote.customer_name }}
          <span class="muted">/ {{ quote.code }}</span>
        </p>
      </div>
      <StatusTag v-if="quote.stage" :status="quote.stage" kind="stage" />
      <StatusTag v-else :status="quote.status" />
    </div>
    <div class="detail-meta">
      <span>业务线：{{ quote.brand }}</span>
      <span v-if="quote.city">城市：{{ quote.city }}</span>
      <span v-if="quote.project_type_name">项目类型：{{ quote.project_type_name }}</span>
      <span v-if="quote.supplier_name">供应商：{{ quote.supplier_name }}</span>
      <span v-if="quote.contract_no">合同号：{{ quote.contract_no }}</span>
      <span>
        税口径：{{ quote.tax_basis === 'UNKNOWN' ? '待核实' : taxName[quote.tax_basis]
        }}{{ quote.tax_rate == null ? '' : ` · ${percent(quote.tax_rate)}` }}
      </span>
      <span v-if="quote.valid_until">有效期至：{{ day(quote.valid_until) }}</span>
      <span v-if="quote.template_name">报价单版式：{{ quote.template_name }}</span>
      <span v-if="quote.due_date">应收截止日：{{ day(quote.due_date) }}</span>
    </div>
    <div v-if="seeCost && !quote.complete" class="inline-note warning">
      金额或成本缺失，整份报价的毛利和毛利率暂不计算。
    </div>
    <el-table :data="rows" row-key="key" :span-method="spanMethod" :row-class-name="rowClass">
      <el-table-column label="产品" min-width="160">
        <template #default="{ row }">
          <div v-if="row.kind === 'group'" class="group-head">
            <span class="group-name">组合 · {{ row.name }} × {{ row.sets }} 套</span>
            <span class="muted">{{ row.lines.length }} 种标准品 · 小计 {{ money(groupTotal(row.lines)) }}</span>
          </div>
          <template v-else>{{ row.line.name }}</template>
        </template>
      </el-table-column>
      <el-table-column label="规格" min-width="150">
        <template #default="{ row }">{{ row.kind === 'line' ? row.line.specification : '' }}</template>
      </el-table-column>
      <el-table-column label="数量" width="70" align="right">
        <template #default="{ row }">{{ row.kind === 'line' ? Number(row.line.quantity) : '' }}</template>
      </el-table-column>
      <el-table-column label="销售单价" width="125" align="right">
        <template #default="{ row }">{{ row.kind === 'line' ? unitMoney(row.line.unit_price) : '' }}</template>
      </el-table-column>
      <el-table-column label="销售金额" width="125" align="right">
        <template #default="{ row }">{{ row.kind === 'line' ? money(row.line.amount) : '' }}</template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="单台成本（材料 / 人工 / 财务）" min-width="210" align="right">
        <template #default="{ row }">
          <template v-if="row.kind === 'line'">
            {{ unitMoney(row.line.unit_cost) }}
            <small
              v-if="row.line.material_cost != null || row.line.labor_cost != null || row.line.finance_cost != null"
              class="cell-sub"
            >
              {{ split(row.line.material_cost) }} / {{ split(row.line.labor_cost) }} /
              {{ split(row.line.finance_cost) }}
            </small>
          </template>
        </template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="成本金额" width="115" align="right">
        <template #default="{ row }">{{ row.kind === 'line' ? money(row.line.cost) : '' }}</template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="毛利率" width="88" align="right">
        <template #default="{ row }">
          <template v-if="row.kind === 'line'">
            {{
              row.line.amount && row.line.cost != null
                ? percent((row.line.amount - row.line.cost) / row.line.amount)
                : '—'
            }}
          </template>
        </template>
      </el-table-column>
    </el-table>
    <div class="quote-summary">
      <div>
        报价合计
        <strong>{{ money(quote.amount) }}</strong>
      </div>
      <div v-if="seeCost">
        成本合计
        <strong>{{ money(quote.cost) }}</strong>
      </div>
      <div v-if="seeCost">
        毛利
        <strong>{{ money(quote.profit) }}</strong>
      </div>
      <div v-if="seeCost">
        毛利率
        <strong>{{ percent(quote.gross_margin) }}</strong>
      </div>
      <div v-if="seeCost">
        成本利润率
        <strong>{{ percent(quote.cost_margin) }}</strong>
      </div>
    </div>
    <p v-if="seeCost" class="muted">
      毛利率 = 毛利 ÷ 报价；成本利润率 = 毛利 ÷
      成本。未包含尚未登记的运输、安装及其他费用。税口径待核实时，仅作历史金额测算。
    </p>
    <p v-if="quote.note" class="detail-note">{{ quote.note }}</p>
    <ProjectFiles :project-id="quote.project_id" />
  </div>
</template>
<style scoped>
.cell-sub {
  display: block;
  color: #65726a;
  font-size: 12px;
}
</style>
