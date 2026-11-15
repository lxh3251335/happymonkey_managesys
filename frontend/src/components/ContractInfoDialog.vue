<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, canApprove, post } from '../api'
import { companyLabel, contractStatusName, defaultCompany, dict, loadDictionaries } from '../dictionaries'
import type { ContractDetail, Partner } from '../types'
import { today } from '../utils'

/**
 * 合同要素编辑 / 新建合同。
 * 拟定中的合同由负责人直接修改；已签订的合同，非管理员提交变更申请（须填原因），管理员直接修改也须填原因。
 */
const props = defineProps<{ modelValue: boolean; contract?: ContractDetail | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [{ customerId: string; contractKey: string }] }>()

const busy = ref(false),
  customers = ref<Partner[]>([]),
  reason = ref('')
const fresh = () => ({
  customerId: '',
  contractNo: '',
  status: 'DRAFT',
  companyId: defaultCompany.value?.id || '',
  title: '',
  content: '',
  signedOn: '',
  signedAmount: undefined as number | undefined,
  paymentTerms: '',
  startOn: '',
  endOn: '',
  warrantyMonths: undefined as number | undefined,
  retentionAmount: undefined as number | undefined,
  ownerId: '',
  customerSigner: '',
  archived: false,
  archiveNo: '',
  note: '',
})
const form = ref(fresh())
const creating = computed(() => !props.contract)
/** 新建合同时，按所选客户的编号规律提示下一个合同号。 */
const suggested = ref('')
watch(
  () => form.value.customerId,
  async (id) => {
    suggested.value = ''
    if (!creating.value || !id) return
    suggested.value = await api<{ number: string }>(`/contracts/next-number?customerId=${encodeURIComponent(id)}`)
      .then((r) => r.number)
      .catch(() => '')
  },
)
const established = computed(() => !!props.contract && props.contract.status !== 'DRAFT')
/** 非管理员修改已建立的合同 → 提交审批。 */
const needsApproval = computed(() => established.value && !canApprove())
const needsReason = computed(() => established.value)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    reason.value = ''
    await loadDictionaries().catch(() => {})
    if (creating.value) {
      form.value = fresh()
      customers.value = (await api<Partner[]>('/partners').catch(() => [])).filter((p) => p.kind === 'CUSTOMER')
      return
    }
    const c = props.contract!,
      i = c.info
    const d = (v: string | null | undefined) => (v ? String(v).slice(0, 10) : '')
    const n = (v: number | null | undefined) => (v == null ? undefined : Number(v))
    form.value = {
      customerId: c.customer_id,
      contractNo: c.contract_key,
      status: c.status,
      companyId: i?.company_id || '',
      title: i?.title || '',
      content: i?.content || '',
      signedOn: d(i?.signed_on),
      signedAmount: n(i?.signed_amount),
      paymentTerms: i?.payment_terms || '',
      startOn: d(i?.start_on),
      endOn: d(i?.end_on),
      warrantyMonths: n(i?.warranty_months),
      retentionAmount: n(i?.retention_amount),
      ownerId: i ? i.owner_id || '' : c.owner_id || '',
      customerSigner: i?.customer_signer || '',
      archived: !!i?.archived,
      archiveNo: i?.archive_no || '',
      note: i?.note || '',
    }
  },
)
/** 签订：拟定中 → 已签订，自动带今天作为签订日期。 */
function sign() {
  form.value.status = 'SIGNED'
  if (!form.value.signedOn) form.value.signedOn = today()
}
function info() {
  const f = form.value
  return {
    status: f.status,
    companyId: f.companyId || null,
    title: f.title,
    content: f.content,
    signedOn: f.signedOn || null,
    signedAmount: f.signedAmount ?? null,
    paymentTerms: f.paymentTerms,
    startOn: f.startOn || null,
    endOn: f.endOn || null,
    warrantyMonths: f.warrantyMonths ?? null,
    retentionAmount: f.retentionAmount ?? null,
    ownerId: f.ownerId || null,
    customerSigner: f.customerSigner,
    archived: f.archived,
    archiveNo: f.archiveNo,
    note: f.note,
  }
}
async function save() {
  if (creating.value && !form.value.customerId) return ElMessage.warning('请选择客户')
  if (needsReason.value && !reason.value.trim())
    return ElMessage.warning(needsApproval.value ? '请填写变更原因，提交管理员审批' : '请填写修改原因')
  busy.value = true
  try {
    if (creating.value) {
      const r = await post<{ contractKey: string }>('/contracts', {
        customerId: form.value.customerId,
        contractNo: form.value.contractNo || null,
        info: info(),
      })
      ElMessage.success(`合同 ${r.contractKey} 已建立`)
      emit('saved', { customerId: form.value.customerId, contractKey: r.contractKey })
    } else {
      const c = props.contract!
      const r = await api<{ applied: boolean; pending: boolean }>(
        `/contracts/info?customerId=${encodeURIComponent(c.customer_id)}&contractKey=${encodeURIComponent(c.contract_key)}`,
        { method: 'PUT', body: JSON.stringify({ info: info(), reason: reason.value }) },
      )
      ElMessage.success(r.pending ? '变更申请已提交，管理员批准后生效' : '合同信息已保存')
      emit('saved', { customerId: c.customer_id, contractKey: c.contract_key })
    }
    emit('update:modelValue', false)
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
    :title="creating ? '新建合同' : needsApproval ? '申请变更合同' : '编辑合同信息'"
    width="760px"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="needsApproval" class="inline-note warn">
      该合同已{{
        contractStatusName[contract!.status]
      }}。保存后提交为变更申请，管理员批准后才生效；申请、审批及每个字段的原值和新值都会永久保留。
    </div>
    <div v-else-if="established" class="inline-note">
      管理员直接修改已建立的合同，须填写修改原因，修改前后的值会记入变更记录。
    </div>
    <el-form label-position="top">
      <div class="form-grid three">
        <el-form-item v-if="creating" label="客户" required>
          <el-select v-model="form.customerId" filterable placeholder="选择客户">
            <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="creating" label="合同号">
          <el-input
            v-model="form.contractNo"
            maxlength="100"
            :placeholder="suggested ? `留空自动编号：${suggested}` : '留空自动编号'"
          />
        </el-form-item>
        <el-form-item label="合同状态">
          <div class="status-row">
            <el-select v-model="form.status">
              <el-option v-for="(n, k) in contractStatusName" :key="k" :label="n" :value="k" />
            </el-select>
            <el-button v-if="form.status === 'DRAFT' && !creating" size="small" type="primary" plain @click="sign">
              确认签订
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="我方签约主体">
          <el-select v-model="form.companyId" clearable placeholder="未指定">
            <el-option
              v-for="c in dict.companies.filter((x) => x.active || x.id === form.companyId)"
              :key="c.id"
              :value="c.id"
              :label="companyLabel(c)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="签订日期">
          <el-date-picker v-model="form.signedOn" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="签约金额（元）">
          <el-input-number
            v-model="form.signedAmount"
            :min="0"
            :max="99999999999"
            :precision="2"
            :controls="false"
            placeholder="留空按门店订单合计"
          />
        </el-form-item>
        <el-form-item label="履约开始">
          <el-date-picker v-model="form.startOn" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="履约结束">
          <el-date-picker v-model="form.endOn" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="质保期（月）">
          <el-input-number v-model="form.warrantyMonths" :min="0" :max="240" :controls="false" />
        </el-form-item>
        <el-form-item label="质保金（元）">
          <el-input-number
            v-model="form.retentionAmount"
            :min="0"
            :max="99999999999"
            :precision="2"
            :controls="false"
          />
        </el-form-item>
        <el-form-item label="销售负责人">
          <el-select v-model="form.ownerId" clearable filterable :disabled="!canApprove()" placeholder="未分配">
            <el-option
              v-for="o in dict.owners.filter((x) => x.enabled || x.id === form.ownerId)"
              :key="o.id"
              :value="o.id"
              :label="o.display_name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="客户签约人">
          <el-input v-model="form.customerSigner" maxlength="100" />
        </el-form-item>
        <el-form-item label="原件归档">
          <div class="status-row">
            <el-switch v-model="form.archived" active-text="已归档" />
            <el-input v-model="form.archiveNo" maxlength="100" placeholder="归档编号" />
          </div>
        </el-form-item>
      </div>
      <el-form-item label="合同名称"><el-input v-model="form.title" maxlength="200" /></el-form-item>
      <el-form-item label="合同内容（标的、供货范围）">
        <el-input v-model="form.content" type="textarea" :rows="3" maxlength="2000" show-word-limit />
      </el-form-item>
      <el-form-item label="付款条款">
        <el-input v-model="form.paymentTerms" type="textarea" :rows="2" maxlength="1000" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="form.note" maxlength="2000" /></el-form-item>
      <el-form-item v-if="needsReason" :label="needsApproval ? '变更原因（提交审批）' : '修改原因'" required>
        <el-input v-model="reason" maxlength="500" placeholder="如：客户来函调整签约金额" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">
        {{ creating ? '建立合同' : needsApproval ? '提交变更申请' : '保存' }}
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.status-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.status-row .el-switch,
.status-row .el-button {
  flex: none;
  white-space: nowrap;
}
.inline-note.warn {
  background: #fbf3e2;
  color: #7a5a17;
}
</style>
