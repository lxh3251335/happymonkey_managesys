-- 门店附件：客户给的配置清单、我们按清单做的设计图等。挂在门店上，换报价版本、生成合同后都还在。
CREATE TABLE project_attachment (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL REFERENCES project(id),
  category VARCHAR(30) NOT NULL CHECK(category IN ('CONFIG_LIST','DESIGN','OTHER')),
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
CREATE INDEX idx_project_attachment_project ON project_attachment(project_id);
