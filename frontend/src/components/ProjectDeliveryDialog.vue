<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import { api } from '../api'
import type { Project } from '../types'
import { copyText, deliveryText, today } from '../utils'
import { parseDelivery } from '../parsing/delivery'

/** 发货与物流登记：收货信息、预计发货、发货状态与日期、快递公司与运单号。填了运单号即视为已发货。 */
const props = defineProps<{
  modelValue: boolean
  project?: Project | null
  /** 从“粘贴登记”打开时带入的收货信息或运单文字。 */
  initialText?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const couriers = ref<string[]>([]),
  busy = ref(false),
  pasted = ref(''),
  /** 粘贴识别的结果说明：填入了哪些字段、哪些需要核对。 */
  pasteNote = ref<{ filled: string[]; guessed?: string } | null>(null)
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
    pasted.value = ''
    pasteNote.value = null
    if (!couriers.value.length) couriers.value = await api<string[]>('/projects/couriers').catch(() => [])
    if (props.initialText) pasted.value = props.initialText
  },
)
/** 粘贴识别：只覆盖认出来的字段，其余保持原样。 */
watch(pasted, (text) => {
  if (!text.trim()) return (pasteNote.value = null)
  const p = parseDelivery(text, couriers.value)
  const f = form.value,
    filled: string[] = []
  if (p.consignee) ((f.consignee = p.consignee), filled.push('收货人'))
  if (p.phone) ((f.consigneePhone = p.phone), filled.push('电话'))
  if (p.address) ((f.shipAddress = p.address), filled.push('地址'))
  if (p.courier) ((f.courier = p.courier), filled.push('快递公司'))
  if (p.trackingNo) ((f.trackingNo = p.trackingNo), filled.push('运单号'))
  pasteNote.value = { filled, guessed: p.courierGuessed ? p.courier : undefined }
})
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
      <el-input
        v-model="pasted"
        type="textarea"
        :rows="2"
        resize="none"
        class="paste-box"
        placeholder="粘贴识别：把客户发来的收货信息或快递单号贴到这里，自动填到下面。例如：张三 13800001111 广东省深圳市宝安区某某路 8 号"
      />
      <div v-if="pasteNote" :class="['paste-note', { none: !pasteNote.filled.length }]">
        <template v-if="pasteNote.filled.length">
          已填入：{{ pasteNote.filled.join('、') }}，请核对。
          <span v-if="pasteNote.guessed" class="warn">快递公司“{{ pasteNote.guessed }}”是按单号开头推断的。</span>
        </template>
        <template v-else>没有认出收货信息或运单号，请手工填写。</template>
      </div>
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
  font-size: 14px;
  font-weight: 600;
  color: #253731;
}
.copy {
  float: left;
}
.paste-box {
  margin-bottom: 6px;
}
.paste-box :deep(textarea) {
  background: #f8faf7;
}
.paste-note {
  margin-bottom: 10px;
  font-size: 13px;
  color: #24634f;
}
.paste-note.none,
.paste-note .warn {
  color: #96670f;
}
</style>
