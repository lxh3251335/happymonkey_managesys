<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, ArrowRight, Document, Wallet, TrendCharts, Coin, Box } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import GroupedBarChart from '../components/GroupedBarChart.vue'
import ContractTable from '../components/ContractTable.vue'
import { api, canWrite } from '../api'
import type { BizRow, BrandLines, Contract, Dashboard, Quote } from '../types'
import { money, percent, wan, axisWan } from '../utils'

/** 两个系列颜色已通过色觉差异校验（合同额 / 到账额）。 */
const CONTRACT = '#14805e',
  RECEIVED = '#c27c0e'
/** 产品线身份色（已校验色觉差异）：电控柜蓝、物联网电箱橙。 */
const CABINET = '电控柜',
  IOT = '物联网电箱'
const LINE_COLOR: Record<string, string> = { [CABINET]: '#2a78d6', [IOT]: '#eb6834' }

const data = ref<Dashboard>(),
  contracts = ref<Contract[]>([]),
  error = ref(''),
  loading = ref(false),
  router = useRouter(),
  route = useRoute()
const trendView = ref<'amount' | 'line' | 'margin' | 'table'>('amount')
const dim = ref<'customer' | 'brand' | 'city' | 'line'>('customer')
const showAllDims = ref(false)
const DIM_LIMIT = 10
/** 业务（品牌）筛选，空字符串为全部业务；写入地址栏便于分享。 */
const brand = ref(String(route.query.brand || '')),
  group = ref(brand.value ? '' : String(route.query.group || ''))
/** 业务选择器的值：'' 全部；'g:集团' 整个集团；'l:业务线' 单条业务线。 */
const scope = computed({
  get: () => (brand.value ? `l:${brand.value}` : group.value ? `g:${group.value}` : ''),
  set: (v: string) => {
    brand.value = v.startsWith('l:') ? v.slice(2) : ''
    group.value = v.startsWith('g:') ? v.slice(2) : ''
  },
})

