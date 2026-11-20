-- 回款单：客户一次付款常常对应多份合同。回款单记录银行到账的一笔钱，下面挂若干份合同回款；
-- 还没分到合同上的余额留作“待分配”，以后可以继续分。原来按单份合同登记的回款不受影响（batch_id 为空）。
CREATE TABLE receipt_batch (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  amount NUMERIC(18,2) NOT NULL CHECK(amount > 0),
  paid_on DATE NOT NULL,
  reference VARCHAR(120) NOT NULL UNIQUE,
  payer VARCHAR(200),
  note VARCHAR(1000),
  source_text VARCHAR(4000),
  created_by VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_receipt_batch_customer ON receipt_batch(customer_id);
ALTER TABLE contract_receipt ADD COLUMN batch_id VARCHAR(36) REFERENCES receipt_batch(id);
CREATE INDEX idx_contract_receipt_batch ON contract_receipt(batch_id);
