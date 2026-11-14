<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import PageHeader from '../components/PageHeader.vue'
import { api, post, canWrite } from '../api'
import type { Offer, Partner, Product } from '../types'
import { money, unitMoney, today, afterDays, day, percent, taxName } from '../utils'
const offers = ref<Offer[]>([]),
  partners = ref<Partner[]>([]),
  products = ref<Product[]>([]),
  productId = ref(''),
  quantity = ref(1),
  comparison = ref<Offer[]>([]),
  dialog = ref(false),
  busy = ref(false),
  error = ref('')
const fresh = () => ({
  supplierId: '',
  productId: productId.value,
  unitPrice: 0,
  taxBasis: 'INCLUDED',
  taxRate: 0.13,
  freight: 0,
  minQuantity: 1,
  leadDays: 7,
  quotedOn: today(),
  validUntil: afterDays(30),
  note: '',
})
const form = ref(fresh())
const suppliers = computed(() => partners.value.filter((p) => p.kind === 'SUPPLIER'))
const best = computed(() => comparison.value.find((o) => o.eligible))
async function compare() {
  if (!productId.value) {
    comparison.value = []
    return
  }
  try {
    comparison.value = await api<Offer[]>(
      `/offers/compare?productId=${encodeURIComponent(productId.value)}&quantity=${quantity.value}`,
    )
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
async function load() {
  try {
    ;[offers.value, partners.value, products.value] = await Promise.all([
      api<Offer[]>('/offers'),
      api<Partner[]>('/partners'),
      api<Product[]>('/products'),
    ])
    error.value = ''
    await compare()
  } catch (e) {
    error.value = (e as Error).message
  }
}
function open() {
  form.value = fresh()
  dialog.value = true
}
async function save() {
  if (!form.value.supplierId || !form.value.productId) return ElMessage.warning('请选择供应商与产品')
  busy.value = true
  try {
    await post('/offers', form.value)
    dialog.value = false
    ElMessage.success('供应商报价已登记')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader
      eyebrow="SUPPLIER INTELLIGENCE"
      title="供应商比价"
      description="在相同产品与配置下，比较含税价格、运费、交期和起订量。"
    >
      <el-button v-if="canWrite()" type="primary" @click="open">
        <el-icon><Plus /></el-icon>
        登记供应商报价
      </el-button>
    </PageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="panel comparison-controls">
      <div>
        <h2>选择同一产品，查看到货成本</h2>
        <p>到货总价 = 含税单价 × 采购数量 + 本次含税运费。仅有效且满足起订量的报价参与排名。</p>
      </div>
      <div class="compare-fields">
        <el-select v-model="productId" filterable clearable placeholder="选择需要比价的标准产品" @change="compare">
          <el-option
            v-for="p in products"
            :key="p.id"
            :value="p.id"
            :label="`${p.brand} / ${p.name} / ${p.specification} / ${p.model}`"
          />
        </el-select>
        <el-input-number v-model="quantity" :min="0.001" :max="999999" :precision="3" @change="compare" />
        <el-button type="primary" @click="compare">开始比价</el-button>
      </div>
    </section>
    <section v-if="productId" class="panel">
      <div class="panel-heading">
        <div>
          <h2>到货成本比较</h2>
          <p>
            {{ comparison.length }} 份供应商报价
            <span v-if="best">· 当前最低到货单价 {{ money(best.landed_unit) }}</span>
          </p>
        </div>
      </div>
      <el-table :data="comparison">
        <el-table-column label="供应商" prop="supplier_name" min-width="160" />
        <el-table-column label="含税到货总价" align="right" min-width="160">
          <template #default="{ row }">
            <strong :class="best?.id === row.id ? 'profit-text' : ''">{{ money(row.landed_total) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="到货单价" align="right" min-width="140">
          <template #default="{ row }">{{ money(row.landed_unit) }}</template>
        </el-table-column>
        <el-table-column label="交期" width="100">
          <template #default="{ row }">{{ row.lead_days }} 天</template>
        </el-table-column>
        <el-table-column label="起订量" prop="min_quantity" width="100" />
        <el-table-column label="有效期至" width="130">
          <template #default="{ row }">{{ day(row.valid_until) }}</template>
        </el-table-column>
        <el-table-column label="适用情况" min-width="150">
          <template #default="{ row }">
            <span :class="row.eligible ? 'profit-text' : 'danger-text'">
              {{ row.eligible ? (row.id === best?.id ? '当前最低到货成本' : '有效报价') : '过期或不满足起订量' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div class="panel-bottom-note">
        请在备注中确认元件品牌、控制逻辑及配置清单一致。相同箱体尺寸不代表可直接替代。
      </div>
    </section>
    <section class="panel section-gap">
      <div class="panel-heading">
        <div>
          <h2>供应商报价记录</h2>
          <p>历史客户报价中的成本保留在报价明细，不自动视为供应商的有效报价。</p>
        </div>
        <span class="small-label">{{ offers.length }} RECORDS</span>
      </div>
      <el-table v-if="offers.length" :data="offers">
        <el-table-column label="供应商" prop="supplier_name" min-width="140" />
        <el-table-column label="产品" prop="product_name" min-width="160" />
        <el-table-column label="规格" prop="specification" min-width="150" />
        <el-table-column label="原始单价" width="135" align="right">
          <template #default="{ row }">{{ unitMoney(row.unit_price) }}</template>
        </el-table-column>
        <el-table-column label="税口径" width="140">
          <template #default="{ row }">{{ taxName[row.tax_basis] }} / {{ percent(row.tax_rate) }}</template>
        </el-table-column>
        <el-table-column label="报价日期" width="125">
          <template #default="{ row }">{{ day(row.quoted_on) }}</template>
        </el-table-column>
        <el-table-column label="有效期至" width="125">
          <template #default="{ row }">{{ day(row.valid_until) }}</template>
        </el-table-column>
        <el-table-column label="配置备注" prop="note" min-width="150" show-overflow-tooltip />
      </el-table>
      <el-empty v-else description="尚未登记供应商的正式报价">
        <p class="muted">先在基础资料中添加供应商，再登记其产品报价。</p>
        <router-link to="/masters"><el-button>管理基础资料</el-button></router-link>
      </el-empty>
    </section>
    <el-dialog v-model="dialog" title="登记供应商报价" width="680px">
      <el-alert v-if="!suppliers.length" title="请先到基础资料添加供应商。" type="info" :closable="false" />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商" required>
            <el-select v-model="form.supplierId" filterable>
              <el-option v-for="s in suppliers" :key="s.id" :value="s.id" :label="s.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="标准产品" required>
            <el-select v-model="form.productId" filterable>
              <el-option
                v-for="p in products"
                :key="p.id"
                :value="p.id"
                :label="`${p.brand} / ${p.name} / ${p.specification} / ${p.model}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="供应商单价（元）" required>
            <el-input-number v-model="form.unitPrice" :min="0" :max="999999999" :precision="6" :controls="false" />
          </el-form-item>
          <el-form-item label="价格口径">
            <el-select v-model="form.taxBasis">
              <el-option label="含税" value="INCLUDED" />
              <el-option label="未税" value="EXCLUDED" />
            </el-select>
          </el-form-item>
          <el-form-item label="税率（小数，0.13 表示13%）">
            <el-input-number v-model="form.taxRate" :min="0" :max="1" :step="0.01" :precision="4" />
          </el-form-item>
          <el-form-item label="本次含税运费（元）">
            <el-input-number v-model="form.freight" :min="0" :max="999999999" :precision="2" />
          </el-form-item>
          <el-form-item label="最小起订量">
            <el-input-number v-model="form.minQuantity" :min="0.001" :max="999999" :precision="3" />
          </el-form-item>
          <el-form-item label="交货天数">
            <el-input-number v-model="form.leadDays" :min="0" :max="3650" :precision="0" />
          </el-form-item>
          <el-form-item label="报价日期" required>
            <el-date-picker v-model="form.quotedOn" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
          <el-form-item label="有效期至" required>
            <el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </div>
        <el-form-item label="配置清单 / 报价说明">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存报价</el-button>
      </template>
    </el-dialog>
  </div>
</template>
