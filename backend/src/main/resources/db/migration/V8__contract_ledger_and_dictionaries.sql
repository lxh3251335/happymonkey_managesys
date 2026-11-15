-- 我方签约主体：公司可能有多个签合同的单位（不同公司抬头、税号、开户行）。报价与合同选择签约主体，打印时使用其抬头与收款信息。
CREATE TABLE company (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(200) NOT NULL UNIQUE,
  short_name VARCHAR(30) UNIQUE,
  tax_no VARCHAR(30),
  legal_rep VARCHAR(60),
  address VARCHAR(300),
  phone VARCHAR(60),
  bank_name VARCHAR(200),
  bank_account VARCHAR(40),
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort INTEGER NOT NULL DEFAULT 0,
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 以默认报价模板的公司抬头作为第一个签约主体
INSERT INTO company(id,name,tax_no,address,phone,bank_name,bank_account,is_default,sort)
SELECT 'company-default', company_name, tax_no, address, phone, bank_name, bank_account, TRUE, 1
FROM quote_template WHERE id='default-quote-template';

ALTER TABLE quotation ADD COLUMN company_id VARCHAR(36) REFERENCES company(id);

-- 应用场景字典：标准产品的 scenario 列保存场景名称，场景改名时同步更新。
CREATE TABLE scenario (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(60) NOT NULL UNIQUE,
  sort INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 城市字典：项目 city 列保存城市名称。aliases 为区县、镇等别名（逗号分隔），用于从门店名称识别城市。
CREATE TABLE city (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(50) NOT NULL UNIQUE,
  province VARCHAR(30),
  aliases VARCHAR(1000),
  sort INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 合同信息：门店订单按“客户 + 合同号”归为一份合同，这里补充合同本身的要素。
-- contract_no 与回款、附件一致：有合同号为合同号，未填合同号的单店订单为“#项目编号”。
CREATE TABLE contract (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  contract_no VARCHAR(100) NOT NULL,
  company_id VARCHAR(36) REFERENCES company(id),
  title VARCHAR(200),
  content VARCHAR(2000),
  signed_on DATE,
  signed_amount NUMERIC(18,2) CHECK(signed_amount >= 0),
  payment_terms VARCHAR(1000),
  start_on DATE,
  end_on DATE,
  warranty_months INTEGER CHECK(warranty_months >= 0),
  retention_amount NUMERIC(18,2) CHECK(retention_amount >= 0),
  owner_id VARCHAR(36) REFERENCES app_user(id),
  customer_signer VARCHAR(100),
  status VARCHAR(20) NOT NULL DEFAULT 'SIGNED' CHECK(status IN ('DRAFT','SIGNED','COMPLETED','TERMINATED')),
  archived BOOLEAN NOT NULL DEFAULT FALSE,
  archive_no VARCHAR(100),
  note VARCHAR(2000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(customer_id, contract_no)
);
CREATE INDEX idx_contract_company ON contract(company_id);
CREATE INDEX idx_contract_owner ON contract(owner_id);

-- 销售负责人：每个门店项目归属一名销售，销售只能查看和办理自己负责的项目、报价与合同。
ALTER TABLE project ADD COLUMN owner_id VARCHAR(36) REFERENCES app_user(id);
CREATE INDEX idx_project_owner ON project(owner_id);

-- 合同变更：已签订合同的内容修改须经管理员批准；管理员直接修改与草稿修改也记录在此，形成完整的修改轨迹。
-- changes 为 JSON：{字段: [原值, 新值]}。
CREATE TABLE contract_change (
  id VARCHAR(36) PRIMARY KEY,
  contract_id VARCHAR(36) NOT NULL REFERENCES contract(id),
  status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','DIRECT')),
  changes VARCHAR(8000) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  requested_by VARCHAR(80) NOT NULL,
  requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by VARCHAR(80),
  reviewed_at TIMESTAMP,
  review_note VARCHAR(500)
);
CREATE INDEX idx_contract_change ON contract_change(contract_id, status);

-- 合同实际成本：采购、安装人工、运输等实际发生的成本，用于核算合同实际毛利。
CREATE TABLE contract_cost (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  contract_no VARCHAR(100) NOT NULL,
  category VARCHAR(20) NOT NULL CHECK(category IN ('MATERIAL','LABOR','FREIGHT','INSTALL','FINANCE','OTHER')),
  supplier_id VARCHAR(36) REFERENCES partner(id),
  amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
  incurred_on DATE NOT NULL,
  reference VARCHAR(150),
  note VARCHAR(1000),
  created_by VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  deleted_by VARCHAR(80),
  delete_reason VARCHAR(300)
);
CREATE INDEX idx_cost_contract ON contract_cost(customer_id, contract_no);

-- 开票记录：蓝字发票与红字冲销。错误登记可删除（软删除，留审计）。
CREATE TABLE contract_invoice (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  contract_no VARCHAR(100) NOT NULL,
  company_id VARCHAR(36) REFERENCES company(id),
  kind VARCHAR(10) NOT NULL CHECK(kind IN ('INVOICE','RED')),
  invoice_no VARCHAR(60) NOT NULL,
  invoice_type VARCHAR(10) CHECK(invoice_type IN ('SPECIAL','NORMAL')),
  issued_on DATE NOT NULL,
  amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
  tax_rate NUMERIC(8,4) CHECK(tax_rate >= 0 AND tax_rate <= 1),
  reverses_id VARCHAR(36) REFERENCES contract_invoice(id),
  note VARCHAR(1000),
  created_by VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  deleted_by VARCHAR(80),
  delete_reason VARCHAR(300)
);
CREATE INDEX idx_invoice_contract ON contract_invoice(customer_id, contract_no);
CREATE INDEX idx_invoice_no ON contract_invoice(invoice_no);
