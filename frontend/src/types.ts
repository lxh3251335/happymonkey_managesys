export interface User {
  id: string
  username: string
  display_name: string
  role: 'ADMIN' | 'SALES' | 'FINANCE' | 'VIEWER'
  enabled?: boolean
}
export interface Partner {
  id: string
  name: string
  kind: 'CUSTOMER' | 'SUPPLIER'
  contact: string
  phone: string
  note: string
  invoice_title: string | null
  tax_no: string | null
  address: string | null
  invoice_phone: string | null
  bank_name: string | null
  bank_account: string | null
  invoice_type: 'SPECIAL' | 'NORMAL' | null
}
export interface Project {
  id: string
  code: string
  name: string
  brand: string
  customer_id: string
  customer_name: string
  external_id: string | null
  order_date: string | null
  note: string
  quote_count: number
  contract_no: string | null
  shipping_status: string | null
  city: string | null
  project_type_id: string | null
  project_type_name: string | null
}
export interface Product {
  id: string
  /** 应用场景，如小象前置仓、快乐猴、冷库 */
  scenario: string
  supplier_id: string | null
  supplier_name: string | null
  mainstream: boolean
  drawings: number
  name: string
  specification: string
  model: string
  unit: string
  reference_price: number | null
  reference_cost: number | null
}
export interface QuoteItem {
  id: string
  product_id: string
  name: string
  specification: string
  unit: string
  quantity: number
  unit_price: number | null
  unit_cost: number | null
  amount: number | null
  cost: number | null
  source: string
  note: string
  material_cost: number | null
  labor_cost: number | null
  finance_cost: number | null
}
export interface Quote {
  id: string
  code: string
  project_id: string
  project_name: string
  project_code: string
  brand: string
  customer_name: string
  version: number
  status: 'DRAFT' | 'ISSUED' | 'ACCEPTED' | 'HISTORICAL'
  tax_basis: string
  tax_rate: number | null
  valid_until: string | null
  due_date: string | null
  note: string
  amount: number | null
  cost: number | null
  profit: number | null
  known_amount: number
  gross_margin: number | null
  cost_margin: number | null
  complete: boolean
  created_at: string
  items: QuoteItem[]
  received: number
  outstanding: number
  overdue: boolean
  template_name?: string | null
  supplier_id?: string | null
  supplier_name?: string | null
  project_type_id?: string | null
  project_type_name?: string | null
  city?: string | null
  contract_no?: string | null
  customer_contact?: string | null
  customer_phone?: string | null
  customer_invoice_title?: string | null
  customer_tax_no?: string | null
  customer_address?: string | null
  customer_invoice_phone?: string | null
  customer_bank_name?: string | null
  customer_bank_account?: string | null
  /** 仅对客版本：出具时固定的模板快照 */
  template?: PrintTemplate | null
}
export interface Offer {
  id: string
  supplier_name: string
  product_id: string
  product_name: string
  scenario: string
  specification: string
  model: string
  unit_price: number
  tax_basis: string
  tax_rate: number
  freight: number
  min_quantity: number
  lead_days: number
  quoted_on: string
  valid_until: string
  note: string
  eligible: boolean
  landed_total: number
  landed_unit: number
}
export interface CashEntry {
  id: string
  quotation_id: string
  quote_code: string
  project_name: string
  customer_name: string
  kind: string
  amount: number
  paid_on: string
  reference: string
  note: string
  reverses_id: string | null
}
export interface ImportBatch {
  id: string
  detail: string
  created_at: string
}
/** 经营分组：合同额按下单月份，到账额按收款日期（收款减退款）。 */
export interface BizRow {
  name: string
  contract_amount: number
  received: number
  outstanding: number
  collection_rate: number | null
  share: number | null
  profit: number
  gross_margin: number | null
  margin_coverage: number | null
  projects: number
  contracts: number
  avg_per_project: number | null
  quantity: number
}
export interface DashboardTotals extends BizRow {
  confirmed_amount: number
  pending_amount: number
  receivable: number
  overdue_amount: number
  overdue_projects: number
  backlog_amount: number
  backlog_projects: number
  incomplete_cost_projects: number
  current_month: string
  current_month_contract: number
  current_month_received: number
}
/** 月度行附带各产品线的合同额与毛利 */
export interface MonthRow extends BizRow {
  lines: Record<string, BizRow>
}
/** 品牌（业务）× 产品线对比，始终基于全部业务 */
export interface BrandLines {
  brand: string
  group: string
  contract_amount: number
  gross_margin: number | null
  lines: BizRow[]
}
export interface Dashboard {
  totals: DashboardTotals
  monthly: MonthRow[]
  /** 当前筛选下的产品线汇总，固定顺序：电控柜、物联网电箱，其余有数据才出现 */
  line_summary: BizRow[]
  brand: string | null
  group: string | null
  brand_lines: BrandLines[]
  by_customer: BizRow[]
  by_brand: BizRow[]
  by_city: BizRow[]
  by_line: BizRow[]
  /** contract_amount 为该账龄段的未收余额 */
  aging: BizRow[]
  /** contract_amount 为未发货的合同额 */
  backlog: BizRow[]
  concentration: {
    top_customer: string | null
    top1_share: number | null
    top3_share: number | null
    customer_count: number
  }
  projects: number
  customers: number
  recent: Quote[]
  imports: ImportBatch[]
}
export interface ContractItem {
  project_id: string
  project_code: string
  project_name: string
  brand: string
  quote_id: string
  quote_code: string
  status: Quote['status']
  shipping_status: string | null
  order_date: string | null
  due_date: string | null
  amount: number
  received: number
  outstanding: number
}
export interface Contract {
  customer_id: string
  customer_name: string
  contract_no: string | null
  contract_key: string
  brands: string[]
  projects: number
  shipped: number
  confirmed_projects: number
  confirmation: 'PENDING' | 'PARTIAL' | 'CONFIRMED'
  amount: number
  confirmed_amount: number
  received: number
  outstanding: number
  overdue: number
  collection_rate: number | null
  order_date: string | null
  due_date: string | null
  items: ContractItem[]
  attachments: number
}
export interface ContractReceipt {
  id: string
  customer_id: string
  customer_name: string
  contract_no: string
  kind: 'RECEIPT' | 'REFUND'
  amount: number
  paid_on: string
  reference: string
  note: string
  reverses_id: string | null
  allocations: number
  refunded: number
}
export interface Audit {
  id: string
  actor: string
  action: string
  entity_id: string
  detail: string
  created_at: string
}
export interface PrintTemplate {
  name: string
  company_name: string
  title: string
  address: string | null
  contact: string | null
  phone: string | null
  email: string | null
  tax_no: string | null
  bank_name: string | null
  bank_account: string | null
  terms: string | null
  footer: string | null
  contract_terms: string | null
}
export interface QuoteTemplate extends PrintTemplate {
  id: string
  is_default: boolean
  active: boolean
  used: number
  updated_at: string
}
export interface ContractAttachment {
  id: string
  customer_id: string
  contract_no: string
  category: 'CONTRACT' | 'STAMPED_QUOTE' | 'DELIVERY' | 'INVOICE' | 'OTHER'
  file_name: string
  extension: string
  size_bytes: number
  sha256: string
  note: string | null
  uploaded_by: string
  created_at: string
}
export interface ProjectTypeItem {
  id: string
  product_id: string
  position: number
  quantity: number
  unit_price: number | null
  name: string
  specification: string
  model: string
  unit: string
}
export interface ProjectType {
  id: string
  name: string
  brand: string
  note: string | null
  active: boolean
  supplier_templates: number
  projects: number
  items: ProjectTypeItem[]
}
export interface SupplierTemplateItem {
  product_id: string
  name: string
  specification: string
  unit: string
  material_cost: number | null
  labor_cost: number | null
  finance_cost: number | null
  unit_cost: number | null
}
export interface SupplierTemplate {
  id: string
  supplier_id: string
  supplier_name: string
  project_type_id: string
  project_type_name: string
  note: string | null
  active: boolean
  updated_at: string
  items: SupplierTemplateItem[]
}
export interface PrefillLine {
  product_id: string
  name: string
  specification: string
  unit: string
  quantity: number
  unit_price: number | null
  material_cost: number | null
  labor_cost: number | null
  finance_cost: number | null
  unit_cost: number | null
}
export interface ProductDrawing {
  id: string
  product_id: string
  file_name: string
  extension: string
  size_bytes: number
  note: string | null
  uploaded_by: string
  created_at: string
}
export interface BusinessLine {
  id: string
  group_id: string
  name: string
  short_name: string | null
  sort: number
  active: boolean
  note: string | null
  projects: number
  project_types: number
}
export interface BusinessGroup {
  id: string
  name: string
  sort: number
  active: boolean
  lines: BusinessLine[]
}
