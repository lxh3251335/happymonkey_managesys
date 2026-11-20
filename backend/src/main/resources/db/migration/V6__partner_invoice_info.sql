-- 客户 / 供应商开票信息：开票抬头、纳税人识别号、注册地址与电话、开户行与账号、发票类型。合同输出时自动带出。
ALTER TABLE partner ADD COLUMN invoice_title VARCHAR(200);
ALTER TABLE partner ADD COLUMN tax_no VARCHAR(30);
ALTER TABLE partner ADD COLUMN address VARCHAR(300);
ALTER TABLE partner ADD COLUMN invoice_phone VARCHAR(60);
ALTER TABLE partner ADD COLUMN bank_name VARCHAR(200);
ALTER TABLE partner ADD COLUMN bank_account VARCHAR(40);
ALTER TABLE partner ADD COLUMN invoice_type VARCHAR(20) CHECK(invoice_type IN ('SPECIAL','NORMAL'));
