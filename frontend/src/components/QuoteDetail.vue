<script setup lang="ts">
import type { Quote } from '../types'
import { money, unitMoney, percent, day, taxName } from '../utils'
import StatusTag from './StatusTag.vue'
import { canSeeCost } from '../api'
defineProps<{ quote: Quote }>()
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
    <el-table :data="quote.items">
      <el-table-column label="产品" prop="name" min-width="160" />
      <el-table-column label="规格" prop="specification" min-width="150" />
      <el-table-column label="数量" prop="quantity" width="70" align="right" />
      <el-table-column label="销售单价" width="125" align="right">
        <template #default="{ row }">{{ unitMoney(row.unit_price) }}</template>
      </el-table-column>
      <el-table-column label="销售金额" width="125" align="right">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="单台成本（材料 / 人工 / 财务）" min-width="210" align="right">
        <template #default="{ row }">
          {{ unitMoney(row.unit_cost) }}
          <small
            v-if="row.material_cost != null || row.labor_cost != null || row.finance_cost != null"
            class="cell-sub"
          >
            {{ split(row.material_cost) }} / {{ split(row.labor_cost) }} / {{ split(row.finance_cost) }}
          </small>
        </template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="成本金额" width="115" align="right">
        <template #default="{ row }">{{ money(row.cost) }}</template>
      </el-table-column>
      <el-table-column v-if="seeCost" label="毛利率" width="88" align="right">
        <template #default="{ row }">
          {{ row.amount && row.cost != null ? percent((row.amount - row.cost) / row.amount) : '—' }}
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
  </div>
</template>
<style scoped>
.cell-sub {
  display: block;
  color: #65726a;
  font-size: 12px;
}
</style>
