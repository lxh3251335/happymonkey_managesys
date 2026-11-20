-- 历史导入时给每个客户自动写的备注没有业务含义，占着“备注”一列；清空，手工填写的备注不受影响。
UPDATE partner SET note = NULL WHERE note = '来源于历史客户工作表';
