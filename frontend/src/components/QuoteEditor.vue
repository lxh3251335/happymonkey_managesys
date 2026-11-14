<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { api, post } from '../api'
import type { Project, Product, Quote, QuoteTemplate } from '../types'
import { afterDays, money, percent } from '../utils'
const props = defineProps<{
  modelValue: boolean
  projects: Project[]
  products: Product[]
  projectId?: string
  seed?: Quote
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [string] }>()
interface LineDraft {
  productId: string
  quantity: number
  unitPrice: number | undefined
  unitCost: number | undefined
}
const form = ref({
  projectId: '',
  taxBasis: 'UNKNOWN',
  taxPercent: 0,
  validUntil: afterDays(30),
  note: '',
  templateId: '',
})
const templates = ref<QuoteTemplate[]>([])
const lines = ref<LineDraft[]>([]),
  busy = ref(false)
function add() {
  lines.value.push({ productId: '', quantity: 1, unitPrice: undefined, unitCost: undefined })
}
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    try {
      templates.value = (await api<QuoteTemplate[]>('/quotes/templates')).filter((t) => t.active)
    } catch {
      templates.value = []
    }
    form.value = {
      projectId: props.seed?.project_id || props.projectId || '',
      taxBasis: props.seed?.tax_basis || 'UNKNOWN',
      taxPercent: (props.seed?.tax_rate || 0) * 100,
      validUntil: afterDays(30),
      note: props.seed?.note || '',
      templateId: templates.value.find((t) => t.is_default)?.id || '',
    }
    lines.value = props.seed
      ? props.seed.items.map((i) => ({
          productId: i.product_id,
          quantity: Number(i.quantity),
          unitPrice: i.unit_price == null ? undefined : Number(i.unit_price),
          unitCost: i.unit_cost == null ? undefined : Number(i.unit_cost),
        }))
      : []
    if (!lines.value.length) add()
  },
)
function selectProduct(line: LineDraft) {
  const p = props.products.find((p) => p.id === line.productId)
  line.unitPrice = p?.reference_price == null ? undefined : Number(p.reference_price)
  line.unitCost = p?.reference_cost == null ? undefined : Number(p.reference_cost)
}
const total = computed(() =>
  lines.value.reduce((s, i) => s + Math.round(i.quantity * (i.unitPrice || 0) * 100) / 100, 0),
)
const cost = computed(() =>
  lines.value.some((i) => i.unitCost == null)
    ? null
    : lines.value.reduce((s, i) => s + Math.round(i.quantity * i.unitCost! * 100) / 100, 0),
)
async function save() {
  if (
    !form.value.projectId ||
    !form.value.validUntil ||
    !lines.value.length ||
    lines.value.some((l) => !l.productId || l.unitPrice == null)
  )
    return ElMessage.warning('请选择项目，并补全产品、数量、销售单价和有效期')
  busy.value = true
  try {
    const result = await post<{ id: string }>('/quotes', {
      ...form.value,
      taxRate: form.value.taxBasis === 'UNKNOWN' ? null : form.value.taxPercent / 100,
      templateId: form.value.templateId || null,
      items: lines.value.map((l) => ({ ...l, unitCost: l.unitCost ?? null })),
    })
    emit('update:modelValue', false)
    emit('saved', result.id)
    ElMessage.success('已保存新的报价版本')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="创建客户报价"
    width="1080px"
    :close-on-click-modal="false"
  >
    <el-form label-position="top">
      <div class="form-grid quote-base">
        <el-form-item label="报价项目" required>
          <el-select v-model="form.projectId" filterable placeholder="选择门店 / 项目">
            <el-option v-for="p in projects" :key="p.id" :value="p.id" :label="`${p.name} · ${p.customer_name}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="报价有效期" required>
          <el-date-picker v-model="form.validUntil" value-format="YYYY-MM-DD" type="date" />
        </el-form-item>
        <el-form-item label="报价与成本的统一口径">
          <el-select v-model="form.taxBasis">
            <el-option label="待核实" value="UNKNOWN" />
            <el-option label="含税" value="INCLUDED" />
            <el-option label="未税" value="EXCLUDED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.taxBasis !== 'UNKNOWN'" label="税率（%）">
          <el-input-number v-model="form.taxPercent" :min="0" :max="100" :precision="2" />
        </el-form-item>
        <el-form-item label="报价模板（抬头与条款）">
          <el-select v-model="form.templateId" placeholder="默认模板">
            <el-option
              v-for="t in templates"
              :key="t.id"
              :value="t.id"
              :label="`${t.name} · ${t.company_name}${t.is_default ? '（默认）' : ''}`"
            />
          </el-select>
        </el-form-item>
      </div>
      <div class="inline-note">
        选择产品后带入历史单价及成本参考，请核实当前配置和税口径。成本只用于内部测算，对客报价单不包含成本和利润。
      </div>
      <el-table :data="lines" class="editor-table">
        <el-table-column label="产品 / 规格" min-width="280">
          <template #default="{ row }">
            <el-select v-model="row.productId" filterable @change="selectProduct(row)" placeholder="选择标准产品">
              <el-option
                v-for="p in products"
                :key="p.id"
                :value="p.id"
                :label="`${p.brand} / ${p.name} / ${p.specification}${p.model ? ' / ' + p.model : ''}`"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0.001" :max="999999" :precision="3" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="销售单价（元）" width="165">
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitPrice"
              :min="0"
              :max="999999999"
              :precision="6"
              :controls="false"
              placeholder="必填"
            />
          </template>
        </el-table-column>
        <el-table-column label="单台成本（元）" width="165">
          <template #default="{ row }">
            <el-input-number
              v-model="row.unitCost"
              :min="0"
              :max="999999999"
              :precision="6"
              :controls="false"
              placeholder="待补充"
            />
          </template>
        </el-table-column>
        <el-table-column label="销售小计" width="135" align="right">
          <template #default="{ row }">
            {{ row.unitPrice == null ? '待补充' : money(Math.round(row.quantity * row.unitPrice * 100) / 100) }}
          </template>
        </el-table-column>
        <el-table-column width="60">
          <template #default="{ $index }">
            <el-button :icon="Delete" link type="danger" aria-label="删除报价明细" @click="lines.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <el-button class="add-line" :icon="Plus" @click="add">添加产品</el-button>
      <div class="quote-summary">
        <div>
          报价合计
          <strong>{{ money(total) }}</strong>
        </div>
        <div>
          成本合计
          <strong>{{ money(cost) }}</strong>
        </div>
        <div>
          预计利润
          <strong>{{ cost == null ? '待补充' : money(total - cost) }}</strong>
        </div>
        <div>
          销售毛利率
          <strong>{{ cost == null || total === 0 ? '待补充' : percent((total - cost) / total) }}</strong>
        </div>
      </div>
      <el-form-item label="对客备注（本单）">
        <el-input
          v-model="form.note"
          type="textarea"
          :rows="2"
          maxlength="2000"
          placeholder="本单特别说明，例如交货周期、运输范围。通用条款请维护在报价模板中。请勿填写内部成本。"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存报价草稿</el-button>
    </template>
  </el-dialog>
</template>
