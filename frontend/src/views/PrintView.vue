<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import type { Quote } from '../types'
import { day, money, unitMoney, percent, taxName } from '../utils'
const quote = ref<Quote>(),
  error = ref(''),
  route = useRoute()
const print = () => window.print()
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
        <strong>天商</strong>
        <span>客户报价单</span>
      </header>
      <div class="print-title">
        <h1>{{ quote.project_name }}</h1>
        <p>报价编号：{{ quote.code }}　版本：V{{ quote.version }}</p>
      </div>
      <div class="print-meta">
        <p>客户：{{ quote.customer_name }}</p>
        <p>报价有效期：{{ day(quote.valid_until) }}</p>
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
          <tr v-for="(item, i) in quote.items" :key="i">
            <td>{{ i + 1 }}</td>
            <td>{{ item.name }}</td>
            <td>{{ item.specification }}</td>
            <td>{{ item.unit }}</td>
            <td>{{ item.quantity }}</td>
            <td>{{ unitMoney(item.unit_price) }}</td>
            <td>{{ money(item.amount) }}</td>
          </tr>
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
      <footer>
        <span>供方确认：________________</span>
        <span>客户确认：________________</span>
      </footer>
    </article>
  </div>
</template>
