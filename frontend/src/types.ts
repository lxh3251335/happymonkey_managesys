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
  owner_id: string | null
  owner_name: string | null
  shipped_on: string | null
  expected_ship_on: string | null
  consignee: string | null
  consignee_phone: string | null
  ship_address: string | null
  courier: string | null
  tracking_no: string | null
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
  customer_id?: string
  owner_id?: string | null
  owner_name?: string | null
  company_id?: string | null
  company_name?: string | null
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
  supplier_id: string
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
  /** 按销售负责人汇总的业绩 */
  by_owner: BizRow[]
  /** 合同执行概况（当前筛选范围） */
  execution: {
    contracts: number
    draft: number
    signed: number
    completed: number
    terminated: number
    terminated_amount: number
    invoiced: number
    uninvoiced: number
    invoiced_unreceived: number
    actual_cost: number
    actual_cost_contracts: number
    unarchived: number
    pending_changes: number
  }
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
  city: string | null
  owner_name: string | null
  quote_id: string
  quote_code: string
  status: Quote['status']
  shipping_status: string | null
  shipped_on: string | null
  expected_ship_on: string | null
  courier: string | null
  tracking_no: string | null
  order_date: string | null
  due_date: string | null
  amount: number
  received: number
  outstanding: number
}
export type ContractStatus = 'DRAFT' | 'SIGNED' | 'COMPLETED' | 'TERMINATED'
export interface Contract {
  id: string | null
  customer_id: string
  customer_name: string
  contract_no: string | null
  contract_key: string
  status: ContractStatus
  /** 是否已有合同记录（历史合同只有门店订单） */
  recorded: boolean
  title: string | null
  content: string | null
  signed_on: string | null
  company_id: string | null
  company_name: string | null
  owner_id: string | null
  owner_name: string | null
  archived: boolean
  pending_changes: number
  brands: string[]
  cities: string[]
  projects: number
  shipped: number
  confirmed_projects: number
  confirmation: 'PENDING' | 'PARTIAL' | 'CONFIRMED'
  /** 门店订单合计 */
  order_amount: number
  signed_amount: number | null
  /** 合同金额：有签约金额用签约金额，否则为门店订单合计 */
  amount: number
  amount_gap: number | null
  confirmed_amount: number
  received: number
  outstanding: number
  overdue: number
  overdue_days: number
  collection_rate: number | null
  invoiced: number
  uninvoiced: number
  invoiced_unreceived: number
  invoice_status: 'NONE' | 'PARTIAL' | 'FULL'
  last_invoice_on: string | null
  last_receipt_on: string | null
  actual_cost: number
  estimated_cost: number | null
  cost: number | null
  cost_source: 'ACTUAL' | 'ESTIMATED' | 'INCOMPLETE'
  profit: number | null
  gross_margin: number | null
  order_date: string | null
  due_date: string | null
  items: ContractItem[]
  attachments: number
}
export interface ContractInfo {
  id: string
  status: ContractStatus
  company_id: string | null
  company_name: string | null
  title: string | null
  content: string | null
  signed_on: string | null
  signed_amount: number | null
  payment_terms: string | null
  start_on: string | null
  end_on: string | null
  warranty_months: number | null
  retention_amount: number | null
  owner_id: string | null
  owner_name: string | null
  customer_signer: string | null
  archived: boolean
  archive_no: string | null
  note: string | null
  updated_at: string
}
export interface FieldChange {
  field: string
  label: string
  before: string | null
  after: string | null
}
export interface ContractChange {
  id: string
  contract_id: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'DIRECT'
  changes: FieldChange[]
  reason: string
  requested_by: string
  requester_name: string | null
  requested_at: string
  reviewed_by: string | null
  reviewer_name?: string | null
  reviewed_at: string | null
  review_note: string | null
  customer_id?: string
  contract_no?: string
  customer_name?: string
  title?: string | null
}
export interface ContractEvent {
  id: string
  actor: string
  actor_name: string | null
  action: string
  entity_id: string
  detail: string
  created_at: string
}
export interface ContractInvoice {
  id: string
  customer_id: string
  customer_name: string
  contract_no: string
  company_name: string | null
  kind: 'INVOICE' | 'RED'
  invoice_no: string
  invoice_type: 'SPECIAL' | 'NORMAL' | null
  issued_on: string
  amount: number
  tax_rate: number | null
  reverses_id: string | null
  reverses_no: string | null
  reversed: number
  note: string | null
  created_by: string
}
export interface ContractCost {
  id: string
  category: string
  supplier_id: string | null
  supplier_name: string | null
  amount: number
  incurred_on: string
  reference: string | null
  note: string | null
  created_by: string
}
export interface ContractDetail extends Contract {
  info: ContractInfo | null
  changes: ContractChange[]
  events: ContractEvent[]
  receipts: ContractReceipt[]
}
export interface Scenario {
  id: string
  name: string
  sort: number
  active: boolean
  note: string | null
  products: number
}
export interface City {
  id: string
  name: string
  province: string | null
  aliases: string | null
  sort: number
  active: boolean
  projects: number
}
export interface Company {
  id: string
  name: string
  short_name: string | null
  tax_no: string | null
  legal_rep: string | null
  address: string | null
  phone: string | null
  bank_name: string | null
  bank_account: string | null
  is_default: boolean
  active: boolean
  sort: number
  note: string | null
  contracts: number
  quotes: number
}
export interface Owner {
  id: string
  display_name: string
  role: string
  enabled: boolean
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
/** 回款流水：合同收付款（含门店分摊）或未关联合同的历史门店流水。 */
export interface ReceiptRow {
  id: string
  source: 'CONTRACT' | 'STORE'
  kind: 'RECEIPT' | 'REFUND'
  amount: number
  paid_on: string
  reference: string
  note: string | null
  reverses_id: string | null
  refunded: number
  customer_id: string
  customer_name: string
  contract_key: string
  project_name?: string
  allocations: { project_name: string; project_code: string; amount: number; reference: string }[]
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
