import { computed, reactive } from 'vue'
import { api } from './api'
import type { City, Company, Owner, Scenario } from './types'

/** 数据字典（应用场景、城市、签约主体、销售负责人）的页面共享缓存；维护后调用 loadDictionaries(true) 刷新。 */
export const dict = reactive<{
  scenarios: Scenario[]
  cities: City[]
  companies: Company[]
  owners: Owner[]
  loaded: boolean
}>({ scenarios: [], cities: [], companies: [], owners: [], loaded: false })

let pending: Promise<void> | null = null
export function loadDictionaries(force = false): Promise<void> {
  if (dict.loaded && !force) return Promise.resolve()
  if (pending && !force) return pending
  pending = Promise.all([
    api<Scenario[]>('/dictionaries/scenarios'),
    api<City[]>('/dictionaries/cities'),
    api<Company[]>('/dictionaries/companies'),
    api<Owner[]>('/owners'),
  ])
    .then(([s, c, k, o]) => {
      dict.scenarios = s
      dict.cities = c
      dict.companies = k
      dict.owners = o
      dict.loaded = true
    })
    .finally(() => (pending = null))
  return pending
}

/** 城市按省份分组（启用的城市；keep 为当前值，停用也保留可见）。 */
export function cityGroups(keep?: string | null) {
  const groups = new Map<string, City[]>()
  for (const c of dict.cities) {
    if (!c.active && c.name !== keep) continue
    const p = c.province || '其他'
    if (!groups.has(p)) groups.set(p, [])
    groups.get(p)!.push(c)
  }
  return [...groups.entries()].map(([province, cities]) => ({ province, cities }))
}

export const activeScenarios = computed(() => dict.scenarios.filter((s) => s.active).map((s) => s.name))
export const activeCompanies = computed(() => dict.companies.filter((c) => c.active))
export const defaultCompany = computed(() => dict.companies.find((c) => c.is_default && c.active) || null)
export const activeOwners = computed(() => dict.owners.filter((o) => o.enabled))
export const companyLabel = (c: Company) => c.short_name || c.name

export const contractStatusName: Record<string, string> = {
  DRAFT: '拟定中',
  SIGNED: '已签订',
  COMPLETED: '已完结',
  TERMINATED: '已终止',
}
export const invoiceStatusName: Record<string, string> = { NONE: '未开票', PARTIAL: '部分开票', FULL: '已开足' }
export const costCategoryName: Record<string, string> = {
  MATERIAL: '材料采购',
  LABOR: '团队人工',
  INSTALL: '安装调试',
  FREIGHT: '运输物流',
  FINANCE: '财务成本',
  OTHER: '其他',
}
export const changeStatusName: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  DIRECT: '已生效',
}
