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
  LOST: '未成交',
  SUPERSEDED: '已被取代',
}
/** 报价跟进阶段的显示名称。 */
export const stageName: Record<string, string> = {
  DRAFT: '草稿',
  ISSUED: '待客户确认',
  ACCEPTED: '待生成合同',
  CONTRACTED: '已转合同',
  LOST: '未成交',
  ARCHIVED: '已被新版本取代',
}
export const taxName: Record<string, string> = { INCLUDED: '含税', EXCLUDED: '未税', UNKNOWN: '税口径待核实' }
export const roleName: Record<string, string> = {
  ADMIN: '系统管理员',
  MANAGER: '管理层',
  FINANCE: '财务',
  SALES: '业务员',
  VIEWER: '只读',
}
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
/** 人民币金额大写，用于合同。 */
export function rmbUpper(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(Number(value))) return ''
  const digits = '零壹贰叁肆伍陆柒捌玖'
  const units = ['', '拾', '佰', '仟']
  const groups = ['', '万', '亿', '万亿']
  const fen = Math.round(Math.abs(Number(value)) * 100)
  const yuan = Math.floor(fen / 100),
    jiao = Math.floor(fen / 10) % 10,
    cent = fen % 10
  let out = ''
  if (yuan === 0) out = '零'
  else {
    const s = String(yuan)
    let zero = false
    for (let i = 0; i < s.length; i++) {
      const d = Number(s[i]),
        pos = s.length - 1 - i,
        unit = units[pos % 4]
      if (d === 0) zero = true
      else {
        if (zero && out) out += '零'
        out += digits[d] + unit
        zero = false
      }
      if (pos % 4 === 0 && pos > 0) {
        const groupDigits = s.slice(Math.max(0, i - 3), i + 1)
        if (Number(groupDigits) > 0) out += groups[pos / 4]
      }
    }
  }
  out += '元'
  if (jiao === 0 && cent === 0) return `${Number(value) < 0 ? '负' : ''}${out}整`
  out += jiao ? digits[jiao] + '角' : '零'
  if (cent) out += digits[cent] + '分'
  return `${Number(value) < 0 ? '负' : ''}${out}`
}
/** 开票信息必填五项（抬头缺省用名称）：税号、地址、电话、开户行、账号。返回缺少的项目名称。 */
export function missingInvoiceFields(p: {
  name: string
  invoice_title: string | null
  tax_no: string | null
  address: string | null
  invoice_phone: string | null
  bank_name: string | null
  bank_account: string | null
}): string[] {
  const items: [string, string | null][] = [
    ['税号', p.tax_no],
    ['地址', p.address],
    ['电话', p.invoice_phone],
    ['开户行', p.bank_name],
    ['账号', p.bank_account],
  ]
  return items.filter(([, v]) => !v).map(([k]) => k)
}
/** 复制用的开票信息文本，未填抬头时使用名称。 */
export function invoiceText(p: {
  name: string
  invoice_title: string | null
  tax_no: string | null
  address: string | null
  invoice_phone: string | null
  bank_name: string | null
  bank_account: string | null
}): string {
  return [
    `单位名称：${p.invoice_title || p.name}`,
    `纳税人识别号：${p.tax_no || ''}`,
    `地址、电话：${[p.address, p.invoice_phone].filter(Boolean).join(' ')}`,
    `开户行及账号：${[p.bank_name, p.bank_account].filter(Boolean).join(' ')}`,
  ].join('\n')
}
export const invoiceTypeName: Record<string, string> = { SPECIAL: '增值税专用发票', NORMAL: '增值税普通发票' }

/** 邮寄信息文本（一键复制）：门店、收货人、电话、地址，已发货时附快递与运单号。 */
export function deliveryText(p: {
  name: string
  consignee?: string | null
  consignee_phone?: string | null
  ship_address?: string | null
  courier?: string | null
  tracking_no?: string | null
}): string {
  const lines = [
    `门店：${p.name}`,
    `收货人：${p.consignee || ''}`,
    `电话：${p.consignee_phone || ''}`,
    `地址：${p.ship_address || ''}`,
  ]
  if (p.tracking_no) lines.push(`快递：${p.courier || ''} ${p.tracking_no}`)
  return lines.join('\n')
}

/** 复制文本到剪贴板，返回是否成功（浏览器未授权时返回 false）。 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/** 日期加时分，如 2026-11-16 09:30。 */
export const dateTime = (s: string | null | undefined) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

