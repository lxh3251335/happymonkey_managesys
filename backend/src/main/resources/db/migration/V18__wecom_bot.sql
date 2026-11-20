-- 企业微信机器人。
-- bot_config：连接配置，每种渠道一行（目前只有 WECOM）。secret 只写不读，接口不返回。
-- bot_binding：聊天软件里的人 ↔ 系统账号，一人一个；机器人按绑定的账号和角色办事。
-- bot_bind_code：网页上生成的一次性绑定码，10 分钟内在私聊里发给机器人完成绑定。
CREATE TABLE bot_config (
  channel VARCHAR(20) PRIMARY KEY,
  bot_id VARCHAR(120),
  secret VARCHAR(200),
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  updated_by VARCHAR(80),
  updated_at TIMESTAMP
);
CREATE TABLE bot_binding (
  id VARCHAR(36) PRIMARY KEY,
  channel VARCHAR(20) NOT NULL,
  external_id VARCHAR(200) NOT NULL,
  user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TIMESTAMP,
  UNIQUE(channel, external_id),
  UNIQUE(channel, user_id)
);
CREATE TABLE bot_bind_code (
  code VARCHAR(12) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
  expires_at TIMESTAMP NOT NULL
);
