<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { businesses, loadBusinesses } from '../business'
import { useRoute, useRouter } from 'vue-router'
import {
  DataAnalysis,
  FolderOpened,
  Document,
  Connection,
  Wallet,
  Grid,
  Setting,
  SwitchButton,
  ArrowRight,
  ArrowDown,
} from '@element-plus/icons-vue'
import { api, session } from '../api'
import { roleName, today } from '../utils'
const route = useRoute(),
  router = useRouter()
/** 菜单；报价配置中的成本相关标签页按角色显示。 */
const menu = [
  { path: '/', name: '经营总览', icon: DataAnalysis },
  // 日常使用：项目台账、合同台账；其次是报价与配置类页面
  { path: '/projects', name: '项目台账', icon: FolderOpened },
  { path: '/cash', name: '合同台账', icon: Wallet },
  { path: '/quotes', name: '客户报价', icon: Document },
  { path: '/quote-setup', name: '报价配置', icon: Connection },
  { path: '/masters', name: '基础资料', icon: Grid },
  { path: '/settings', name: '系统管理', icon: Setting },
]
const current = computed(() => route.meta.title as string)
async function logout() {
  await api('/auth/logout', { method: 'POST' })
  session.user = null
  await router.push('/login')
}
/** 侧边栏显示业务字典中启用的客户集团与业务线。 */
const activeGroups = computed(() => businesses.groups.filter((g) => g.active && g.lines.some((l) => l.active)))
const activeLines = computed(() => activeGroups.value.flatMap((g) => g.lines.filter((l) => l.active)))
onMounted(() => loadBusinesses().catch(() => undefined))
</script>
<template>
  <div class="workspace">
    <aside class="sidebar">
      <router-link class="brand" to="/">
        <span class="brand-mark">
          T
          <span>.</span>
        </span>
        <div>
          <strong>天商 · 商务云</strong>
          <small>TIANSHANG COMMERCE</small>
        </div>
      </router-link>
      <div class="workspace-label">项目经营工作台</div>
      <nav>
        <router-link
          v-for="item in menu"
          :key="item.path"
          :to="item.path"
          :class="{ active: route.path === item.path }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.name }}</span>
          <span v-if="route.path === item.path" class="nav-dot"></span>
        </router-link>
      </nav>
      <div class="sidebar-project">
        <div class="mini-label">服务客户</div>
        <strong>{{ activeGroups.map((g) => g.name).join(' · ') || '未设置业务字典' }}</strong>
        <div class="brand-pills">
          <span v-for="l in activeLines" :key="l.id">{{ l.short_name || l.name }}</span>
        </div>
        <p>报价 · 成本 · 利润 · 回款</p>
      </div>
      <div class="sidebar-bottom">
        <span class="online-dot"></span>
        企业工作空间
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          工作台
          <el-icon><ArrowRight /></el-icon>
          <strong>{{ current }}</strong>
        </div>
        <div class="topbar-right">
          <span class="current-date">{{ today().replaceAll('-', ' / ') }}</span>
          <span class="top-divider"></span>
          <span class="avatar">{{ session.user?.display_name.slice(0, 1) }}</span>
          <span class="user-name">
            {{ session.user?.display_name }}
            <small>{{ roleName[session.user?.role || ''] }}</small>
          </span>
          <el-dropdown>
            <el-icon class="user-dropdown"><ArrowDown /></el-icon>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="router.push('/settings')">账户设置</el-dropdown-item>
                <el-dropdown-item @click="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>
      <main class="page-content"><router-view /></main>
      <footer class="workspace-footer">
        天商 · 项目经营管理
        <span>数据随业务沉淀，让每一笔报价有据可查。</span>
      </footer>
    </div>
  </div>
</template>
