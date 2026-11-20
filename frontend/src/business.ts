import { computed, reactive } from 'vue'
import { api } from './api'
import type { BusinessGroup } from './types'

/** 业务字典（客户集团 → 业务线）的页面共享缓存；维护后调用 loadBusinesses(true) 刷新。 */
export const businesses = reactive<{ groups: BusinessGroup[]; loaded: boolean }>({ groups: [], loaded: false })

let pending: Promise<void> | null = null
export function loadBusinesses(force = false): Promise<void> {
  if (businesses.loaded && !force) return Promise.resolve()
  if (pending && !force) return pending
  pending = api<BusinessGroup[]>('/businesses')
    .then((g) => {
      businesses.groups = g
      businesses.loaded = true
    })
    .finally(() => (pending = null))
  return pending
}

/** 业务线名称 → 所属集团名称。 */
export const groupOfLine = computed(() => {
  const m: Record<string, string> = {}
  for (const g of businesses.groups) for (const l of g.lines) m[l.name] = g.name
  return m
})
