import { reactive } from 'vue'
import type { User } from './types'
export const session = reactive<{ user: User | null; ready: boolean }>({ user: null, ready: false })
let csrf = { token: '', header: 'X-CSRF-TOKEN' }
export async function refreshCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法连接服务，请检查后端是否启动')
  csrf = await response.json()
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = options.method || 'GET'
  if (method !== 'GET' && !csrf.token) await refreshCsrf()
  const response = await fetch(`/api${path}`, {
    ...options,
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(method !== 'GET' ? { [csrf.header]: csrf.token } : {}),
      ...options.headers,
    },
  })
  if (!response.ok) {
    if (response.status === 401) {
      session.user = null
      if (location.pathname !== '/login') location.assign('/login')
    }
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || `请求失败（${response.status}）`)
  }
  return response.status === 204 || response.headers.get('content-length') === '0'
    ? (undefined as T)
    : await response.text().then((s) => (s ? (JSON.parse(s) as T) : (undefined as T)))
}
export const put = (path: string, data: unknown): Promise<void> =>
  api<void>(path, { method: 'PUT', body: JSON.stringify(data) })
export const del = (path: string): Promise<void> => api<void>(path, { method: 'DELETE' })
/** multipart 上传：不设置 Content-Type，由浏览器生成 boundary。 */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  if (!csrf.token) await refreshCsrf()
  const response = await fetch(`/api${path}`, {
    method: 'POST',
    body: form,
    credentials: 'same-origin',
    headers: { [csrf.header]: csrf.token },
  })
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    if (response.status === 401) session.user = null
    throw new Error(body.message || (response.status === 413 ? '文件过大' : `上传失败（${response.status}）`))
  }
  return body as T
}
export const post = <T = void>(path: string, data: unknown): Promise<T> =>
  api<T>(path, { method: 'POST', body: JSON.stringify(data) })
export async function loadSession() {
  try {
    session.user = await api<User>('/auth/me')
  } catch {
    session.user = null
  }
  session.ready = true
}
export async function login(username: string, password: string) {
  await refreshCsrf()
  await api('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password }),
  })
  await refreshCsrf()
  await loadSession()
}
/** 业务操作（门店、报价、合同、基础资料）：系统管理员、管理层、业务员。 */
export const canWrite = () => ['ADMIN', 'MANAGER', 'SALES'].includes(session.user?.role || '')
export const canAttach = () => ['ADMIN', 'MANAGER', 'SALES', 'FINANCE'].includes(session.user?.role || '')
export const canFinance = () => ['ADMIN', 'FINANCE'].includes(session.user?.role || '')
export const isAdmin = () => session.user?.role === 'ADMIN'
/** 审批合同变更、直接修改已签订合同、分配负责人：系统管理员或管理层。 */
export const canApprove = () => ['ADMIN', 'MANAGER'].includes(session.user?.role || '')
/** 可见成本与毛利：系统管理员、管理层、财务（后端同样会去掉其他角色的成本字段）。 */
export const canSeeCost = () => ['ADMIN', 'MANAGER', 'FINANCE'].includes(session.user?.role || '')