async function load() {
  loading.value = true
  try {
    const q = brand.value
      ? `?brand=${encodeURIComponent(brand.value)}`
      : group.value
        ? `?group=${encodeURIComponent(group.value)}`
        : ''
    ;[data.value, contracts.value] = await Promise.all([
      api<Dashboard>(`/dashboard${q}`),
      api<Contract[]>('/contracts'),
    ])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([brand, group], ([b, g]) => {
  if (b && dim.value === 'brand') dim.value = 'customer'
  router.replace({ query: { ...route.query, brand: b || undefined, group: g || undefined } })
  load()
})
const brandRowClass = ({ row }: { row: BrandLines }) => (row.brand === brand.value ? 'current-brand' : '')
/** 有合同数据的业务线，按客户集团分组。 */
const scopeGroups = computed(() => {
  const out: { group: string; lines: string[] }[] = []
  for (const r of data.value?.brand_lines ?? []) {
    let g = out.find((x) => x.group === r.group)
    if (!g) out.push((g = { group: r.group, lines: [] }))
    g.lines.push(r.brand)
  }
  return out
})
const scopedContracts = computed(() =>
  brand.value
    ? contracts.value.filter((c) => c.brands.includes(brand.value))
    : group.value
      ? contracts.value.filter((c) => c.brands.some((b) => groupOf.value[b] === group.value))
      : contracts.value,
)
const groupOf = computed(() => Object.fromEntries((data.value?.brand_lines ?? []).map((r) => [r.brand, r.group])))
const scopeName = computed(() => brand.value || (group.value ? `${group.value}（全部业务线）` : '全部业务'))
const mainLines = computed(() => (data.value?.line_summary ?? []).filter((l) => l.name in LINE_COLOR))
const minorLines = computed(() => (data.value?.line_summary ?? []).filter((l) => !(l.name in LINE_COLOR)))
const minorNote = computed(
  () =>
    `另有${minorLines.value
      .map((l) => `${l.name} ${money(l.contract_amount)}（毛利率 ${percent(l.gross_margin)}）`)
      .join('、')}，未计入以上两条产品线。毛利率只统计报价与成本都完整的明细行。`,
)
const lineOf = (row: { lines: BizRow[] }, name: string) => row.lines.find((l) => l.name === name)
const lineAmountSeries = computed(() =>
  [CABINET, IOT].map((n) => ({
    name: n,
    color: LINE_COLOR[n],
    values: months.value.map((m) => m.lines[n]?.contract_amount ?? 0),
  })),
)

const t = computed(() => data.value!.totals)
const months = computed(() => data.value?.monthly ?? [])
const multiYear = computed(() => new Set(months.value.map((m) => m.name.slice(0, 4))).size > 1)
const monthCats = computed(() =>
  months.value.map((m) =>
    /^\d{4}-\d{2}$/.test(m.name)
      ? multiYear.value
        ? `${m.name.slice(2, 4)}.${m.name.slice(5)}`
        : `${Number(m.name.slice(5))}月`
      : m.name,
  ),
)
const yearSpan = computed(() => {
  const ys = [...new Set(months.value.map((m) => m.name.slice(0, 4)).filter((y) => /^\d{4}$/.test(y)))]
  return ys.length ? (ys.length > 1 ? `${ys[0]}–${ys[ys.length - 1]} 年` : `${ys[0]} 年`) : ''
})
const amountSeries = computed(() => [
  { name: '合同额', color: CONTRACT, values: months.value.map((m) => m.contract_amount) },
  { name: '到账额', color: RECEIVED, values: months.value.map((m) => m.received) },
])
const marginSeries = computed(() =>
  [CABINET, IOT].map((n) => ({
    name: `${n}毛利率`,
    color: LINE_COLOR[n],
    values: months.value.map((m) => {
      const g = m.lines[n]?.gross_margin
      return g == null ? null : g * 100
    }),
  })),
)

const allDimRows = computed<BizRow[]>(() =>
  !data.value
    ? []
    : dim.value === 'customer'
      ? data.value.by_customer
      : dim.value === 'brand'
        ? data.value.by_brand
        : dim.value === 'city'
          ? data.value.by_city
          : data.value.by_line,
)
const dimRows = computed(() => (showAllDims.value ? allDimRows.value : allDimRows.value.slice(0, DIM_LIMIT)))
const dimName = computed(() => ({ customer: '客户', brand: '业务线', city: '城市', line: '产品线' })[dim.value])
watch(dim, () => (showAllDims.value = false))
const dimMax = computed(() => Math.max(1, ...dimRows.value.map((r) => r.contract_amount)))
/** 业务线名称已在业务字典中维护，直接显示。 */
function brandName(n: string) {
  return n
}
const agingMax = computed(() => Math.max(1, ...(data.value?.aging.map((a) => a.contract_amount) ?? [1])))
const agingColor = ['#9fb3a6', '#e0a458', '#d9773c', '#c4512e', '#9e2f22']
const openQuote = (q: Quote) => router.push(`/quotes?detail=${q.id}`)
</script>
<template>
  <div>
    <PageHeader title="经营总览">
      <template v-if="data" #description>
        <strong>{{ t.contracts }}</strong>
        份合同 ·
        <strong>{{ t.projects }}</strong>
        个门店 ·
        <strong>{{ data.concentration.customer_count }}</strong>
        家客户 · 合同额按下单月、到账按收款日
      </template>
      <div class="biz-switch" role="group" aria-label="按业务筛选">
        <el-select v-model="scope" size="small" class="scope-select" :disabled="loading || !data">
          <el-option label="全部业务" value="" />
          <el-option-group v-for="g in scopeGroups" :key="g.group" :label="g.group">
            <el-option :label="`${g.group}（全部）`" :value="`g:${g.group}`" />
            <el-option v-for="l in g.lines" :key="l" :label="l" :value="`l:${l}`" />
          </el-option-group>
        </el-select>
      </div>
      <el-button v-if="canWrite()" size="small" type="primary" @click="router.push('/quotes?new=1')">
        <el-icon><Plus /></el-icon>
        快速报价
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <template v-if="data">
      <section class="metric-grid biz-metrics">
        <article class="metric-card">
          <div class="metric-label">
            合同额
            <el-icon><Document /></el-icon>
          </div>
          <div class="metric-value">{{ wan(t.contract_amount) }}</div>
          <div class="metric-foot">
            <span class="metric-badge">本月 {{ wan(t.current_month_contract) }}</span>
            已确认 {{ wan(t.confirmed_amount) }}
          </div>
        </article>
        <article class="metric-card">
          <div class="metric-label">
            到账额
            <el-icon><Wallet /></el-icon>
          </div>
          <div class="metric-value">{{ wan(t.received) }}</div>
          <div class="metric-foot">
            <span class="metric-badge">回款率 {{ percent(t.collection_rate) }}</span>
            本月 {{ wan(t.current_month_received) }}
          </div>
        </article>
        <article class="metric-card accent">
          <div class="metric-label">
            应收余额
            <el-icon><Coin /></el-icon>
          </div>
          <div class="metric-value">{{ wan(t.receivable) }}</div>
          <div class="metric-foot">
            <span v-if="t.overdue_amount > 0" class="danger-text">
              逾期 {{ wan(t.overdue_amount) }} · {{ t.overdue_projects }} 店
            </span>
            <span v-else>无逾期</span>
          </div>
        </article>
        <article class="metric-card">
          <div class="metric-label">
            在手订单
            <el-icon><Box /></el-icon>
          </div>
          <div class="metric-value">{{ wan(t.backlog_amount) }}</div>
          <div class="metric-foot">{{ t.backlog_projects }} 个门店未发货</div>
        </article>
        <article class="metric-card">
          <div class="metric-label">
            综合毛利率
            <el-icon><TrendCharts /></el-icon>
          </div>
          <div class="metric-value">{{ percent(t.gross_margin) }}</div>
          <div class="metric-foot">毛利 {{ wan(t.profit) }} · 成本覆盖 {{ percent(t.margin_coverage) }}</div>
        </article>
      </section>

      <div class="dash-row two">
        <section class="panel biz-panel">
          <div class="panel-heading">
            <div>
              <h2>产品线经营 · {{ scopeName }}</h2>
              <p>电控柜与物联网电箱分别统计合同额、毛利与回款；同一门店订单按明细行拆分</p>
            </div>
          </div>
          <div class="line-cards panel-body">
            <article v-for="l in mainLines" :key="l.name" class="line-card">
              <header>
                <i :style="{ background: LINE_COLOR[l.name] }"></i>
                <strong>{{ l.name }}</strong>
                <span class="muted">占合同额 {{ percent(l.share) }}</span>
              </header>
              <div class="line-figures">
                <div>
                  <span>合同额</span>
                  <strong>{{ wan(l.contract_amount) }}</strong>
                </div>
                <div>
                  <span>毛利率</span>
                  <strong :class="l.gross_margin != null && l.gross_margin < 0 ? 'danger-text' : ''">
                    {{ percent(l.gross_margin) }}
                  </strong>
                </div>
                <div>
                  <span>毛利</span>
                  <strong>{{ wan(l.profit) }}</strong>
                </div>
              </div>
              <div class="share-track" :title="`占合同额 ${percent(l.share)}`">
                <i :style="{ width: `${Math.min(100, (l.share || 0) * 100)}%`, background: LINE_COLOR[l.name] }"></i>
              </div>
              <footer>
                <span>到账 {{ wan(l.received) }} · 回款率 {{ percent(l.collection_rate) }}</span>
                <span>
                  {{ Number(l.quantity) }} 台 · {{ l.projects }} 店 · 成本覆盖 {{ percent(l.margin_coverage) }}
                </span>
              </footer>
            </article>
          </div>
          <p v-if="minorLines.length" class="panel-bottom-note">
            {{ minorNote }}
          </p>
        </section>

        <section v-if="data.brand_lines.length > 1" class="panel biz-panel">
          <div class="panel-heading">
            <div>
              <h2>业务 × 产品线</h2>
              <p>各品牌的电控柜与物联网电箱合同额、毛利率对比（全部业务，不随上方筛选变化）</p>
            </div>
          </div>
          <el-table class="brand-matrix" :data="data.brand_lines" size="small" :row-class-name="brandRowClass">
            <el-table-column label="业务线" min-width="110">
              <template #default="{ row }">
                <el-button link @click="scope = row.brand === brand ? '' : `l:${row.brand}`">
                  {{ brandName(row.brand) }}
                </el-button>
                <small class="matrix-group">{{ row.group }}</small>
              </template>
            </el-table-column>
            <el-table-column v-for="n in [CABINET, IOT]" :key="n" align="center">
              <template #header>
                <span class="line-head">
                  <i :style="{ background: LINE_COLOR[n] }"></i>
                  {{ n }}
                </span>
              </template>
              <el-table-column label="合同额" align="right" min-width="84">
                <template #default="{ row }">{{ wan(lineOf(row, n)?.contract_amount ?? 0) }}</template>
              </el-table-column>
              <el-table-column label="毛利率" align="right" min-width="70">
                <template #default="{ row }">{{ percent(lineOf(row, n)?.gross_margin) }}</template>
              </el-table-column>
            </el-table-column>
            <el-table-column label="合计" align="center">
              <el-table-column label="合同额" align="right" min-width="84">
                <template #default="{ row }">{{ wan(row.contract_amount) }}</template>
              </el-table-column>
              <el-table-column label="综合毛利率" align="right" min-width="80">
                <template #default="{ row }">{{ percent(row.gross_margin) }}</template>
              </el-table-column>
            </el-table-column>
          </el-table>
          <p class="panel-bottom-note">点击业务名称可切换整页为该业务的经营分析。</p>
        </section>
      </div>

      <div v-if="t.pending_amount > 0" class="data-note">
        <span class="note-dot"></span>
        <strong>{{ wan(t.pending_amount) }} 合同额尚未确认应收</strong>
        <span>历史记录有合同号的计入合同额；确认合同应收后才能登记到账并计算账龄。</span>
        <router-link to="/cash?tab=contracts">
          去确认合同
          <el-icon><ArrowRight /></el-icon>
        </router-link>
      </div>

      <section class="panel biz-panel">
        <div class="panel-heading">
          <div>
            <h2>月度经营趋势</h2>
            <p>
              {{ yearSpan }} · {{ scopeName }} ·
              {{
                trendView === 'line'
                  ? '电控柜与物联网电箱每月合同额'
                  : trendView === 'margin'
                    ? '两条产品线每月毛利率'
                    : '每月合同额与到账额'
              }}
            </p>
          </div>
          <el-radio-group v-model="trendView" size="small">
            <el-radio-button value="amount">金额</el-radio-button>
            <el-radio-button value="line">产品线</el-radio-button>
            <el-radio-button value="margin">毛利率</el-radio-button>
            <el-radio-button value="table">表格</el-radio-button>
          </el-radio-group>
        </div>
        <div class="panel-body">
          <GroupedBarChart
            v-if="trendView === 'amount'"
            :categories="monthCats"
            :series="amountSeries"
            :format="(n) => money(n)"
            :axis-format="axisWan"
            label="按月合同额与到账额柱状图"
          >
            <template #tip="{ index }">
              <div class="tip-extra">
                {{ months[index].projects }} 个门店 · 毛利率 {{ percent(months[index].gross_margin) }}
              </div>
            </template>
          </GroupedBarChart>
          <GroupedBarChart
            v-else-if="trendView === 'line'"
            :categories="monthCats"
            :series="lineAmountSeries"
            :format="(n) => money(n)"
            :axis-format="axisWan"
            label="按月电控柜与物联网电箱合同额柱状图"
          >
            <template #tip="{ index }">
              <div class="tip-extra">
                电控柜毛利率 {{ percent(months[index].lines[CABINET]?.gross_margin) }} · 物联网电箱毛利率
                {{ percent(months[index].lines[IOT]?.gross_margin) }}
              </div>
            </template>
          </GroupedBarChart>
          <GroupedBarChart
            v-else-if="trendView === 'margin'"
            :categories="monthCats"
            :series="marginSeries"
            :format="(n) => (n == null ? '无完整成本' : `${n.toFixed(1)}%`)"
            :axis-format="(n) => `${n}%`"
            label="按月电控柜与物联网电箱毛利率柱状图"
          >
            <template #tip="{ index }">
              <div class="tip-extra">
                综合毛利率 {{ percent(months[index].gross_margin) }} · 毛利 {{ money(months[index].profit) }}
              </div>
            </template>
          </GroupedBarChart>
          <el-table v-else :data="months" size="small">
            <el-table-column label="月份" prop="name" width="100" />
            <el-table-column label="合同额" align="right">
              <template #default="{ row }">{{ money(row.contract_amount) }}</template>
            </el-table-column>
            <el-table-column label="到账额" align="right">
              <template #default="{ row }">{{ money(row.received) }}</template>
            </el-table-column>
            <el-table-column label="毛利" align="right">
              <template #default="{ row }">{{ money(row.profit) }}</template>
            </el-table-column>
            <el-table-column label="毛利率" align="right" width="100">
              <template #default="{ row }">{{ percent(row.gross_margin) }}</template>
            </el-table-column>
            <el-table-column v-for="n in [CABINET, IOT]" :key="n" :label="n" align="center">
              <el-table-column label="合同额" align="right" min-width="84">
                <template #default="{ row }">{{ money(row.lines[n]?.contract_amount ?? 0) }}</template>
              </el-table-column>
              <el-table-column label="毛利率" align="right" width="90">
                <template #default="{ row }">{{ percent(row.lines[n]?.gross_margin) }}</template>
              </el-table-column>
            </el-table-column>
            <el-table-column label="门店" align="right" width="80" prop="projects" />
            <el-table-column label="合同" align="right" width="80" prop="contracts" />
          </el-table>
        </div>
      </section>

      <div class="dash-row split">
        <section class="panel biz-panel">
          <div class="panel-heading">
            <div>
              <h2>经营分布</h2>
              <p>
                合同额、到账额、回款率与毛利，按{{
                  dim === 'customer' ? '客户' : dim === 'brand' ? '品牌' : '产品线'
                }}汇总
              </p>
            </div>
            <el-radio-group v-model="dim" size="small">
              <el-radio-button value="customer">客户</el-radio-button>
              <el-radio-button v-if="!brand" value="brand">业务线</el-radio-button>
              <el-radio-button value="city">城市</el-radio-button>
              <el-radio-button value="line">产品线</el-radio-button>
            </el-radio-group>
          </div>
          <div class="panel-body">
            <div class="dist-legend">
              <span>
                <i :style="{ background: CONTRACT }"></i>
                合同额
              </span>
              <span>
                <i :style="{ background: RECEIVED }"></i>
                到账额
              </span>
            </div>
            <div class="dist-table">
              <div class="dist-row dist-head">
                <span>{{ dimName }}</span>
                <span></span>
                <span>合同额</span>
                <span>到账额</span>
                <span>回款率</span>
                <span>毛利率</span>
                <span>占比</span>
                <span>{{ dim === 'line' ? '数量' : dim === 'brand' ? '单店均额' : '门店' }}</span>
              </div>
              <div v-for="r in dimRows" :key="r.name" class="dist-row">
                <strong>{{ dim === 'brand' ? brandName(r.name) : r.name }}</strong>
                <div class="dist-bars" :title="`合同额 ${money(r.contract_amount)} · 到账额 ${money(r.received)}`">
                  <i
                    :style="{ width: `${Math.max(0.5, (r.contract_amount / dimMax) * 100)}%`, background: CONTRACT }"
                  ></i>
                  <i
                    :style="{
                      width: `${r.received > 0 ? Math.max(0.5, (r.received / dimMax) * 100) : 0}%`,
                      background: RECEIVED,
                    }"
                  ></i>
                </div>
                <span class="num">{{ wan(r.contract_amount) }}</span>
                <span class="num">{{ wan(r.received) }}</span>
                <span class="num">{{ percent(r.collection_rate) }}</span>
                <span :class="['num', r.gross_margin != null && r.gross_margin < 0 ? 'danger-text' : '']">
                  {{ percent(r.gross_margin) }}
                </span>
                <span class="num">{{ percent(r.share) }}</span>
                <span class="num">
                  {{
                    dim === 'line'
                      ? `${Number(r.quantity)} 台`
                      : dim === 'brand'
                        ? wan(r.avg_per_project)
                        : `${r.projects} 店`
                  }}
                </span>
              </div>
            </div>
            <el-button
              v-if="allDimRows.length > DIM_LIMIT"
              link
              type="primary"
              size="small"
              class="dim-more"
              @click="showAllDims = !showAllDims"
            >
              {{ showAllDims ? '收起' : `展开其余 ${allDimRows.length - DIM_LIMIT} 个${dimName}` }}
            </el-button>
          </div>
          <p v-if="dim === 'line'" class="panel-bottom-note">产品线按品名归类；到账额按门店订单内明细金额比例分摊。</p>
        </section>
        <div class="dash-col">
          <section class="panel">
            <div class="panel-heading">
              <div>
                <h2>应收账龄</h2>
                <p>已确认应收的未收余额，按应收截止日计算</p>
              </div>
              <strong class="panel-figure">{{ wan(t.receivable) }}</strong>
            </div>
            <div v-if="t.receivable > 0" class="aging-list panel-body">
              <div v-for="(a, i) in data.aging" :key="a.name" class="aging-row">
                <span>{{ a.name }}</span>
                <div class="aging-track">
                  <i
                    :style="{
                      width: `${a.contract_amount > 0 ? Math.max(1, (a.contract_amount / agingMax) * 100) : 0}%`,
                      background: agingColor[i],
                    }"
                  ></i>
                </div>
                <span class="num">{{ money(a.contract_amount) }}</span>
                <small>{{ a.projects }} 店</small>
              </div>
            </div>
            <el-empty v-else :image-size="60" description="暂无已确认应收。确认合同并登记回款后显示账龄。" />
          </section>
          <section class="panel">
            <div class="panel-heading">
              <div>
                <h2>在手订单与客户集中度</h2>
                <p>已签合同未发货金额；前几大客户合同额占比</p>
              </div>
            </div>
            <div class="panel-body">
              <div class="backlog-list">
                <div v-for="b in data.backlog" :key="b.name" class="backlog-row">
                  <span>{{ b.name }}</span>
                  <strong class="num">{{ money(b.contract_amount) }}</strong>
                  <small>{{ b.projects }} 店 · {{ b.contracts }} 份合同</small>
                </div>
                <div v-if="!data.backlog.length" class="muted">全部合同均已发货</div>
              </div>
              <div class="concentration">
                <div>
                  <span>第一大客户</span>
                  <strong>{{ data.concentration.top_customer ?? '—' }}</strong>
                  <em>{{ percent(data.concentration.top1_share) }}</em>
                </div>
                <div>
                  <span>前三大客户</span>
                  <strong>合计占比</strong>
                  <em>{{ percent(data.concentration.top3_share) }}</em>
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>

      <section class="panel biz-panel">
        <div class="panel-heading">
          <div>
            <h2>合同明细</h2>
            <p>每个合同号一行，展开查看门店。确认应收与登记回款在“回款与应收”中办理。</p>
          </div>
          <el-button link type="primary" @click="router.push('/cash?tab=contracts')">
            去办理
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
        <ContractTable :contracts="scopedContracts" />
      </section>

      <section class="panel recent-panel">
        <div class="panel-heading">
          <div>
            <h2>最近报价</h2>
            <p>查看项目报价与当前经营测算</p>
          </div>
          <el-button link type="primary" @click="router.push('/quotes')">
            全部报价
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
        <el-table :data="data.recent" @row-click="openQuote" class="clickable-table">
          <el-table-column label="项目 / 门店" prop="project_name" min-width="260" show-overflow-tooltip />
          <el-table-column label="客户" prop="customer_name" min-width="110" />
          <el-table-column label="报价金额" align="right" min-width="140">
            <template #default="{ row }">{{ money(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="预计利润" align="right" min-width="130">
            <template #default="{ row }">
              <span :class="row.profit < 0 ? 'danger-text' : 'profit-text'">{{ money(row.profit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><StatusTag :status="row.status" /></template>
          </el-table-column>
        </el-table>
      </section>
    </template>
    <div v-else-if="!error" class="loading-block">正在读取经营数据…</div>
  </div>
</template>
<style scoped>
.matrix-group {
  display: block;
  color: #8a958e;
  font-size: 11px;
  line-height: 1.2;
}
.scope-select {
  width: 200px;
}
.biz-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.line-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.line-card {
  border: 1px solid #e4eae0;
  border-radius: 8px;
  padding: 12px 14px;
}
.line-card header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.line-card header i,
.line-head i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}
.line-card header .muted {
  margin-left: auto;
  font-size: 12px;
}
.line-figures {
  display: grid;
  grid-template-columns: repeat(3, auto);
  justify-content: space-between;
  gap: 8px;
  margin: 10px 0 8px;
}
.line-figures span {
  display: block;
  font-size: 11px;
  color: #6b7a72;
}
.line-figures strong {
  font-size: clamp(16px, 1.3vw, 20px);
  white-space: nowrap;
  font-weight: 500;
  color: #253731;
  font-variant-numeric: tabular-nums;
}
.share-track {
  height: 6px;
  border-radius: 3px;
  background: #eef1ec;
  overflow: hidden;
}
.share-track i {
  display: block;
  height: 100%;
  border-radius: 3px;
}
.line-card footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 4px 12px;
  margin-top: 10px;
  font-size: 11px;
  color: #6b7a72;
  font-variant-numeric: tabular-nums;
}
.line-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
:deep(.current-brand td) {
  background: #f1f6ee !important;
}
.biz-metrics {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}
.biz-metrics .metric-foot {
  font-size: 11px;
}
.biz-metrics .metric-badge {
  font-size: 11px;
}
.biz-panel {
  margin-top: 18px;
}
.panel-body {
  padding: 0 20px 18px;
}
/* 版面：两列行，窄屏自动堆叠 */
.dash-row {
  display: grid;
  gap: 18px;
  margin-top: 18px;
  align-items: start;
}
.dash-row.two {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
}
.dash-row.two > :only-child {
  grid-column: 1 / -1;
}
.dash-row.two .line-cards {
  grid-template-columns: 1fr;
  gap: 10px;
}
.brand-matrix :deep(.cell) {
  white-space: nowrap;
  padding: 0 8px;
}
.dash-row.split {
  grid-template-columns: minmax(0, 1.35fr) minmax(340px, 1fr);
}
.dash-col {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.dash-row .biz-panel,
.dash-col .panel {
  margin-top: 0;
}
.dash-row.split .dist-row {
  grid-template-columns: minmax(72px, 1.1fr) minmax(50px, 1fr) repeat(4, minmax(58px, 0.75fr));
  gap: 8px;
  font-size: 12px;
}
.dash-row.split .dist-row > :nth-child(n + 7) {
  display: none;
}
.dim-more {
  margin-top: 8px;
}
.biz-metrics {
  gap: 14px;
}
.biz-metrics :deep(.metric-card),
.biz-metrics .metric-card {
  padding: 16px 18px 14px;
}
.biz-metrics .metric-value {
  margin: 12px 0 10px;
  font-size: clamp(20px, 1.7vw, 28px);
}
@media (max-width: 1200px) {
  .dash-row.two,
  .dash-row.split {
    grid-template-columns: 1fr;
  }
}
.biz-grid {
  margin-top: 22px;
}
.tip-extra {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid #edf0eb;
  color: #6b7a72;
}
.dist-legend {
  display: flex;
  gap: 18px;
  margin: 2px 0 10px;
  color: #53645b;
  font-size: 12px;
}
.dist-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.dist-legend i {
  width: 10px;
  height: 10px;
  border-radius: 3px;
}
.dist-row {
  display: grid;
  grid-template-columns: minmax(90px, 1.1fr) minmax(140px, 2.2fr) repeat(6, minmax(70px, 0.8fr));
  align-items: center;
  gap: 12px;
  padding: 9px 4px;
  border-bottom: 1px solid #f0f3ee;
  font-size: 13px;
}
.dist-head {
  color: #8a958e;
  font-size: 12px;
  border-bottom-color: #e5eae3;
}
.dist-head span:nth-child(n + 3),
.dist-row .num {
  text-align: right;
}
.num {
  font-variant-numeric: tabular-nums;
}
.dist-bars {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dist-bars i {
  display: block;
  height: 7px;
  border-radius: 0 4px 4px 0;
}
.panel-figure {
  font-size: 20px;
  color: #2b4531;
  font-variant-numeric: tabular-nums;
}
.aging-row {
  display: grid;
  grid-template-columns: 110px 1fr 120px 44px;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  font-size: 13px;
}
.aging-row .num {
  text-align: right;
}
.aging-row small {
  color: #8a958e;
  text-align: right;
}
.aging-track {
  height: 10px;
  background: #f1f4ef;
  border-radius: 0 5px 5px 0;
}
.aging-track i {
  display: block;
  height: 100%;
  border-radius: 0 5px 5px 0;
}
.backlog-row {
  display: grid;
  grid-template-columns: 120px 1fr auto;
  align-items: baseline;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f3ee;
}
.backlog-row strong {
  font-size: 18px;
  color: #2b4531;
}
.backlog-row small {
  color: #8a958e;
}
.concentration {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}
.concentration div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 8px;
  background: #f7f9f5;
}
.concentration span {
  color: #8a958e;
  font-size: 12px;
}
.concentration em {
  font-style: normal;
  font-size: 22px;
  color: #2b4531;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 1280px) {
  .biz-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .biz-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .dist-row {
    grid-template-columns: 90px 1fr repeat(3, 70px);
  }
  .dist-row > :nth-child(n + 6) {
    display: none;
  }
}
</style>
