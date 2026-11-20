-- 报价“已被取代”状态（SUPERSEDED）：门店已有已确认报价时，新版本经确认后取代旧版本，旧版本保留备查。
ALTER TABLE quotation DROP CONSTRAINT ck_quotation_status;
ALTER TABLE quotation ADD CONSTRAINT ck_quotation_status CHECK (status IN ('DRAFT','ISSUED','ACCEPTED','HISTORICAL','LOST','SUPERSEDED'));
ALTER TABLE quotation ADD COLUMN superseded_by VARCHAR(36) REFERENCES quotation(id);
