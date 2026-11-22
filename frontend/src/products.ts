import type { Product } from './types'

/**
 * 选产品时显示的一行文字：应用场景 / 名称 / 规格 / 型号 · 供应商。
 * 同一种产品不同供应商是不同的标准品（价格不一样），所以供应商一定要带上。
 */
export const productLabel = (p: Product) =>
  `${p.scenario} / ${p.name}${p.specification ? ' / ' + p.specification : ''}${p.model ? ' / ' + p.model : ''}` +
  (p.supplier_name ? ` · ${p.supplier_name}` : ' · 未指定供应商')
