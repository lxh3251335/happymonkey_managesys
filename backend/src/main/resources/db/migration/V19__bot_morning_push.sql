-- 企业微信机器人每天早上推送“今日待办”：是否推送、几点推送（HH:mm）。
-- 只推给已绑定、今天有待办的人；推送内容按各人绑定的系统账号计算。
ALTER TABLE bot_config ADD COLUMN push_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE bot_config ADD COLUMN push_time VARCHAR(5) NOT NULL DEFAULT '09:00';
