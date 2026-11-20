-- 业务字典：客户集团（美团、京东、阿里巴巴…）→ 业务线（小象前置仓、快乐猴超市、京东折扣店、盒马鲜生…）。
-- 项目与项目类型的 brand 列保存业务线名称；业务线改名时由系统同步更新。“未指定”为保留值。
CREATE TABLE business_group (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(60) NOT NULL UNIQUE,
  sort INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE business_line (
  id VARCHAR(36) PRIMARY KEY,
  group_id VARCHAR(36) NOT NULL REFERENCES business_group(id),
  name VARCHAR(30) NOT NULL UNIQUE,
  short_name VARCHAR(30) UNIQUE,
  sort INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  note VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO business_group(id,name,sort) VALUES ('grp-meituan','美团',1), ('grp-other','其他客户',99);
INSERT INTO business_line(id,group_id,name,short_name,sort,note) VALUES
  ('line-xiaoxiang','grp-meituan','小象前置仓','小象',1,'美团小象超市前置仓'),
  ('line-kuailehou','grp-meituan','快乐猴超市','快乐猴',2,'美团快乐猴超市'),
  ('line-other','grp-other','其他业务','其他',99,'未归入具体业务线的项目');

-- 历史数据：原品牌值改为业务线名称
UPDATE project SET brand='小象前置仓' WHERE brand='小象';
UPDATE project SET brand='快乐猴超市' WHERE brand='快乐猴';
UPDATE project SET brand='其他业务' WHERE brand='其他';
UPDATE project_type SET brand='小象前置仓' WHERE brand='小象';
UPDATE project_type SET brand='快乐猴超市' WHERE brand='快乐猴';
UPDATE project_type SET brand='其他业务' WHERE brand='其他';
