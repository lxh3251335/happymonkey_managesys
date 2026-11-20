-- 发货日期：门店发货状态改为“已发货”时记录日期，用于合同执行跟踪。
ALTER TABLE project ADD COLUMN shipped_on DATE;
