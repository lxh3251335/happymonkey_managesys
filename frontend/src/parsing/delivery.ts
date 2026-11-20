/**
 * 物流信息粘贴识别：从一段随手粘贴的文字里拆出收货人、电话、地址、快递公司和运单号。
 * 纯规则、在浏览器里完成；识别不出的部分原样留给人填，不猜。
 */

export interface ParsedDelivery {
  consignee?: string
  phone?: string
  address?: string
  courier?: string
  trackingNo?: string
  /** 快递公司是按运单号开头推断的（文字里没写），需要人核对。 */
  courierGuessed?: boolean
}

/** 文字里的叫法 → 系统里的快递公司名称。 */
const COURIER_ALIASES: [RegExp, string][] = [
  [/顺丰|SF\s*Express/i, '顺丰速运'],
  [/京东/, '京东物流'],
  [/德邦/, '德邦快递'],
  [/中通/, '中通快递'],
  [/圆通/, '圆通速递'],
  [/韵达/, '韵达快递'],
  [/申通/, '申通快递'],
  [/EMS|邮政/i, 'EMS'],
  [/跨越/, '跨越速运'],
  [/安能/, '安能物流'],
  [/极兔/, '极兔速递'],
  [/专车/, '专车配送'],
  [/自提/, '客户自提'],
]

/** 运单号开头 → 快递公司。字母前缀可靠；纯数字的只收常见且不易混淆的几种。 */
const TRACKING_PREFIX: [RegExp, string][] = [
  [/^SF\d{10,}$/i, '顺丰速运'],
  [/^JD[A-Z]{0,2}\d{9,}/i, '京东物流'],
  [/^DPK?\d{9,}$/i, '德邦快递'],
  [/^YT\d{10,}$/i, '圆通速递'],
  [/^JT\d{10,}$/i, '极兔速递'],
  [/^KY\d{10,}$/i, '跨越速运'],
  [/^7[358]\d{12}$/, '中通快递'],
  [/^4[36]\d{11,13}$/, '韵达快递'],
  [/^77\d{13}$/, '申通快递'],
]

const ADDRESS_HINT = /[省市区县镇乡村路街道巷弄号栋幢座楼层室]|单元|大厦|广场|园区|工业园|小区|仓库/
const NAME_TITLE = /(先生|女士|小姐|老师|师傅|店长|经理|主管|老板|总)$/
const SEP = /[\s,，;；、|]+/

/** 全角数字、字母、冒号等转半角，统一换行。 */
function normalize(text: string) {
  return text
    .replace(/[！-～]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/　/g, ' ')
    .replace(/\r\n?/g, '\n')
}

/** 取出带标签的字段（如“收货人：张三”），并从原文中去掉这一段。 */
function takeLabeled(text: string, labels: string, value: string): [string | undefined, string] {
  const re = new RegExp(`(?:${labels})\\s*[:：]?\\s*(${value})`)
  const m = text.match(re)
  if (!m) return [undefined, text]
  return [m[1].trim(), text.replace(m[0], ' ')]
}

export function courierOfTracking(no: string): string | undefined {
  return TRACKING_PREFIX.find(([re]) => re.test(no))?.[1]
}

/** 运单号：字母开头接数字，或 12 位以上纯数字；排除手机号。 */
function findTracking(text: string): string | undefined {
  const candidates = text.match(/[A-Za-z]{1,4}\d{9,}[A-Za-z0-9]*|\d{12,20}/g) || []
  return candidates.find((c) => !/^1[3-9]\d{9}$/.test(c))
}

function findPhone(text: string): [string | undefined, string] {
  // 手机号中间可能有空格或短横：138-0000-1111、138 0000 1111
  const mobile = text.match(/(?<![\dA-Za-z])1[3-9]\d(?:[ -]?\d{4}){2}(?![\dA-Za-z])/)
  if (mobile) return [mobile[0].replace(/[ -]/g, ''), text.replace(mobile[0], ' ')]
  const landline = text.match(/(?<!\d)0\d{2,3}-?\d{7,8}(?:-\d{1,6})?(?!\d)/)
  if (landline) return [landline[0], text.replace(landline[0], ' ')]
  return [undefined, text]
}

/**
 * 识别一段文字。只返回认得出的字段；couriers 是系统里已有的快递公司名称，文字里写了全称时直接采用。
 */
