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
export interface Breakdown {
  name: string
  amount: number
  count: number
}
export interface ImportBatch {
  id: string
  detail: string
  created_at: string
}
export interface Dashboard {
  projects: number
  customers: number
  quotes: number
  amount: number
  incomplete: number
  complete_quotes: number
  profit: number
  cost: number
  gross_margin: number | null
  receivable: number
  received: number
  accepted: number
  overdue: number
  recent: Quote[]
  customers_breakdown: Breakdown[]
  brands_breakdown: Breakdown[]
  imports: ImportBatch[]
}
export interface Audit {
  id: string
  actor: string
  action: string
  entity_id: string
  detail: string
  created_at: string
}
