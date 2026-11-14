export interface User {
  id: string
  username: string
  display_name: string
  role: 'ADMIN' | 'SALES' | 'FINANCE' | 'VIEWER'
}
export interface Partner {
  id: string
  name: string
  kind: 'CUSTOMER' | 'SUPPLIER'
  contact: string
  phone: string
  note: string
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
}
export interface Product {
  id: string
  brand: string
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
}
export interface Offer {
  id: string
  supplier_name: string
  product_id: string
  product_name: string
  brand: string
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
export interface Dashboard {
  totals: DashboardTotals
  monthly: BizRow[]
  by_customer: BizRow[]
  by_brand: BizRow[]
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