export function parseDelivery(input: string, couriers: string[] = []): ParsedDelivery {
  const out: ParsedDelivery = {}
  let text = normalize(input)
  if (!text.trim()) return out

  // 1. 带标签的字段
  let v: string | undefined
  ;[v, text] = takeLabeled(text, '运单号码?|快递单号|物流单号|快递号|单号', '[A-Za-z0-9]{8,24}')
  if (v) out.trackingNo = v.toUpperCase()
  ;[v, text] = takeLabeled(text, '收货人|收件人|收货联系人|联系人|姓名', '[^\\s,，;；:：\\d]{1,12}')
  if (v) out.consignee = v
  ;[v, text] = takeLabeled(text, '收货地址|收件地址|详细地址|地址', '[^\\n]+')
  if (v) out.address = v
  // “电话：”等标签本身没有信息，去掉后按号码规则找
  text = text.replace(/(联系电话|收货电话|手机号码?|电话|手机|联系方式)\s*[:：]?/g, ' ')

  // 2. 电话
  ;[out.phone, text] = findPhone(text)
  if (out.address) {
    // 地址行里夹着的电话也要拿掉
    const [p, rest] = findPhone(out.address)
    if (p) {
      out.phone ??= p
      out.address = rest.trim()
    }
  }

  // 3. 运单号与快递公司
  if (!out.trackingNo) {
    const t = findTracking(text)
    if (t) {
      out.trackingNo = t.toUpperCase()
      text = text.replace(t, ' ')
    }
  }
  const named = couriers.find((c) => text.includes(c))
  if (named) {
    out.courier = named
    text = text.replace(named, ' ')
  } else {
    const alias = COURIER_ALIASES.find(([re]) => re.test(text))
    if (alias) {
      out.courier = alias[1]
      text = text.replace(new RegExp(`(${alias[0].source})(快递|速运|速递|物流|配送)?`, alias[0].flags), ' ')
    }
  }
  if (!out.courier && out.trackingNo) {
    const guessed = courierOfTracking(out.trackingNo)
    if (guessed) {
      out.courier = guessed
      out.courierGuessed = true
    }
  }
  text = text.replace(/(快递公司|物流公司|快递|物流|承运)\s*[:：]/g, ' ')

  // 4. 剩下的词：像地址的拼成地址，第一个像人名的是收货人
  const tokens = text
    .split(SEP)
    .map((t) => t.replace(/^[:：\-—]+|[:：\-—]+$/g, ''))
    .filter(Boolean)
  const addressParts: string[] = []
  let guessedName: string | undefined
  for (const t of tokens) {
    // “四川省”“成都市”这样的短词也是地址的一段
    const isAddress = ADDRESS_HINT.test(t) && (t.length >= 4 || /[省市区县镇乡]$/.test(t))
    const isName = !isAddress && (/^[一-龥·]{2,4}$/.test(t) || (NAME_TITLE.test(t) && /^[一-龥]{2,6}$/.test(t)))
    if (isName && !out.consignee && !guessedName) guessedName = t
    else if (isAddress || (addressParts.length && !isName)) addressParts.push(t)
  }
  if (!out.address && addressParts.length) out.address = addressParts.join('')
  // 没有标签的人名只在同时认出电话或地址时才采用，免得把“明天发”这类话当成收货人
  if (guessedName && (out.phone || out.address)) out.consignee = guessedName

  if (out.address) out.address = out.address.replace(/\s+/g, '').replace(/[，,;；。.]+$/, '')
  for (const k of ['consignee', 'phone', 'address', 'courier', 'trackingNo'] as const) if (!out[k]) delete out[k]
  return out
}

export interface TrackingLine {
  /** 原始的一行。 */
  line: string
  courier?: string
  courierGuessed?: boolean
  trackingNo?: string
  /** 去掉运单号和快递公司后剩下的文字，用来对门店。 */
  hint: string
}

/** 批量运单：每行一个门店 + 运单号（可带快递公司），空行跳过。 */
export function parseTrackingLines(input: string, couriers: string[] = []): TrackingLine[] {
  return normalize(input)
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line) => {
      const p = parseDelivery(line, couriers)
      let hint = line
      for (const x of [p.trackingNo, p.courier]) if (x) hint = hint.replace(new RegExp(x, 'i'), ' ')
      for (const [re] of COURIER_ALIASES)
        hint = hint.replace(new RegExp(`(${re.source})(快递|速运|速递|物流|配送)?`, re.flags), ' ')
      hint = hint
        .replace(/(运单号码?|快递单号|物流单号|单号|快递|物流)\s*[:：]?/g, ' ')
        .replace(/[\s,，;；:：|]+/g, ' ')
        .trim()
      return { line, courier: p.courier, courierGuessed: p.courierGuessed, trackingNo: p.trackingNo, hint }
    })
}

/** 门店名称里用于比对的部分：去掉空格、括号备注和结尾的“店 / 站”。 */
const storeKey = (name: string) =>
  name
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .toLowerCase()

/**
 * 按一行里的文字找门店：先看项目编号、合同号，再看名称互相包含；找到唯一的一个才算匹配。
 * 返回候选（0 个 = 没找到，多个 = 需要人选）。
 */
export function matchStores<T extends { name: string; code?: string | null; contract_no?: string | null }>(
  hint: string,
  stores: T[],
): T[] {
  const h = storeKey(hint)
  if (!h) return []
  const byCode = stores.filter(
    (s) => (s.code && h.includes(s.code.toLowerCase())) || (s.contract_no && h.includes(s.contract_no.toLowerCase())),
  )
  if (byCode.length) return byCode
  const exact = stores.filter((s) => storeKey(s.name) === h)
  if (exact.length) return exact
  // 名称常带编号前缀（如“LK-260127 小象成都盛泰站”），粘贴的文字往往只有后半段
  const contains = stores.filter((s) => {
    const k = storeKey(s.name)
    const bare = k.replace(/^[a-z]+-?\d+/, '')
    return k.includes(h) || h.includes(k) || (bare.length >= 3 && h.includes(bare))
  })
  if (contains.length <= 1) return contains
  // 多个门店都包含这段文字时，取名称最短的完全包含者（“盛泰站”不该同时配上“盛泰站二期”）
  const tight = contains.filter((s) => storeKey(s.name).endsWith(h))
  return tight.length === 1 ? tight : contains
}
