import { nextTick, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

type Value = string | number | boolean

/**
 * 把页面上的筛选、标签页、页码写进地址栏：刷新、从详情返回、把链接发给同事，都还是同一个画面。
 *
 * 用法：`const bind = useUrlState()`，然后对每个筛选 `bind('q', keyword, '')`。
 * 地址里有这个参数时用它作初始值；之后筛选一变就写回地址。取默认值时从地址里去掉，保持地址简短。
 * 同一时刻改了几个筛选，合并成一次地址替换，不会互相覆盖；离开页面后不再改地址。
 */
export function useUrlState() {
  const route = useRoute(),
    router = useRouter()
  const path = route.path
  const pending: Record<string, string | undefined> = {}
  let scheduled = false

  function flush() {
    scheduled = false
    if (route.path !== path) return
    const query = { ...route.query }
    for (const [key, value] of Object.entries(pending)) {
      if (value === undefined) delete query[key]
      else query[key] = value
      delete pending[key]
    }
    router.replace({ query })
  }

  function write(key: string, value: string | undefined) {
    pending[key] = value
    if (!scheduled) {
      scheduled = true
      nextTick(flush)
    }
  }

  function bind<T extends Value>(key: string, state: Ref<T>, fallback: T) {
    const raw = route.query[key]
    if (typeof raw === 'string' && raw !== '') {
      if (typeof fallback === 'number') {
        const n = Number(raw)
        if (Number.isFinite(n) && n > 0) state.value = n as T
      } else if (typeof fallback === 'boolean') state.value = (raw === '1') as T
      else state.value = raw as T
    }
    watch(state, (v) =>
      write(key, v === fallback || v === '' ? undefined : typeof v === 'boolean' ? (v ? '1' : '0') : String(v)),
    )
  }

  return Object.assign(bind, { write })
}
