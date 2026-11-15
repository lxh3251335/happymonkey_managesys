<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import { api } from '../api'
import type { Project } from '../types'
import { copyText, deliveryText, today } from '../utils'

/** 发货与物流登记：收货信息、预计发货、发货状态与日期、快递公司与运单号。填了运单号即视为已发货。 */
const props = defineProps<{ modelValue: boolean; project?: Project | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const couriers = ref<string[]>([]),
  busy = ref(false)
const form = ref({
  status: '',
  expectedShipOn: '',
  shippedOn: '',
  courier: '',
  trackingNo: '',
  consignee: '',
  consigneePhone: '',
  shipAddress: '',
})
const d = (v: string | null | undefined) => (v ? String(v).slice(0, 10) : '')

watch(
  () => props.modelValue,
  async (open) => {
    if (!open || !props.project) return
    const p = props.project
    form.value = {
      status: p.shipping_status || '',
      expectedShipOn: d(p.expected_ship_on),
      shippedOn: d(p.shipped_on),
      courier: p.courier || '',
      trackingNo: p.tracking_no || '',
      consignee: p.consignee || '',
      consigneePhone: p.consignee_phone || '',
      shipAddress: p.ship_address || '',
    }
    if (!couriers.value.length) couriers.value = await api<string[]>('/projects/couriers').catch(() => [])
  },
)
/** 录入运单号时自动标记已发货并带上今天。 */
watch(
  () => form.value.trackingNo,
  (v) => {
    if (v.trim() && form.value.status !== '已发货') {
      form.value.status = '已发货'
      if (!form.value.shippedOn) form.value.shippedOn = today()
    }
  },
)
async function copy() {
  if (!props.project) return
  const ok = await copyText(
    deliveryText({
      name: props.project.name,
      consignee: form.value.consignee,
      consignee_phone: form.value.consigneePhone,
      ship_address: form.value.shipAddress,
      courier: form.value.courier,
      tracking_no: form.value.trackingNo,
    }),
  )
  if (ok) ElMessage.success('邮寄信息已复制')
  else ElMessage.warning('浏览器未允许复制，请手动选择文本')
}
async function save() {
  if (!props.project) return
  busy.value = true
  try {
    const f = form.value
    await api(`/projects/${props.project.id}/delivery`, {
      method: 'PUT',
      body: JSON.stringify({
        status: f.status,
        expectedShipOn: f.expectedShipOn || null,
        shippedOn: f.status === '已发货' ? f.shippedOn || null : null,
        courier: f.courier,
        trackingNo: f.trackingNo.replace(/\s/g, ''),
        consignee: f.consignee,
        consigneePhone: f.consigneePhone,
        shipAddress: f.shipAddress,
      }),
    })
    ElMessage.success('发货与物流信息已保存')
    emit('update:modelValue', false)
    emit('saved')
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
    :title="`发货与物流 · ${project?.name || ''}`"
    width="640px"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <div class="section">收货信息</div>
      <div class="form-grid">
        <el-form-item label="收货人"><el-input v-model="form.consignee" maxlength="60" /></el-form-item>
        <el-form-item label="收货电话"><el-input v-model="form.consigneePhone" maxlength="40" /></el-form-item>
      </div>
      <el-form-item label="收货地址">
        <el-input v-model="form.shipAddress" maxlength="300" placeholder="省市区 + 详细地址" />
      </el-form-item>
      <div class="section">发货</div>
      <div class="form-grid">
        <el-form-item label="预计发货日期">
          <el-date-picker v-model="form.expectedShipOn" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="发货状态">
          <el-select v-model="form.status" placeholder="未登记">
            <el-option label="未登记" value="" />
            <el-option label="制作中" value="制作中" />
            <el-option label="已发货" value="已发货" />
          </el-select>
        </el-form-item>
        <el-form-item label="快递 / 物流公司">
          <el-select
            v-model="form.courier"
            filterable
            allow-create
            clearable
            default-first-option
            placeholder="选择或输入"
          >
            <el-option v-for="c in couriers" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号">
          <el-input v-model="form.trackingNo" maxlength="60" placeholder="填写后自动标记已发货" />
        </el-form-item>
        <el-form-item v-if="form.status === '已发货'" label="发货日期">
          <el-date-picker v-model="form.shippedOn" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button :icon="CopyDocument" class="copy" @click="copy">复制邮寄信息</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.section {
  margin: 2px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #253731;
}
.copy {
  float: left;
}
</style>
