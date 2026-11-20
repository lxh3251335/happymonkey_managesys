-- 报价模板：公司抬头、联系方式、收款信息与标准条款。出具报价时把模板内容快照到报价，之后修改模板不影响已出具的报价单。
CREATE TABLE quote_template (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  company_name VARCHAR(200) NOT NULL,
  title VARCHAR(100) NOT NULL DEFAULT '客户报价单',
  address VARCHAR(300),
  contact VARCHAR(100),
  phone VARCHAR(60),
  email VARCHAR(120),
  tax_no VARCHAR(60),
  bank_name VARCHAR(200),
  bank_account VARCHAR(60),
  terms VARCHAR(4000),
  footer VARCHAR(500),
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO quote_template(id,name,company_name,title,terms,is_default) VALUES (
  'default-quote-template',
  '标准报价',
  '天商',
  '客户报价单',
  '1. 本报价有效期以报价单所列日期为准，逾期需重新确认。
2. 交货周期、运输范围及安装费用以双方书面确认为准。
3. 付款方式以合同约定为准。',
  TRUE
);

ALTER TABLE quotation ADD COLUMN template_id VARCHAR(36) REFERENCES quote_template(id);
ALTER TABLE quotation ADD COLUMN template_snapshot VARCHAR(8000);

-- 合同附件：合同扫描件、盖章报价单等确认凭据。文件存放在服务器目录，数据库只保存元数据和校验值；删除为软删除。
CREATE TABLE contract_attachment (
  id VARCHAR(36) PRIMARY KEY,
  customer_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  contract_no VARCHAR(100) NOT NULL,
  category VARCHAR(30) NOT NULL CHECK(category IN ('CONTRACT','STAMPED_QUOTE','DELIVERY','INVOICE','OTHER')),
  file_name VARCHAR(255) NOT NULL,
  extension VARCHAR(10) NOT NULL,
  size_bytes BIGINT NOT NULL CHECK(size_bytes > 0),
  sha256 VARCHAR(64) NOT NULL,
  storage_path VARCHAR(300) NOT NULL,
  note VARCHAR(500),
  uploaded_by VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP,
  deleted_by VARCHAR(80)
);
CREATE INDEX idx_attachment_contract ON contract_attachment(customer_id, contract_no);
