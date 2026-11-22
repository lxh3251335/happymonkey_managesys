import type { Bundle } from './types'

/**
 * 明细里的标准组合（像套餐）：报价明细、项目类型标准配置里，同一组的行属于同一个组合。
 * 行的数量是总数量 = 每套数量 × 套数；改套数时组内各行数量跟着变。组合不单独定价，组小计 = 组内各行金额之和。
 */

/** 编辑态的分组信息。 */
export interface GroupDraft {
  no: number
  bundleId: string
  bundleName: string
  sets: number
  perSet: number
}
export interface Grouped {
  quantity: number
  group?: GroupDraft
}
/** 后端返回的行里的组合字段（报价明细、项目类型配置）。 */
export interface BundleFields {
  bundle_id?: string | null
  bundle_name?: string | null
  bundle_sets?: number | null
  bundle_group?: number | null
}

export function groupOf(row: BundleFields & { quantity: number }): GroupDraft | undefined {
  if (!row.bundle_id || row.bundle_group == null) return undefined
  const sets = Number(row.bundle_sets) || 1
  return {
    no: Number(row.bundle_group),
    bundleId: row.bundle_id,
    bundleName: row.bundle_name || '组合',
    sets,
    perSet: Number(row.quantity) / sets,
  }
}

export type DisplayRow<T> =
  | { kind: 'group'; key: string; no: number; name: string; sets: number; lines: T[] }
  | { kind: 'line'; key: string; line: T }

/** 排成显示顺序：同一组的行放在一起，前面加一行组标题；不在组合里的行保持原顺序。 */
export function displayRows<T extends Grouped>(lines: T[]): DisplayRow<T>[] {
  const out: DisplayRow<T>[] = []
  const seen = new Set<number>()
  lines.forEach((line, idx) => {
    const g = line.group
    if (!g) {
      out.push({ kind: 'line', key: `l${idx}`, line })
      return
    }
    if (seen.has(g.no)) return
    seen.add(g.no)
    const members = lines.filter((l) => l.group?.no === g.no)
    out.push({ kind: 'group', key: `g${g.no}`, no: g.no, name: g.bundleName, sets: g.sets, lines: members })
    for (const m of members) out.push({ kind: 'line', key: `l${lines.indexOf(m)}`, line: m })
  })
  return out
}

export const nextGroupNo = <T extends Grouped>(lines: T[]) =>
  lines.reduce((m, l) => Math.max(m, l.group?.no || 0), 0) + 1

/** 改套数：组内每行数量 = 每套数量 × 套数。 */
export function setSets<T extends Grouped>(lines: T[], no: number, sets: number | undefined) {
  const s = Math.max(1, Math.round(sets || 1))
  for (const l of lines)
    if (l.group?.no === no) {
      l.group.sets = s
      l.quantity = Math.max(1, Math.round(l.group.perSet * s))
    }
}
/** 组内某行直接改了数量：按当前套数反推每套数量。 */
export function syncPerSet<T extends Grouped>(line: T) {
  if (line.group) line.group.perSet = line.quantity / (line.group.sets || 1)
}

/** 组合展开成明细行（不含价格），套数 1。 */
export function linesOfBundle(b: Bundle, no: number) {
  return b.items.map((i) => ({
    productId: i.product_id,
    quantity: Number(i.quantity),
    group: { no, bundleId: b.id, bundleName: b.name, sets: 1, perSet: Number(i.quantity) } as GroupDraft,
  }))
}

/** 提交给后端的分组字段。 */
export const groupPayload = (g?: GroupDraft) => (g ? { no: g.no, bundleId: g.bundleId, sets: g.sets } : null)

/** 组标题行跨整行：给 el-table 的 span-method。columns 是表格的列数。 */
export function spanGroups(columns: () => number) {
  return ({ row, columnIndex }: { row: DisplayRow<unknown>; columnIndex: number }) =>
    row.kind === 'group' ? (columnIndex === 0 ? [1, columns()] : [0, 0]) : [1, 1]
}

export const rowClass = ({ row }: { row: DisplayRow<Grouped> }) =>
  row.kind === 'group' ? 'group-row' : row.line.group ? 'in-group' : ''

/** 一行文字说明配置：组合按“名称×套数”，单品按“名称×数量”。 */
export function configText<T extends Grouped & { name: string }>(items: T[]) {
  const parts: string[] = []
  for (const r of displayRows(items)) {
    if (r.kind === 'group') parts.push(`${r.name}×${r.sets}套`)
    else if (!r.line.group) parts.push(`${r.line.name}×${Number(r.line.quantity)}`)
  }
  return parts.join('、')
}
