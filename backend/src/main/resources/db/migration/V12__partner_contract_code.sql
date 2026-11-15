-- 客户合同代码：合同号按“前缀+年份-客户代码-序号”自动编号（如 TJ2026-SHHLZY-109）。
-- 留空时从该客户已有合同号中识别；新客户没有历史合同时需要填写。
ALTER TABLE partner ADD COLUMN contract_code VARCHAR(20);
