-- 一张发票可以对应多份合同：contract_invoice 的每一行是这张发票记到某一份合同上的金额，
-- 同一张发票的各行发票号码相同、共用 group_id。原来一张发票只对一份合同的记录不受影响（group_id 为空）。
ALTER TABLE contract_invoice ADD COLUMN group_id VARCHAR(36);
CREATE INDEX idx_invoice_group ON contract_invoice(group_id);
