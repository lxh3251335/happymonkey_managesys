<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import type { Quote } from '../types'
import { day, money, unitMoney, percent, taxName } from '../utils'
import { displayRows, groupOf } from '../bundles'
/** 对客报价单按组合分组：组合占一行（名称、套数、小计），下面列里面的标准品；序号只给组合和单品。 */
const rows = computed(() => {
  if (!quote.value) return []
  let seq = 0
  return displayRows(quote.value.items.map((i) => ({ ...i, quantity: Number(i.quantity), group: groupOf(i) }))).map(
    (r) => ({ ...r, seq: r.kind === 'group' || !r.line.group ? ++seq : null }),
  )
})
const groupTotal = (xs: { amount: number | null }[]) => xs.reduce((s, x) => s + Number(x.amount || 0), 0)
const quote = ref<Quote>(),
  error = ref(''),
  route = useRoute()
const print = () => window.print()
const terms = (t: string | null | undefined) =>
  (t || '')
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean)
onMounted(async () => {
  try {
    quote.value = await api<Quote>(`/quotes/${route.params.id}/customer-copy`)
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>
<template>
  <div class="print-page">
    <div class="print-toolbar">
      <span>客户报价单预览</span>
      <el-button type="primary" @click="print">打印 / 保存为 PDF</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" />
    <article v-if="quote" class="print-sheet">
      <header>
        <div class="print-company">
          <strong>{{ quote.template?.company_name || '天商' }}</strong>
          <small v-if="quote.template?.address">{{ quote.template.address }}</small>
        </div>
        <span>{{ quote.template?.title || '客户报价单' }}</span>
      </header>
      <div class="print-title">
        <h1>{{ quote.project_name }}</h1>
        <p>报价编号：{{ quote.code }}　版本：V{{ quote.version }}</p>
      </div>
      <div class="print-meta">
        <p>客户：{{ quote.customer_name }}</p>
        <p>报价有效期：{{ day(quote.valid_until) }}</p>
        <p v-if="quote.template?.contact || quote.template?.phone">
          联系人：{{ [quote.template?.contact, quote.template?.phone].filter(Boolean).join('　') }}
        </p>
        <p v-if="quote.template?.email">邮箱：{{ quote.template.email }}</p>
        <p>
          金额口径：{{ taxName[quote.tax_basis] }}
          {{ quote.tax_rate == null ? '' : `（税率 ${percent(quote.tax_rate)}）` }}
        </p>
      </div>
      <table class="customer-quote-table">
        <thead>
          <tr>
            <th>序号</th>
            <th>产品名称</th>
            <th>规格</th>
            <th>单位</th>
            <th>数量</th>
            <th>单价（元）</th>
            <th>金额（元）</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="r in rows" :key="r.key">
            <tr v-if="r.kind === 'group'" class="print-group">
              <td>{{ r.seq }}</td>
              <td colspan="3">{{ r.name }}（组合，含 {{ r.lines.length }} 种产品）</td>
              <td>{{ r.sets }} 套</td>
              <td></td>
              <td>{{ money(groupTotal(r.lines)) }}</td>
            </tr>
            <tr v-else :class="{ 'print-in-group': r.line.group }">
              <td>{{ r.seq ?? '' }}</td>
              <td>{{ r.line.name }}</td>
              <td>{{ r.line.specification }}</td>
              <td>{{ r.line.unit }}</td>
              <td>{{ Number(r.line.quantity) }}</td>
              <td>{{ unitMoney(r.line.unit_price) }}</td>
              <td>{{ money(r.line.amount) }}</td>
            </tr>
          </template>
        </tbody>
      </table>
      <div class="print-total">
        报价合计
        <strong>{{ money(quote.amount) }}</strong>
      </div>
      <div v-if="quote.note" class="print-notes">
        <strong>报价说明</strong>
        <p>{{ quote.note }}</p>
      </div>
      <div v-if="terms(quote.template?.terms).length" class="print-notes">
        <strong>报价条款</strong>
        <ol class="print-terms">
          <li v-for="(t, i) in terms(quote.template?.terms)" :key="i">{{ t.replace(/^\d+[.、．)]\s*/, '') }}</li>
        </ol>
      </div>
      <div
        v-if="quote.template?.bank_name || quote.template?.bank_account || quote.template?.tax_no"
        class="print-bank"
      >
        <p v-if="quote.template?.company_name">户名：{{ quote.template.company_name }}</p>
        <p v-if="quote.template?.bank_name">开户银行：{{ quote.template.bank_name }}</p>
        <p v-if="quote.template?.bank_account">银行账号：{{ quote.template.bank_account }}</p>
        <p v-if="quote.template?.tax_no">纳税人识别号：{{ quote.template.tax_no }}</p>
      </div>
      <footer>
        <span>供方确认（盖章）：________________</span>
        <span>客户确认（盖章）：________________</span>
      </footer>
      <p v-if="quote.template?.footer" class="print-footnote">{{ quote.template.footer }}</p>
    </article>
  </div>
</template>
