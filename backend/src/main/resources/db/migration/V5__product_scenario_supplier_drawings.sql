-- 标准产品：“品牌”改为“应用场景”（原值保留，可改为如“小象前置仓”“冷库”等）；增加默认供应商与主流配置标签。
ALTER TABLE product RENAME COLUMN brand TO scenario;
ALTER TABLE product ALTER COLUMN scenario SET DATA TYPE VARCHAR(60);
ALTER TABLE product ADD COLUMN supplier_id VARCHAR(36) REFERENCES partner(id);
ALTER TABLE product ADD COLUMN mainstream BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_product_scenario ON product(scenario);
CREATE INDEX idx_product_supplier ON product(supplier_id);

-- 产品图纸：文件存放在附件目录，数据库只存元数据；删除为软删除。
CREATE TABLE product_drawing (
  id VARCHAR(36) PRIMARY KEY,
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
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
CREATE INDEX idx_drawing_product ON product_drawing(product_id);
