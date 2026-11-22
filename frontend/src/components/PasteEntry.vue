<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { DocumentAdd } from '@element-plus/icons-vue'
import { api, canFinance, canWrite } from '../api'
import type { Contract, Project } from '../types'
import { money } from '../utils'
import { matchStores, parseDelivery, parseTrackingLines } from '../parsing/delivery'
import { parseReceipt } from '../parsing/receipt'
import { notifyDataChanged } from '../dataEvents'
import ReceiptEntryDialog from './ReceiptEntryDialog.vue'
import InvoiceEntryDialog from './InvoiceEntryDialog.vue'
import DeliveryBatchDialog from './DeliveryBatchDialog.vue'
import ProjectDeliveryDialog from './ProjectDeliveryDialog.vue'

/**
 * 顶栏的“粘贴登记”：不管是运单、收货信息、银行到账短信还是发票 PDF，都往这一个框里粘或拖，
 * 系统认出是什么，再打开对应的登记窗口并填好。识别规则和各登记窗口里的一样，最终仍由人核对后保存。
 */
type Kind = 'invoice' | 'receipt' | 'tracking' | 'consignee' | 'unknown'

const open = ref(false),
  text = ref(''),
  file = ref<File>(),
  dragging = ref(false),
  storeId = ref(''),
  projects = ref<Project[]>([]),
  contracts = ref<Contract[]>([]),
  couriers = ref<string[]>([])
const receiptOpen = ref(false),
  invoiceOpen = ref(false),
  batchOpen = ref(false),
  deliveryOpen = ref(false),
  carried = ref('')

// 与机器人判断“是不是到账短信”的词一致
const RECEIPT_WORDS =
  /收到|收入|入账|转入|存入|汇入|到账|来账|贷记|付款人|付款方|付方|对方户名|交易金额|流水号|回单|汇款/
const dealt = computed(() =>
  projects.value.filter((p) => p.contract_state !== 'NONE' || Number(p.confirmed_quotes || 0) > 0),
)

function show() {
  text.value = ''
  file.value = undefined
  storeId.value = ''
  open.value = true
  if (canWrite() && !projects.value.length) {
    api<Project[]>('/projects')
      .then((r) => (projects.value = r))
      .catch(() => {})
    api<string[]>('/projects/couriers')
      .then((r) => (couriers.value = r))
      .catch(() => {})
  }
}

/** 收货信息：第一行若对得上门店，就是门店名称，其余是收货信息。 */
const firstLine = computed(() => text.value.trim().split(/\r?\n/)[0]?.trim() || '')
const storeHits = computed(() => (firstLine.value ? matchStores(firstLine.value, dealt.value) : []))
const consigneeText = computed(() =>
  storeHits.value.length ? text.value.trim().split(/\r?\n/).slice(1).join('\n') : text.value.trim(),
)
watch(storeHits, (hits) => {
  if (hits.length === 1) storeId.value = hits[0].id
})

const kind = computed<Kind>(() => {
  if (file.value) return 'invoice'
  const t = text.value.trim()
  if (!t) return 'unknown'
  const r = parseReceipt(t)
  if (r.amount != null && RECEIPT_WORDS.test(t)) return 'receipt'
  const lines = parseTrackingLines(t, couriers.value)
  if (lines.length && lines.every((l) => l.trackingNo && l.hint)) return 'tracking'
  const d = parseDelivery(consigneeText.value, couriers.value)
  if (d.phone || d.address || d.trackingNo) return 'consignee'
  return 'unknown'
})
const allowed = computed(() =>
  kind.value === 'invoice' || kind.value === 'receipt' ? canFinance() : kind.value === 'unknown' ? true : canWrite(),
)
const summary = computed(() => {
  const t = text.value.trim()
  switch (kind.value) {
    case 'invoice':
      return `发票文件：${file.value?.name}`
    case 'receipt': {
      const r = parseReceipt(t)
      return `到账短信：${money(r.amount!)}${r.payer ? `，付款方 ${r.payer}` : ''}${r.paidOn ? `，${r.paidOn}` : ''}`
    }
    case 'tracking':
      return `运单：${parseTrackingLines(t, couriers.value).length} 行“门店 + 运单号”`
    case 'consignee': {
      const d = parseDelivery(consigneeText.value, couriers.value)
      return `收货信息：${[d.consignee, d.phone, d.address, d.trackingNo].filter(Boolean).join('，')}`
    }
    default:
      return ''
  }
})
const action = computed(
  () =>
    ({ invoice: '登记发票', receipt: '登记回款', tracking: '登记运单', consignee: '填到门店的发货信息', unknown: '' })[
      kind.value
    ],
)
const store = computed(() => dealt.value.find((p) => p.id === storeId.value) || null)
const ready = computed(() => allowed.value && kind.value !== 'unknown' && (kind.value !== 'consignee' || !!store.value))

