import { createRouter, createWebHistory } from 'vue-router'
import { loadSession, session } from './api'
import AppLayout from './layouts/AppLayout.vue'
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    { path: '/print/:id', component: () => import('./views/PrintView.vue'), meta: { title: '客户报价单' } },
    {
      path: '/print/:id/contract',
      component: () => import('./views/ContractPrintView.vue'),
      meta: { title: '购销合同' },
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', component: () => import('./views/DashboardView.vue'), meta: { title: '经营总览' } },
        { path: 'projects', component: () => import('./views/ProjectsView.vue'), meta: { title: '项目台账' } },
        { path: 'quotes', component: () => import('./views/QuotesView.vue'), meta: { title: '客户报价' } },
        {
          path: 'quote-setup',
          component: () => import('./views/QuoteSetupView.vue'),
          meta: { title: '报价配置' },
        },
        { path: 'offers', redirect: '/quote-setup?tab=offers' },
        { path: 'cash', component: () => import('./views/CashView.vue'), meta: { title: '合同台账' } },
        { path: 'masters', component: () => import('./views/MastersView.vue'), meta: { title: '基础资料' } },
        { path: 'settings', component: () => import('./views/SettingsView.vue'), meta: { title: '系统管理' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async (to) => {
  if (!session.ready) await loadSession()
  if (!session.user && to.path !== '/login') return '/login'
  if (session.user && to.path === '/login') return '/'
})
