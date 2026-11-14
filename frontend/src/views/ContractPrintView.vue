<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api'
import type { Quote } from '../types'
import { day, money, unitMoney, percent, taxName, rmbUpper } from '../utils'

const quote = ref<Quote>(),
  error = ref(''),
  route = useRoute()
onMounted(async () => {
  try {
    quote.value = await api<Quote>(`/quotes/${route.params.id}/customer-copy`)
    document.title = `购销合同 ${contractNo.value}`
  } catch (e) {
    error.value = (e as Error).message
  }
})
const t = computed(() => quote.value?.template)
/** 合同编号：项目已登记合同号时使用，否则由报价编号生成。 */
const contractNo = computed(
  () => quote.value?.contract_no || (quote.value ? `HT-${quote.value.code.replace(/^BJ-/, '')}` : ''),
)
const terms = computed(() =>
  (t.value?.contract_terms || '')
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean),
)
const quoteTerms = computed(() =>
  (t.value?.terms || '')
    .split(/\r?\n/)
    .map((x) => x.trim().replace(/^\d+[.、．)]\s*/, ''))
    .filter(Boolean),
)
const print = () => window.print()
const blank = (v: string | null | undefined) => v || '＿＿＿＿＿＿＿＿＿＿'
</script>
<template>
  <div class="print-page">
    <div class="print-toolbar">
      <span>购销合同预览 · 打印前请核对双方信息，空白处可手填</span>
      <el-button type="primary" @click="print">打印 / 保存为 PDF</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" />
    <article v-if="quote" class="print-sheet contract-sheet">
      <h1 class="contract-title">购 销 合 同</h1>
      <p class="contract-no">合同编号：{{ contractNo }}　　签订日期：＿＿＿＿年＿＿月＿＿日</p>

      <section class="parties">
        <div>
          <h3>甲方（买方）</h3>
          <p>名称：{{ quote.customer_invoice_title || quote.customer_name }}</p>
          <p>纳税人识别号：{{ blank(quote.customer_tax_no) }}</p>
          <p>地址：{{ blank(quote.customer_address) }}</p>
          <p>
            联系人：{{ blank(quote.customer_contact) }}　电话：{{
              blank(quote.customer_phone || quote.customer_invoice_phone)
            }}
          </p>
          <p>开户行：{{ blank(quote.customer_bank_name) }}</p>
          <p>账号：{{ blank(quote.customer_bank_account) }}</p>
        </div>
        <div>
          <h3>乙方（卖方）</h3>
          <p>名称：{{ t?.company_name || '天商' }}</p>
          <p>纳税人识别号：{{ blank(t?.tax_no) }}</p>
          <p>地址：{{ blank(t?.address) }}</p>
          <p>联系人：{{ blank(t?.contact) }}　电话：{{ blank(t?.phone) }}</p>
          <p>开户行：{{ blank(t?.bank_name) }}</p>
          <p>账号：{{ blank(t?.bank_account) }}</p>
        </div>
      </section>

      <p class="contract-lead">
        甲乙双方本着平等互利的原则，经友好协商，就甲方向乙方采购
        <strong>{{ quote.project_name }}</strong>
        {{ quote.city ? `（${quote.city}）` : '' }}项目所需设备达成如下协议：
      </p>

      <h3 class="clause">第一条　产品名称、规格、数量及金额</h3>
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
        <tfoot>
          <tr>
            <td colspan="5">
              合同总金额（大写）：
              <strong>人民币{{ rmbUpper(quote.amount) }}</strong>
            </td>
            <td colspan="2">
              小写：
              <strong>{{ money(quote.amount) }}</strong>
            </td>
          </tr>
        </tfoot>
      </table>
      <p class="contract-tax">
        以上价格为{{ taxName[quote.tax_basis] }}价格{{
          quote.tax_rate == null ? '' : `，税率 ${percent(quote.tax_rate)}`
        }}。
        <template v-if="quote.note">补充说明：{{ quote.note }}</template>
      </p>
      <template v-if="quoteTerms.length">
        <h3 class="clause">第二条　报价约定</h3>
        <ol class="print-terms">
          <li v-for="(x, i) in quoteTerms" :key="i">{{ x }}</li>
        </ol>
      </template>

      <h3 class="clause">第{{ quoteTerms.length ? '三' : '二' }}条　其他约定</h3>
      <div class="contract-terms">
        <p v-for="(x, i) in terms" :key="i">{{ x }}</p>
        <p v-if="!terms.length" class="muted">（请在“系统管理 → 报价模板 → 合同条款”中维护）</p>
      </div>

      <div v-if="t?.bank_name || t?.bank_account" class="print-bank">
        <p>乙方收款户名：{{ t?.company_name }}</p>
        <p>开户银行：{{ blank(t?.bank_name) }}</p>
        <p>银行账号：{{ blank(t?.bank_account) }}</p>
        <p v-if="t?.tax_no">纳税人识别号：{{ t.tax_no }}</p>
      </div>

      <section class="signs">
        <div>
          <p>甲方（盖章）：</p>
          <p>授权代表（签字）：</p>
          <p>日期：</p>
        </div>
        <div>
          <p>乙方（盖章）：</p>
          <p>授权代表（签字）：</p>
          <p>日期：</p>
        </div>
      </section>
      <p class="print-footnote">
        依据报价单 {{ quote.code }}（V{{ quote.version }}）生成 · 报价有效期至 {{ day(quote.valid_until) }}
      </p>
    </article>
  </div>
</template>
<style scoped>
.contract-title {
  text-align: center;
  font-size: 26px;
  letter-spacing: 10px;
  font-weight: 600;
  margin: 0 0 10px;
}
.contract-no {
  text-align: center;
  font-size: 11px;
  color: #556650;
  margin-bottom: 26px;
}
.parties {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  font-size: 12px;
  line-height: 2;
  padding: 14px 0;
  border-top: 1px solid #cad5c1;
  border-bottom: 1px solid #cad5c1;
}
.parties h3 {
  font-size: 13px;
  margin: 0 0 4px;
}
.contract-lead,
.contract-tax {
  font-size: 12px;
  line-height: 2;
  margin: 16px 0 8px;
}
.clause {
  font-size: 13px;
  margin: 20px 0 10px;
}
.customer-quote-table tfoot td {
  padding: 12px 8px;
  font-size: 12px;
  border-bottom: 1px solid #cad5c1;
  text-align: left !important;
}
.contract-terms p {
  font-size: 12px;
  line-height: 2;
  margin: 0;
}
.signs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-top: 40px;
  font-size: 12px;
  line-height: 3;
  break-inside: avoid;
}
.print-terms {
  font-size: 12px;
  line-height: 2;
}
</style>
