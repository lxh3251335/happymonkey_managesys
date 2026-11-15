<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import { api, post, canSeeCost } from '../api'
import type { Offer, Partner, Product, ProjectType, SupplierTemplate } from '../types'
import { day, money, percent, taxName, today } from '../utils'

const emit = defineEmits<{ configure: [string] }>()
const templates = ref<SupplierTemplate[]>([]),
  types = ref<ProjectType[]>([]),
  suppliers = ref<Partner[]>([]),
  offers = ref<Offer[]>([]),
  products = ref<Product[]>([]),
  extraProductId = ref(''),
  dialog = ref(false),
  busy = ref(false)
interface Line {
  productId: string
  name: string
  specification: string
  quantity: number
  unitPrice: number | null
  materialCost: number | undefined
  laborCost: number | undefined
  financeCost: number | undefined
  /** 标准配置以外的产品：不计入整套成本，业务员在报价中手动添加该产品时按此成本核算。 */
  extra: boolean
}
const form = ref({ supplierId: '', projectTypeId: '', note: '', active: true, items: [] as Line[] })

async function load() {
  try {
    const [t, ty, p, o, pr] = await Promise.all([
      api<SupplierTemplate[]>('/supplier-templates'),
      api<ProjectType[]>('/project-types'),
      api<Partner[]>('/partners'),
      api<Offer[]>('/offers').catch(() => [] as Offer[]),
      api<Product[]>('/products'),
    ])
    templates.value = t
    products.value = pr
    offers.value = o
    types.value = ty
    suppliers.value = p.filter((x) => x.kind === 'SUPPLIER')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)

const n = (v: number | null | undefined) => (v == null ? undefined : Number(v))
/** 该供应商对该产品最近一次仍在有效期内的报价（供应商报价作为成本模板的参考）。 */
function offerFor(productId: string): Offer | undefined {
  const now = today()
  return offers.value
    .filter(
      (o) => o.product_id === productId && o.supplier_id === form.value.supplierId && String(o.valid_until) >= now,
    )
    .sort((a, b) => String(b.quoted_on).localeCompare(String(a.quoted_on)))[0]
}
const unit = (l: Line) =>
  l.materialCost == null && l.laborCost == null && l.financeCost == null
    ? null
    : (l.materialCost || 0) + (l.laborCost || 0) + (l.financeCost || 0)

/** 整套配置（按项目类型数量）的成本与标准售价；缺成本的产品使整套成本不可计算。 */
function setFigures(t: SupplierTemplate) {
  const type = types.value.find((x) => x.id === t.project_type_id)
  if (!type || !type.items.length) return { price: null, cost: null }
  let price = 0,
    cost: number | null = 0
  for (const i of type.items) {
    price += Number(i.quantity) * Number(i.unit_price || 0)
    const c = t.items.find((x) => x.product_id === i.product_id)?.unit_cost
    cost = c == null || cost == null ? null : cost + Number(i.quantity) * Number(c)
  }
  return { price, cost }
}
const formFigures = computed(() => {
  let price = 0,
    cost: number | null = 0
  for (const l of form.value.items) {
    if (l.extra) continue
    price += l.quantity * (l.unitPrice || 0)
    const u = unit(l)
    cost = u == null || cost == null ? null : cost + l.quantity * u
  }
  return { price, cost }
})

/** 选好类型和供应商后，按类型配置列出产品并带入已有成本；模板中配置以外的产品排在后面，保存时一并保留。 */
function rebuild() {
  const type = types.value.find((t) => t.id === form.value.projectTypeId)
  const existing = templates.value.find(
    (t) => t.project_type_id === form.value.projectTypeId && t.supplier_id === form.value.supplierId,
  )
  form.value.note = existing?.note || ''
  form.value.active = existing?.active ?? true
  form.value.items = (type?.items || []).map((i) => {
    const c = existing?.items.find((x) => x.product_id === i.product_id)
    return {
      productId: i.product_id,
      name: i.name,
      specification: i.specification,
      quantity: Number(i.quantity),
      unitPrice: i.unit_price == null ? null : Number(i.unit_price),
      materialCost: n(c?.material_cost),
      laborCost: n(c?.labor_cost),
      financeCost: n(c?.finance_cost),
      extra: false,
    }
  })
  const standard = new Set(form.value.items.map((l) => l.productId))
  for (const c of existing?.items || []) {
    if (standard.has(c.product_id)) continue
    form.value.items.push({
      productId: c.product_id,
      name: c.name,
      specification: c.specification,
      quantity: 0,
      unitPrice: null,
      materialCost: n(c.material_cost),
      laborCost: n(c.labor_cost),
      financeCost: n(c.finance_cost),
      extra: true,
    })
  }
  extraProductId.value = ''
}
/** 可添加的产品：标准产品库中尚未列出的。 */
const addable = computed(() => {
  const listed = new Set(form.value.items.map((l) => l.productId))
  return products.value.filter((p) => !listed.has(p.id))
})
const productLabel = (p: Product) =>
  `${p.scenario} / ${p.name}${p.specification ? ' / ' + p.specification : ''}${p.model ? ' / ' + p.model : ''}`
function addExtra() {
  const p = products.value.find((x) => x.id === extraProductId.value)
  if (!p) return
  form.value.items.push({
    productId: p.id,
    name: p.name,
    specification: p.specification,
    quantity: 0,
    unitPrice: null,
    materialCost: undefined,
    laborCost: undefined,
    financeCost: undefined,
    extra: true,
  })
  extraProductId.value = ''
}
function open(t?: SupplierTemplate) {
  form.value = {
    supplierId: t?.supplier_id || '',
    projectTypeId: t?.project_type_id || '',
    note: '',
    active: true,
    items: [],
  }
  rebuild()
  dialog.value = true
}
async function save() {
  const f = form.value
  if (!f.supplierId || !f.projectTypeId) return ElMessage.warning('请选择项目类型和供应商')
  if (!f.items.length) return ElMessage.warning('没有可保存的产品：请先设置该项目类型的标准配置，或添加配置外产品')
  const unfilled = f.items.filter((l) => l.extra && unit(l) == null)
  if (unfilled.length) return ElMessage.warning(`配置外产品“${unfilled[0].name}”还没有填成本，请填写或删除该行`)
  busy.value = true
  try {
    await post('/supplier-templates', {
      supplierId: f.supplierId,
      projectTypeId: f.projectTypeId,
      note: f.note,
      active: f.active,
      items: f.items.map((l) => ({
        productId: l.productId,
        materialCost: l.materialCost ?? null,
        laborCost: l.laborCost ?? null,
        financeCost: l.financeCost ?? null,
      })),
    })
    dialog.value = false
    await load()
    ElMessage.success('供应商成本模板已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div>
    <div class="manager-bar">
      <span class="muted">
        每个供应商对每种项目类型的单台成本 = 材料 + 团队人工 + 财务成本。新建报价选择供应商后自动带出。
      </span>
      <el-button v-if="canSeeCost()" type="primary" size="small" @click="open()">新增 / 维护成本模板</el-button>
    </div>
    <el-table :data="templates" empty-text="暂无供应商成本模板">
      <el-table-column label="项目类型" prop="project_type_name" min-width="120" />
      <el-table-column label="供应商" prop="supplier_name" min-width="150" />
      <el-table-column label="已报成本" width="90" align="right">
        <template #default="{ row }">{{ row.items.length }} 项</template>
      </el-table-column>
      <el-table-column label="整套成本" width="120" align="right">
        <template #default="{ row }">
          {{ setFigures(row).cost == null ? '不完整' : money(setFigures(row).cost) }}
        </template>
      </el-table-column>
      <el-table-column label="整套标准售价" width="120" align="right">
        <template #default="{ row }">{{ setFigures(row).price == null ? '—' : money(setFigures(row).price) }}</template>
      </el-table-column>
      <el-table-column label="整套毛利率" width="100" align="right">
        <template #default="{ row }">
          {{
            setFigures(row).cost == null || !setFigures(row).price
              ? '—'
              : percent((setFigures(row).price! - setFigures(row).cost!) / setFigures(row).price!)
          }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新" width="105">
        <template #default="{ row }">{{ day(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column v-if="canSeeCost()" label="操作" width="80" fixed="right">
        <template #default="{ row }"><el-button link type="primary" @click="open(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="供应商成本模板" width="min(1160px, 96vw)" :close-on-click-modal="false">
      <el-form label-position="top">
        <div class="pick-grid">
          <el-form-item label="项目类型" required>
            <el-select v-model="form.projectTypeId" placeholder="选择项目类型" @change="rebuild">
              <el-option v-for="t in types" :key="t.id" :value="t.id" :label="t.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="供应商" required>
            <el-select v-model="form.supplierId" filterable placeholder="选择供应商" @change="rebuild">
              <el-option v-for="s in suppliers" :key="s.id" :value="s.id" :label="s.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-table v-if="form.items.length" :data="form.items" size="small">
          <el-table-column label="产品 / 规格" min-width="190">
            <template #default="{ row }">
              {{ row.name }}
              <small class="muted">{{ row.specification }}</small>
              <el-tag v-if="row.extra" size="small" type="info" class="extra-tag">配置外</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="56" align="right">
            <template #default="{ row }">{{ row.extra ? '—' : row.quantity }}</template>
          </el-table-column>
          <el-table-column label="供应商报价参考" width="150">
            <template #default="{ row }">
              <template v-if="offerFor(row.productId)">
                {{ money(offerFor(row.productId)!.unit_price) }}
                <small class="muted">{{ taxName[offerFor(row.productId)!.tax_basis] }}</small>
                <el-button
                  link
                  type="primary"
                  size="small"
                  @click="row.materialCost = Number(offerFor(row.productId)!.unit_price)"
                >
                  填入
                </el-button>
                <small class="cell-secondary muted">有效至 {{ day(offerFor(row.productId)!.valid_until) }}</small>
              </template>
              <span v-else class="muted">无有效报价</span>
            </template>
          </el-table-column>
          <el-table-column label="材料成本" width="115">
            <template #default="{ row }">
              <el-input-number v-model="row.materialCost" :min="0" :precision="2" :controls="false" />
            </template>
          </el-table-column>
          <el-table-column label="团队人工" width="105">
            <template #default="{ row }">
              <el-input-number v-model="row.laborCost" :min="0" :precision="2" :controls="false" />
            </template>
          </el-table-column>
          <el-table-column label="财务成本" width="105">
            <template #default="{ row }">
              <el-input-number v-model="row.financeCost" :min="0" :precision="2" :controls="false" />
            </template>
          </el-table-column>
          <el-table-column label="单台成本" width="105" align="right">
            <template #default="{ row }">{{ unit(row) == null ? '待填' : money(unit(row)) }}</template>
          </el-table-column>
          <el-table-column label="标准售价" width="105" align="right">
            <template #default="{ row }">{{ row.unitPrice == null ? '—' : money(row.unitPrice) }}</template>
          </el-table-column>
          <el-table-column label="毛利率" width="80" align="right">
            <template #default="{ row }">
              {{ unit(row) == null || !row.unitPrice ? '—' : percent((row.unitPrice - unit(row)!) / row.unitPrice) }}
            </template>
          </el-table-column>
          <el-table-column width="44">
            <template #default="{ row, $index }">
              <el-button
                v-if="row.extra"
                :icon="Delete"
                link
                type="danger"
                aria-label="删除配置外产品"
                @click="form.items.splice($index, 1)"
              />
            </template>
          </el-table-column>
        </el-table>
        <div v-else-if="form.projectTypeId" class="inline-note">
          该项目类型还没有标准配置（门店要用哪些产品、各几台），所以这里没有可填成本的产品。
          <el-button
            size="small"
            type="primary"
            link
            @click="((dialog = false), emit('configure', form.projectTypeId))"
          >
            去设置标准配置（可从历史报价一键推荐）
          </el-button>
        </div>
        <div v-if="form.projectTypeId && form.supplierId" class="extra-row">
          <el-select v-model="extraProductId" filterable clearable placeholder="添加标准配置以外的产品（如选配件）">
            <el-option v-for="p in addable" :key="p.id" :value="p.id" :label="productLabel(p)" />
          </el-select>
          <el-button :disabled="!extraProductId" @click="addExtra">添加</el-button>
          <span class="muted">配置外产品不计入整套成本；报价中手动加入该产品时按这里的成本核算。</span>
        </div>
        <div v-if="form.items.some((l) => !l.extra)" class="set-sum">
          整套成本
          <strong>{{ formFigures.cost == null ? '不完整' : money(formFigures.cost) }}</strong>
          · 整套标准售价
          <strong>{{ money(formFigures.price) }}</strong>
          · 毛利率
          <strong>
            {{
              formFigures.cost == null || !formFigures.price
                ? '—'
                : percent((formFigures.price - formFigures.cost) / formFigures.price)
            }}
          </strong>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="1000" placeholder="如：2026 年 11 月报价，含运费" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.manager-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 24px;
  font-size: 12px;
}
.pick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 160px;
  gap: 0 16px;
}
.extra-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
}
.extra-row .el-select {
  flex: 0 0 340px;
}
.extra-tag {
  margin-left: 6px;
}
.set-sum {
  margin: 12px 0;
  text-align: right;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.set-sum strong {
  margin: 0 4px;
  font-size: 15px;
}
small {
  margin-left: 6px;
}
:deep(.el-input-number) {
  width: 100%;
}
</style>
