CREATE TABLE app_user (
  id VARCHAR(36) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK(role IN ('ADMIN','SALES','FINANCE','VIEWER')),
  enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE partner (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL,
  kind VARCHAR(20) NOT NULL CHECK(kind IN ('CUSTOMER','SUPPLIER')),
  contact VARCHAR(100), phone VARCHAR(60), note VARCHAR(1000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(name, kind)
);
CREATE TABLE project (
  id VARCHAR(36) PRIMARY KEY, code VARCHAR(100) NOT NULL UNIQUE,
  name VARCHAR(300) NOT NULL, brand VARCHAR(30) NOT NULL,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  stage VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
  external_id VARCHAR(150) UNIQUE, order_date DATE, note VARCHAR(2000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE product (
  id VARCHAR(36) PRIMARY KEY, brand VARCHAR(30) NOT NULL,
  name VARCHAR(200) NOT NULL, specification VARCHAR(200) NOT NULL,
  model VARCHAR(100) NOT NULL DEFAULT '', unit VARCHAR(30) NOT NULL DEFAULT '台',
  UNIQUE(brand, name, specification, model)
);
CREATE TABLE quotation (
  id VARCHAR(36) PRIMARY KEY, code VARCHAR(100) NOT NULL UNIQUE,
  project_id VARCHAR(36) NOT NULL REFERENCES project(id),
  version INTEGER NOT NULL DEFAULT 1,
  status VARCHAR(30) NOT NULL CHECK(status IN ('DRAFT','ISSUED','ACCEPTED','HISTORICAL')),
  tax_basis VARCHAR(30) NOT NULL CHECK(tax_basis IN ('INCLUDED','EXCLUDED','UNKNOWN')),
  tax_rate NUMERIC(8,4), valid_until DATE, due_date DATE, note VARCHAR(2000),
  accepted_project VARCHAR(36) UNIQUE REFERENCES project(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(project_id, version)
);
CREATE TABLE quotation_item (
  id VARCHAR(36) PRIMARY KEY, quotation_id VARCHAR(36) NOT NULL REFERENCES quotation(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id), position INTEGER NOT NULL,
  name VARCHAR(200) NOT NULL, specification VARCHAR(200) NOT NULL,
  unit VARCHAR(30) NOT NULL, quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  unit_price NUMERIC(18,6), unit_cost NUMERIC(18,6),
  amount NUMERIC(18,2), cost NUMERIC(18,2),
  source VARCHAR(1000), note VARCHAR(2000)
);
CREATE TABLE supplier_offer (
  id VARCHAR(36) PRIMARY KEY, supplier_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  unit_price NUMERIC(18,6) NOT NULL CHECK(unit_price >= 0),
  tax_basis VARCHAR(30) NOT NULL CHECK(tax_basis IN ('INCLUDED','EXCLUDED')),
  tax_rate NUMERIC(8,4) NOT NULL CHECK(tax_rate >= 0 AND tax_rate <= 1),
  freight NUMERIC(18,2) NOT NULL CHECK(freight >= 0),
  min_quantity NUMERIC(18,3) NOT NULL CHECK(min_quantity > 0),
  lead_days INTEGER NOT NULL CHECK(lead_days >= 0),
  quoted_on DATE NOT NULL, valid_until DATE NOT NULL,
  note VARCHAR(1000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK(valid_until >= quoted_on)
);
CREATE TABLE cash_entry (
  id VARCHAR(36) PRIMARY KEY, quotation_id VARCHAR(36) NOT NULL REFERENCES quotation(id),
  kind VARCHAR(30) NOT NULL CHECK(kind IN ('RECEIPT','REFUND')),
  amount NUMERIC(18,2) NOT NULL CHECK(amount > 0), paid_on DATE NOT NULL,
  reference VARCHAR(150) NOT NULL UNIQUE, note VARCHAR(1000),
  reverses_id VARCHAR(36) REFERENCES cash_entry(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE audit_log (
  id VARCHAR(36) PRIMARY KEY, actor VARCHAR(80) NOT NULL, action VARCHAR(100) NOT NULL,
  entity_id VARCHAR(100) NOT NULL, detail VARCHAR(2000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE import_batch (
  id VARCHAR(100) PRIMARY KEY, detail VARCHAR(4000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_project_customer ON project(customer_id);
CREATE INDEX idx_quote_project ON quotation(project_id);
CREATE INDEX idx_item_quote ON quotation_item(quotation_id);
CREATE INDEX idx_offer_product ON supplier_offer(product_id);
CREATE INDEX idx_cash_quote ON cash_entry(quotation_id);