async function next() {
  if (!ready.value) return
  if ((kind.value === 'invoice' || kind.value === 'receipt') && !contracts.value.length)
    contracts.value = await api<Contract[]>('/contracts').catch(() => [])
  carried.value = kind.value === 'consignee' ? consigneeText.value : text.value.trim()
  open.value = false
  if (kind.value === 'invoice') invoiceOpen.value = true
  else if (kind.value === 'receipt') receiptOpen.value = true
  else if (kind.value === 'tracking') batchOpen.value = true
  else deliveryOpen.value = true
}
function onDrop(e: DragEvent) {
  dragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) file.value = f
}
function onPick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) file.value = f
}
function saved() {
  notifyDataChanged()
  projects.value = []
  contracts.value = []
}
</script>
<template>
  <el-button v-if="canWrite() || canFinance()" class="paste-entry-btn" @click="show">
    <el-icon><DocumentAdd /></el-icon>
    粘贴登记
  </el-button>
  <el-dialog v-model="open" title="粘贴登记" width="min(620px, 94vw)" append-to-body>
    <div
      :class="['drop', { dragging }]"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <el-input
        v-model="text"
        type="textarea"
        :rows="6"
        :disabled="!!file"
        placeholder="粘贴运单、收货信息或银行到账短信；发票 PDF 直接拖到这里"
      />
      <div class="file-row">
        <template v-if="file">
          <span>{{ file.name }}</span>
          <el-button link type="primary" @click="file = undefined">移除</el-button>
        </template>
        <label v-else-if="canFinance()" class="pick">
          或选择发票 PDF
          <input type="file" accept="application/pdf,.pdf" @change="onPick" />
        </label>
      </div>
    </div>
    <div v-if="text.trim() || file" class="result">
      <template v-if="kind === 'unknown'">
        <span class="muted">
          没认出来。可以粘贴：每行“门店 运单号”、门店名称加收货人电话地址、银行到账短信，或拖入发票 PDF。
        </span>
      </template>
      <template v-else>
        <div>{{ summary }}</div>
        <div v-if="kind === 'consignee'" class="store">
          <span>门店</span>
          <el-select v-model="storeId" filterable placeholder="选择门店（第一行写门店名称可自动对上）">
            <el-option v-for="p in storeHits.length ? storeHits : dealt" :key="p.id" :value="p.id" :label="p.name" />
          </el-select>
        </div>
        <div v-if="!allowed" class="danger-text">你的账号角色不能{{ action }}。</div>
      </template>
    </div>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :disabled="!ready" @click="next">
        {{ action ? `下一步：${action}` : '下一步' }}
      </el-button>
    </template>
  </el-dialog>
  <ReceiptEntryDialog v-model="receiptOpen" :contracts="contracts" :preset="{ text: carried }" @saved="saved" />
  <InvoiceEntryDialog v-model="invoiceOpen" :contracts="contracts" :initial-file="file" @saved="saved" />
  <DeliveryBatchDialog v-model="batchOpen" :projects="dealt" :initial-text="carried" @saved="saved" />
  <ProjectDeliveryDialog v-model="deliveryOpen" :project="store" :initial-text="carried" @saved="saved" />
</template>
<style scoped>
.drop {
  border: 1px dashed transparent;
  border-radius: 8px;
}
.drop.dragging {
  border-color: #24634f;
  background: #f5f9f6;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  margin-top: 6px;
  font-size: 13px;
}
.pick {
  color: #24634f;
  cursor: pointer;
}
.pick input {
  display: none;
}
.result {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f3f6f2;
  font-size: 14px;
  line-height: 1.6;
}
.store {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}
.store .el-select {
  flex: 1;
}
</style>