/** 操作记录中的操作名称（后端记录的是代码）。合同详情的操作记录与系统管理的操作审计共用。 */
export const actionName: Record<string, string> = {
  CONTRACT_CREATED: '建立合同',
  CONTRACT_FROM_QUOTE: '由报价生成合同',
  CONTRACT_UPDATED: '修改合同',
  CONTRACT_CHANGE_REQUESTED: '提交变更申请',
  CONTRACT_CHANGE_APPROVED: '批准变更',
  CONTRACT_CHANGE_REJECTED: '驳回变更',
  CONTRACT_CHANGE_WITHDRAWN: '撤回变更',
  CONTRACT_CONFIRMED: '设置应收截止日',
  CONTRACT_QUOTE_REPLACED: '更换门店报价',
  CONTRACT_RECEIPT: '登记回款',
  CONTRACT_REFUND: '登记退款',
  CONTRACT_INVOICE: '登记开票',
  CONTRACT_INVOICE_RED: '登记红字发票',
  CONTRACT_INVOICE_DELETED: '删除发票登记',
  CONTRACT_COST: '登记成本',
  CONTRACT_COST_DELETED: '删除成本登记',
  CASH_REFUND: '历史流水退款冲正',
  RECEIPT_BATCH_CREATED: '登记回款单',
  RECEIPT_BATCH_ALLOCATED: '分配回款余额',
  BOT_BOUND: '绑定企业微信机器人',
  BOT_UNBOUND: '解除企业微信绑定',
  BOT_CONFIG_UPDATED: '修改企业微信机器人配置',
  PROJECT_CREATED: '登记门店',
  PROJECT_UPDATED: '修改门店',
  PROJECT_DELETED: '删除门店',
  PROJECT_DELIVERY: '发货物流',
  PROJECT_OWNER_ASSIGNED: '分配负责人',
  PROJECT_TYPE_SAVED: '保存项目类型',
  QUOTE_CREATED: '新建报价',
  QUOTE_UPDATED: '修改报价草稿',
  QUOTE_DELETED: '删除报价草稿',
  QUOTE_ISSUE: '出具报价',
  QUOTE_ACCEPT: '客户确认报价',
  QUOTE_LOST: '标记未成交',
  QUOTE_REOPENED: '恢复跟进',
  QUOTE_SUPERSEDED: '报价被新版本取代',
  QUOTE_PINNED: '确定合同依据的报价',
  QUOTE_DUE_DATE: '设置应收截止日',
  QUOTE_COST_CORRECTED: '更正报价成本',
  CUSTOMER_TEMPLATE_SAVED: '保存客户报价模板',
  SUPPLIER_TEMPLATE_SAVED: '保存供应商成本模板',
  SUPPLIER_OFFER_CREATED: '登记供应商报价',
  TEMPLATE_CREATED: '新增报价单版式',
  TEMPLATE_UPDATED: '修改报价单版式',
  TEMPLATE_DEFAULT: '设为默认版式',
  PARTNER_CREATED: '新增客户或供应商',
  PARTNER_UPDATED: '修改客户或供应商资料',
  PRODUCT_CREATED: '新增产品',
  PRODUCT_UPDATED: '修改产品',
  PRODUCT_MAINSTREAM: '标记主流配置',
  DRAWING_UPLOADED: '上传图纸',
  DRAWING_DOWNLOADED: '下载图纸',
  DRAWING_DELETED: '删除图纸',
  ATTACHMENT_UPLOADED: '上传附件',
  ATTACHMENT_DOWNLOADED: '下载附件',
  ATTACHMENT_DELETED: '删除附件',
  COMPANY_CREATED: '新增签约主体',
  COMPANY_UPDATED: '修改签约主体',
  BUSINESS_GROUP_CREATED: '新增客户集团',
  BUSINESS_GROUP_UPDATED: '修改客户集团',
  BUSINESS_LINE_CREATED: '新增业务线',
  BUSINESS_LINE_UPDATED: '修改业务线',
  SCENARIO_CREATED: '新增应用场景',
  SCENARIO_UPDATED: '修改应用场景',
  CITY_CREATED: '新增城市',
  CITY_UPDATED: '修改城市',
  USER_CREATED: '新增用户',
  USER_UPDATED: '修改用户',
  USER_PASSWORD_RESET: '重置用户密码',
  PASSWORD_CHANGED: '修改密码',
  EXCEL_EXPORT: '导出 Excel',
  EXCEL_IMPORT: '导入 Excel',
  SOURCE_IMPORTED: '导入历史数据',
}
