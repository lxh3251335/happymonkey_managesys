/**
 * 回款粘贴识别：从银行短信、回单上复制的文字里找出到账金额、日期、付款方和流水号。
 * 纯规则、在浏览器里完成；认不出的留给人填。余额不是到账金额，会先被排除。
 */

export interface ParsedReceipt {
  amount?: number
  /** YYYY-MM-DD */
  paidOn?: string
  payer?: string
  reference?: string
}

const NUM = '(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)'
const CURRENCY = '(?:人民币|RMB|CNY|¥|￥)?'

function normalize(text: string) {
  return text
    .replace(/[！-～]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/　/g, ' ')
    .replace(/\r\n?/g, '\n')
}
const toNumber = (s: string) => Number(s.replace(/,/g, ''))
const pad = (n: number) => String(n).padStart(2, '0')

function findAmount(text: string): number | undefined {
  // 余额、手续费不是到账金额
  const t = text.replace(new RegExp(`(可用余额|当前余额|账户余额|余额|手续费)[^\\d\\n]{0,8}${NUM}\\s*元?`, 'g'), ' ')
  const patterns = [
    // 收到 / 收入 / 入账……人民币 30,000.00 元
    new RegExp(`(?:收到|收入|入账|转入|存入|汇入|到账|来账|贷记|收款|已收)[^\\d\\n]{0,16}?${CURRENCY}\\s*${NUM}`),
    // 金额：30,000.00
    new RegExp(
      `(?:交易金额|转账金额|付款金额|汇款金额|到账金额|金额)(?:\\(元\\)|（元）)?\\s*[:：]?\\s*${CURRENCY}\\s*${NUM}`,
    ),
    new RegExp(`(?:人民币|RMB|CNY|¥|￥)\\s*${NUM}`),
    new RegExp(`${NUM}\\s*元`),
  ]
  for (const re of patterns) {
    const m = t.match(re)
    if (m) {
      const v = toNumber(m[1])
      if (v > 0) return v
    }
  }
  return undefined
}

function findDate(text: string, today: Date): string | undefined {
  let m = text.match(/(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})/)
  if (m) return `${m[1]}-${pad(+m[2])}-${pad(+m[3])}`
  m = text.match(/(?<!\d)(20\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(?!\d)/)
  if (m) return `${m[1]}-${m[2]}-${m[3]}`
  m = text.match(/(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*日/)
  if (m) {
    // 短信里常不带年份：按今年算，算出来在将来就是去年的
    let year = today.getFullYear()
    const candidate = new Date(year, +m[1] - 1, +m[2])
    if (candidate.getTime() > today.getTime() + 86400000) year--
    return `${year}-${pad(+m[1])}-${pad(+m[2])}`
  }
  return undefined
}

const ORG =
  '[\\u4e00-\\u9fa5A-Za-z0-9（）()·]{2,40}?(?:有限责任公司|股份有限公司|有限公司|公司|工厂|厂|店|中心|经营部|商行|合作社)'

function findPayer(text: string): string | undefined {
  const labeled = text.match(
    /(?:付款人|付款方|付款单位|付款户名|付方|对方户名|对方账户名称?|对方名称|对方单位|汇款人|汇款单位|来账户名|对方)(?:名称|户名|全称)?\s*[:：为]?\s*([^\s,，。;；:：]{2,40})/,
  )
  if (labeled) return labeled[1].replace(/[（(]?尾号.*$/, '').replace(/的?(?:转账|汇款|来款|付款)$/, '')
  // “某某公司向您尾号 8888 的账户转入……”
  const org = text.match(new RegExp(`(${ORG})\\s*(?:向|给|于|已|转入|汇入|支付|付款)`))
  return org?.[1]
}

function findReference(text: string): string | undefined {
  return text.match(
    /(?:交易流水号?|银行流水号?|流水号|凭证号码?|业务编号|交易参考号|参考号|回单编号|回单号|交易序号)\s*[:：]?\s*([A-Za-z0-9-]{6,40})/,
  )?.[1]
}

/** today 只用于给不带年份的日期补年份。 */
export function parseReceipt(input: string, today = new Date()): ParsedReceipt {
  const text = normalize(input)
  const out: ParsedReceipt = {}
  if (!text.trim()) return out
  const amount = findAmount(text)
  if (amount != null) out.amount = amount
  const paidOn = findDate(text, today)
  if (paidOn) out.paidOn = paidOn
  const payer = findPayer(text)
  if (payer) out.payer = payer
  const reference = findReference(text)
  if (reference) out.reference = reference
  return out
}

/**
 * 按付款方（或整段文字）找客户：客户名称或开票抬头出现在文字里。
 * 系统里的客户常是简称（如“中野”），付款方是全称，所以用“全称包含简称”来对；多个都对上时取名字最长的。
 */
export function matchCustomer<T extends { name: string; invoice_title?: string | null }>(
  text: string,
  customers: T[],
): T | undefined {
  const t = normalize(text).replace(/\s+/g, '')
  if (!t) return undefined
  let best: T | undefined,
    bestLength = 0
  for (const c of customers)
    for (const key of [c.name, c.invoice_title]) {
      const k = (key || '').replace(/\s+/g, '')
      if (k.length >= 2 && t.includes(k) && k.length > bestLength) {
        best = c
        bestLength = k.length
      }
    }
  return best
}
