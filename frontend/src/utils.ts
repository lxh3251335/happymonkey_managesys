export const money = (n: number | null | undefined) =>
  n == null
    ? '待补充'
    : new Intl.NumberFormat('zh-CN', {
        style: 'currency',
        currency: 'CNY',
        maximumFractionDigits: 2,
        minimumFractionDigits: 2,
      }).format(n)
export const percent = (n: number | null | undefined) => (n == null ? '待补充' : `${(n * 100).toFixed(2)}%`)
export const unitMoney = (n: number | null | undefined) =>
  n == null
    ? '待补充'
    : new Intl.NumberFormat('zh-CN', {
        style: 'currency',
        currency: 'CNY',
        minimumFractionDigits: 2,
        maximumFractionDigits: 6,
      }).format(n)
export const day = (n: string | null | undefined) => (n ? String(n).slice(0, 10) : '—')
export function today() {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
}
export function afterDays(n: number) {
  const d = new Date(`${today()}T00:00:00+08:00`)
  d.setDate(d.getDate() + n)
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(d)
}
export const statusName: Record<string, string> = {
  DRAFT: '草稿',
  ISSUED: '已出具',
  ACCEPTED: '已确认',
  HISTORICAL: '历史报价',
}
export const taxName: Record<string, string> = { INCLUDED: '含税', EXCLUDED: '未税', UNKNOWN: '税口径待核实' }
export const roleName: Record<string, string> = { ADMIN: '管理员', SALES: '商务', FINANCE: '财务', VIEWER: '只读' }
/** 金额以“万元”紧凑显示，用于指标卡和图表坐标。 */
export const wan = (n: number | null | undefined, digits = 2) =>
  n == null
    ? '待补充'
    : `${(Number(n) / 10000).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })} 万`
export const axisWan = (n: number) =>
  n === 0 ? '0' : `${(n / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}万`
export const monthLabel = (m: string) => (/^\d{4}-\d{2}$/.test(m) ? `${Number(m.slice(5))}月` : m)
export const attachmentCategory: Record<string, string> = {
  CONTRACT: '合同',
  STAMPED_QUOTE: '盖章报价单',
  DELIVERY: '送货签收',
  INVOICE: '发票',
  OTHER: '其他',
}
export const fileSize = (n: number) =>
  n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`
