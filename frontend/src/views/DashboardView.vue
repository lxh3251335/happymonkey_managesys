<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Plus,
  ArrowRight,
  Document,
  Coin,
  TrendCharts,
  Wallet,
  FolderOpened,
  Connection,
} from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import { api, canWrite, session } from '../api'
import type { Dashboard, Quote } from '../types'
import { money, percent } from '../utils'
const data = ref<Dashboard>(),
  error = ref(''),
  router = useRouter()
const biggest = computed(() => Math.max(...(data.value?.customers_breakdown.map((c) => c.amount) || [1])))
const openQuote = (q: Quote) => router.push(`/quotes?detail=${q.id}`)
async function load() {
  try {
    data.value = await api<Dashboard>('/dashboard')
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader eyebrow="BUSINESS OVERVIEW" title="经营总览" description="每个项目的报价、利润与回款，尽在掌握。">
      <el-button @click="router.push('/projects')">
        <el-icon><FolderOpened /></el-icon>
        项目台账
      </el-button>
      <el-button v-if="canWrite()" type="primary" @click="router.push('/quotes?new=1')">
        <el-icon><Plus /></el-icon>
        快速报价
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <template v-if="data">
      <div class="overview-strip">
        <div>
          <span class="online-dot"></span>
          {{ session.user?.display_name }}，欢迎回来
          <span class="strip-divider">/</span>
          <span>
            当前管理
            <strong>{{ data.projects }}</strong>
            个项目，服务
            <strong>{{ data.customers }}</strong>
            家客户
          </span>
        </div>
        <span class="muted">以项目最新报价版本汇总</span>
      </div>
      <section class="metric-grid">
        <article class="metric-card">
          <div class="metric-label">
            累计报价金额
            <el-icon><Document /></el-icon>
          </div>
          <div class="metric-value">{{ money(data.amount) }}</div>
          <div class="metric-foot">
            <span class="metric-badge">{{ data.quotes }} 份</span>
            项目最新报价 · 已知金额
          </div>
        </article>
        <article class="metric-card">
          <div class="metric-label">
            预计报价利润
            <el-icon><Coin /></el-icon>
          </div>
          <div class="metric-value">{{ money(data.profit) }}</div>
          <div class="metric-foot">{{ data.complete_quotes }} 份成本及报价完整的记录</div>
        </article>
        <article class="metric-card">
          <div class="metric-label">
            销售毛利率
            <el-icon><TrendCharts /></el-icon>
          </div>
          <div class="metric-value">
            {{ percent(data.gross_margin) }}
            <span class="metric-unit">毛利 / 报价</span>
          </div>
          <div class="metric-foot">按完整记录加权 · 未计额外费用</div>
        </article>
        <article class="metric-card accent">
          <div class="metric-label">
            待收客户款
            <el-icon><Wallet /></el-icon>
          </div>
          <div class="metric-value">{{ money(data.receivable) }}</div>
          <div class="metric-foot">
            {{ data.accepted }} 份已确认订单
            <span v-if="data.overdue" class="danger-text">· {{ data.overdue }} 份逾期</span>
          </div>
        </article>
      </section>
      <div v-if="data.incomplete" class="data-note">
        <span class="note-dot"></span>
        <strong>{{ data.incomplete }} 份报价待补充金额或成本</strong>
        <span>利润及毛利率仅统计数据完整的报价；历史报价确认后才计入应收。</span>
        <router-link to="/quotes?incomplete=1">
          查看待补充
          <el-icon><ArrowRight /></el-icon>
        </router-link>
      </div>
      <div class="dashboard-grid">
        <section class="panel customer-panel">
          <div class="panel-heading">
            <div>
              <h2>客户报价分布</h2>
              <p>最新报价的已知金额，按客户汇总</p>
            </div>
            <span class="small-label">CNY / 人民币</span>
          </div>
          <div v-if="data.customers_breakdown.length" class="customer-bars">
            <div v-for="(c, i) in data.customers_breakdown.slice(0, 6)" :key="c.name" class="customer-bar-row">
              <span class="bar-rank">{{ String(i + 1).padStart(2, '0') }}</span>
              <div class="bar-body">
                <div class="bar-label">
                  <strong>{{ c.name }}</strong>
                  <span>{{ money(c.amount) }}</span>
                </div>
                <div class="bar-track" :aria-label="`${c.name}报价${c.amount}元`">
                  <span :style="{ width: `${Math.max(1, (c.amount / biggest) * 100)}%` }"></span>
                </div>
              </div>
              <small>{{ c.count }} 项目</small>
            </div>
          </div>
          <el-empty v-else description="登记项目并创建第一份报价" />
        </section>
        <section class="panel brand-panel">
          <div class="panel-heading">
            <div>
              <h2>业务项目构成</h2>
              <p>品牌与业务分布</p>
            </div>
            <span class="small-label">PORTFOLIO</span>
          </div>
          <div v-for="b in data.brands_breakdown" :key="b.name" class="brand-summary">
            <div :class="['business-symbol', b.name === '快乐猴' ? 'monkey' : 'elephant']">
              {{ b.name === '快乐猴' ? '猴' : b.name === '小象' ? '象' : '项' }}
            </div>
            <div>
              <strong>{{ b.name === '小象' ? '美团小象' : b.name === '快乐猴' ? '美团快乐猴' : b.name }}</strong>
              <small>{{ b.count }} 个项目</small>
            </div>
            <div class="brand-amount">{{ money(b.amount) }}</div>
          </div>
          <div class="brand-panel-footer">
            <el-icon><Connection /></el-icon>
            <div>
              <strong>云智冷项目关联</strong>
              <p>在项目台账中维护对应项目 ID</p>
            </div>
            <el-button link type="primary" @click="router.push('/projects')">去关联 →</el-button>
          </div>
        </section>
      </div>
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
          <el-table-column label="销售毛利率" align="right" width="125">
            <template #default="{ row }">{{ percent(row.gross_margin) }}</template>
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
