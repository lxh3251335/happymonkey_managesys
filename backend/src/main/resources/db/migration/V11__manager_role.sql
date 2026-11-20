-- 新增“管理层”角色（MANAGER）：查看全部经营与成本数据、审批合同变更、分配负责人，不负责系统配置。
-- 角色列原有 CHECK 约束名称因数据库而异，这里重建该列以替换约束。
ALTER TABLE app_user ADD COLUMN role_v2 VARCHAR(20);
UPDATE app_user SET role_v2 = role;
ALTER TABLE app_user DROP COLUMN role;
ALTER TABLE app_user RENAME COLUMN role_v2 TO role;
ALTER TABLE app_user ALTER COLUMN role SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN','MANAGER','SALES','FINANCE','VIEWER'));
