-- 合同号与发货状态结构化；按合同整笔登记回款，并分摊到门店订单。
ALTER TABLE project ADD COLUMN contract_no VARCHAR(100);
ALTER TABLE project ADD COLUMN shipping_status VARCHAR(30);
CREATE INDEX idx_project_contract ON project(customer_id, contract_no);

CREATE TABLE contract_receipt (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  contract_no VARCHAR(100) NOT NULL,
  kind VARCHAR(30) NOT NULL CHECK(kind IN ('RECEIPT','REFUND')),
  amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
  paid_on DATE NOT NULL,
  reference VARCHAR(150) NOT NULL UNIQUE,
  note VARCHAR(1000),
  reverses_id VARCHAR(36) REFERENCES contract_receipt(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_contract_receipt ON contract_receipt(customer_id, contract_no);

ALTER TABLE cash_entry ADD COLUMN contract_receipt_id VARCHAR(36) REFERENCES contract_receipt(id);
CREATE INDEX idx_cash_contract_receipt ON cash_entry(contract_receipt_id);
