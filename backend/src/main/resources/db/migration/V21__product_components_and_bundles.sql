-- 标准品的电气件清单（像菜品的原材料）：只记录组成，作为技术资料，不参与报价和成本。
CREATE TABLE product_component (
  id VARCHAR(36) PRIMARY KEY,
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  position INTEGER NOT NULL,
  name VARCHAR(200) NOT NULL,
  model VARCHAR(120),
  brand VARCHAR(80),
  quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  unit VARCHAR(20),
  note VARCHAR(300)
);
CREATE INDEX idx_product_component_product ON product_component(product_id);

-- 标准组合（像套餐）：按应用场景把几个标准品和数量打包。组合价 = 里面标准品的单价 × 数量之和，不单独定价。
CREATE TABLE bundle (
  id VARCHAR(36) PRIMARY KEY,
  scenario VARCHAR(60) NOT NULL,
  name VARCHAR(120) NOT NULL,
  note VARCHAR(1000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(scenario, name)
);
CREATE TABLE bundle_item (
  id VARCHAR(36) PRIMARY KEY,
  bundle_id VARCHAR(36) NOT NULL REFERENCES bundle(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  position INTEGER NOT NULL,
  quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  UNIQUE(bundle_id, product_id)
);
CREATE INDEX idx_bundle_item_bundle ON bundle_item(bundle_id);

-- 报价明细可以按组合分组：同一组的明细 bundle_group 相同，记下组合（名称留快照）和套数。
-- 明细的数量是总数量（每套数量 × 套数）；不属于组合的明细这些列为空。
ALTER TABLE quotation_item ADD COLUMN bundle_id VARCHAR(36) REFERENCES bundle(id);
ALTER TABLE quotation_item ADD COLUMN bundle_name VARCHAR(120);
ALTER TABLE quotation_item ADD COLUMN bundle_sets NUMERIC(18,3);
ALTER TABLE quotation_item ADD COLUMN bundle_group INTEGER;

-- 项目类型的标准配置也可以放组合。原表“同一产品只能出现一次”的约束要去掉（同一产品可以在不同组合里），
-- 约束名在各数据库里不同，所以重建这张表并搬数据。
CREATE TABLE project_type_line (
  id VARCHAR(36) PRIMARY KEY,
  project_type_id VARCHAR(36) NOT NULL REFERENCES project_type(id),
  product_id VARCHAR(36) NOT NULL REFERENCES product(id),
  position INTEGER NOT NULL,
  quantity NUMERIC(18,3) NOT NULL CHECK(quantity > 0),
  unit_price NUMERIC(18,6) CHECK(unit_price >= 0),
  bundle_id VARCHAR(36) REFERENCES bundle(id),
  bundle_name VARCHAR(120),
  bundle_sets NUMERIC(18,3),
  bundle_group INTEGER
);
INSERT INTO project_type_line(id,project_type_id,product_id,position,quantity,unit_price)
  SELECT id,project_type_id,product_id,position,quantity,unit_price FROM project_type_item;
DROP TABLE project_type_item;
ALTER TABLE project_type_line RENAME TO project_type_item;
CREATE INDEX idx_project_type_item_type ON project_type_item(project_type_id);
