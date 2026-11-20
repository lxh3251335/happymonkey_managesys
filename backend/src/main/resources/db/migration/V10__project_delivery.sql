-- 项目发货与物流：收货信息、预计发货日期、快递公司与运单号。项目台账负责项目执行，合同台账只读展示。
ALTER TABLE project ADD COLUMN expected_ship_on DATE;
ALTER TABLE project ADD COLUMN consignee VARCHAR(60);
ALTER TABLE project ADD COLUMN consignee_phone VARCHAR(40);
ALTER TABLE project ADD COLUMN ship_address VARCHAR(300);
ALTER TABLE project ADD COLUMN courier VARCHAR(40);
ALTER TABLE project ADD COLUMN tracking_no VARCHAR(60);
CREATE INDEX idx_project_tracking ON project(tracking_no);
