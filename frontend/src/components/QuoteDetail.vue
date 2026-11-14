<script setup lang="ts">
import type { Quote } from '../types'
import { money, unitMoney, percent, day, taxName } from '../utils'
import StatusTag from './StatusTag.vue'
defineProps<{ quote: Quote }>()
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
      <StatusTag :status="quote.status" />
    </div>
    <div class="detail-meta">
      <span>品牌：{{ quote.brand }}</span>
      <span>
        税口径：{{ taxName[quote.tax_basis] }}{{ quote.tax_rate == null ? '' : ` · ${percent(quote.tax_rate)}` }}
      </span>
      <span>有效期：{{ day(quote.valid_until) }}</span>
      <span v-if="quote.template_name">报价模板：{{ quote.template_name }}</span>
      <span>应收截止：{{ day(quote.due_date) }}</span>
    </div>
    <div v-if="!quote.complete" class="inline-note warning">金额或成本缺失，整份报价的利润和利润率暂不计算。</div>
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
      <el-table-column label="成本金额" width="125" align="right">
        <template #default="{ row }">{{ money(row.cost) }}</template>
      </el-table-column>
      <el-table-column label="数据来源" min-width="200" prop="source" show-overflow-tooltip />
    </el-table>
    <div class="quote-summary">
      <div>
        报价合计
        <strong>{{ money(quote.amount) }}</strong>
      </div>
      <div>
        预计利润
        <strong>{{ money(quote.profit) }}</strong>
      </div>
      <div>
        销售毛利率
        <strong>{{ percent(quote.gross_margin) }}</strong>
      </div>
      <div>
        成本利润率
        <strong>{{ percent(quote.cost_margin) }}</strong>
      </div>
    </div>
    <p class="muted">
      销售毛利率 = 利润 ÷ 报价；成本利润率 = 利润 ÷
      成本。未包含尚未登记的运输、安装及其他费用。税口径待核实时，仅作历史金额测算。
    </p>
    <p v-if="quote.note" class="detail-note">{{ quote.note }}</p>
  </div>
</template>
