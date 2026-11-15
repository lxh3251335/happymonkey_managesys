-- 报价跟进：报价增加“未成交”结局（LOST），记录原因与关闭前的状态，便于恢复跟进和统计成交率。
-- 状态列原有 CHECK 约束名称因数据库而异，这里重建该列以替换约束（做法同 V11）。
ALTER TABLE quotation ADD COLUMN status_v2 VARCHAR(30);
UPDATE quotation SET status_v2 = status;
ALTER TABLE quotation DROP COLUMN status;
ALTER TABLE quotation RENAME COLUMN status_v2 TO status;
ALTER TABLE quotation ALTER COLUMN status SET NOT NULL;
ALTER TABLE quotation ADD CONSTRAINT ck_quotation_status CHECK (status IN ('DRAFT','ISSUED','ACCEPTED','HISTORICAL','LOST'));
ALTER TABLE quotation ADD COLUMN lost_reason VARCHAR(300);
ALTER TABLE quotation ADD COLUMN lost_from VARCHAR(30);
ALTER TABLE quotation ADD COLUMN lost_at TIMESTAMP;
CREATE INDEX idx_quote_status ON quotation(status);

-- 客户报价模板：某客户在某项目类型下的产品、数量与协议售价。
-- 新建报价时优先于项目类型的标准配置；成本不在此维护，仍由供应商成本模板核算。
CREATE TABLE customer_template (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  project_type_id VARCHAR(36) NOT NULL REFERENCES project_type(id),
  note VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(customer_id, project_type_id)
);
CREATE TABLE customer_template_item (
  id VARCHAR(36) PRIMARY KEY,
  template_id VARCHAR(36) NOT NULL REFERENCES customer_template(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  position INTEGER NOT NULL,
  quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  unit_price NUMERIC(18,6) CHECK(unit_price >= 0),
  UNIQUE(template_id, product_id)
);
CREATE INDEX idx_customer_template_type ON customer_template(project_type_id);
