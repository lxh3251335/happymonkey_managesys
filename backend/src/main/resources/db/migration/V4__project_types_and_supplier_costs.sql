-- 项目类型：门店标准配置清单与对客标准售价（如小象前置仓、快乐猴）。
CREATE TABLE project_type (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  brand VARCHAR(30) NOT NULL,
  note VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE project_type_item (
  id VARCHAR(36) PRIMARY KEY,
  project_type_id VARCHAR(36) NOT NULL REFERENCES project_type(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  position INTEGER NOT NULL,
  quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  unit_price NUMERIC(18,6) CHECK(unit_price >= 0),
  UNIQUE(project_type_id, product_id)
);
INSERT INTO project_type(id,name,brand,note) VALUES
  ('type-xiaoxiang-qzc','小象前置仓','小象','美团小象前置仓标准配置'),
  ('type-kuailehou','快乐猴','快乐猴','美团快乐猴门店标准配置');

-- 供应商成本模板：某供应商对某项目类型各产品的单台成本，拆分为材料、团队人工、财务成本。
CREATE TABLE supplier_template (
  id VARCHAR(36) PRIMARY KEY,
  supplier_id VARCHAR(36) NOT NULL REFERENCES partner(id),
  project_type_id VARCHAR(36) NOT NULL REFERENCES project_type(id),
  note VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(supplier_id, project_type_id)
);
CREATE TABLE supplier_template_item (
  id VARCHAR(36) PRIMARY KEY,
  template_id VARCHAR(36) NOT NULL REFERENCES supplier_template(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  material_cost NUMERIC(18,6) CHECK(material_cost >= 0),
  labor_cost NUMERIC(18,6) CHECK(labor_cost >= 0),
  finance_cost NUMERIC(18,6) CHECK(finance_cost >= 0),
  UNIQUE(template_id, product_id)
);

-- 门店城市与项目类型；报价记录所用供应商与项目类型，明细保留成本拆分。
ALTER TABLE project ADD COLUMN city VARCHAR(50);
ALTER TABLE project ADD COLUMN project_type_id VARCHAR(36) REFERENCES project_type(id);
ALTER TABLE quotation ADD COLUMN supplier_id VARCHAR(36) REFERENCES partner(id);
ALTER TABLE quotation ADD COLUMN project_type_id VARCHAR(36) REFERENCES project_type(id);
ALTER TABLE quotation_item ADD COLUMN material_cost NUMERIC(18,6);
ALTER TABLE quotation_item ADD COLUMN labor_cost NUMERIC(18,6);
ALTER TABLE quotation_item ADD COLUMN finance_cost NUMERIC(18,6);

-- 合同条款随报价模板维护，用于一键输出购销合同。
ALTER TABLE quote_template ADD COLUMN contract_terms VARCHAR(6000);
UPDATE quote_template SET contract_terms='一、质量标准：按国家相关标准及双方确认的技术要求执行，产品出厂前经检验合格。
二、交货：乙方按双方确认的时间将货物送至甲方指定门店，运输费用按报价约定执行。
三、验收：甲方应在收货后 3 日内验收，逾期未提出书面异议视为验收合格。
四、付款：按双方约定的付款节点支付，乙方在收款前开具合规发票。
五、质保：自验收合格之日起质保一年，质保期内非人为损坏免费维修或更换。
六、违约责任：任何一方违反本合同约定，应承担由此给对方造成的直接损失。
七、争议解决：双方协商解决；协商不成的，提交乙方所在地人民法院诉讼解决。
八、本合同一式两份，双方各执一份，经双方签字盖章后生效。';

CREATE INDEX idx_project_city ON project(city);
CREATE INDEX idx_supplier_template_type ON supplier_template(project_type_id);
