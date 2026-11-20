<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, del, put } from '../api'
import { dateTime, roleName } from '../utils'

/**
 * 企业微信机器人（系统管理员）：填写 Bot ID 和 Secret、启用或停用、看连接状态，以及谁绑定了机器人。
 * Secret 只能写入，保存后不再显示。
 */
interface Status {
  state: string
  detail?: string | null
  since?: string | null
  lastMessageAt?: string | null
}
interface Config {
  botId: string
  secretSet: boolean
  enabled: boolean
  updatedBy?: string | null
  updatedAt?: string | null
  status: Status
}
interface BindingRow {
  id: string
  username: string
  display_name: string
  role: string
  enabled: boolean
  created_at: string
  last_seen_at: string | null
}
const STATES: Record<string, { text: string; type: 'success' | 'warning' | 'danger' | 'info' }> = {
  DISABLED: { text: '未启用', type: 'info' },
  CONNECTING: { text: '正在连接', type: 'warning' },
  CONNECTED: { text: '已连接', type: 'success' },
  RETRYING: { text: '连接断开，正在重连', type: 'warning' },
  AUTH_FAILED: { text: '认证失败', type: 'danger' },
  KICKED: { text: '已被别处的连接顶下线', type: 'danger' },
}
const config = ref<Config>(),
  form = ref({ botId: '', secret: '', enabled: false }),
  bindings = ref<BindingRow[]>([]),
  busy = ref(false)
const state = computed(() => STATES[config.value?.status.state || 'DISABLED'] || STATES.DISABLED)
let timer: number | undefined

async function load(fill = true) {
  try {
    ;[config.value, bindings.value] = await Promise.all([
      api<Config>('/wecom/config'),
      api<BindingRow[]>('/bot/bindings'),
    ])
    if (fill) form.value = { botId: config.value.botId, secret: '', enabled: config.value.enabled }
  } catch (e) {
    if (fill) ElMessage.error((e as Error).message)
  }
}
async function save() {
  if (form.value.enabled && !form.value.botId.trim()) return ElMessage.warning('请填写 Bot ID')
  if (form.value.enabled && !form.value.secret && !config.value?.secretSet) return ElMessage.warning('请填写 Secret')
  busy.value = true
  try {
    await put('/wecom/config', form.value)
    await load()
    // 连接要一两秒才有结果
    window.setTimeout(() => load(false), 1500)
    ElMessage.success(form.value.enabled ? '已保存，正在连接企业微信' : '已保存，机器人已停用')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function reconnect() {
  busy.value = true
  try {
    await api('/wecom/reconnect', { method: 'POST' })
    await load(false)
    window.setTimeout(() => load(false), 1500)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function unbind(row: BindingRow) {
  try {
    await ElMessageBox.confirm(`解除后，机器人不再回答 ${row.display_name} 的消息，直到他重新绑定。`, '解除绑定', {
      confirmButtonText: '解除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await del(`/bot/bindings/${row.id}`)
    await load(false)
    ElMessage.success('已解除绑定')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(() => {
  load()
  // 连接状态会自己变化（连上、断开、重连），定时刷新；不覆盖正在填写的表单
  timer = window.setInterval(() => load(false), 5000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>
<template>
  <div class="wecom">
    <div class="status-line">
      连接状态：
      <el-tag :type="state.type" effect="light">{{ state.text }}</el-tag>
      <span v-if="config?.status.detail" class="danger-text detail">{{ config.status.detail }}</span>
      <span v-if="config?.status.lastMessageAt" class="muted detail">
        最近一条消息：{{ dateTime(config.status.lastMessageAt) }}
      </span>
      <el-button v-if="config?.enabled" size="small" :loading="busy" @click="reconnect">重新连接</el-button>
    </div>
    <el-form label-position="top" class="wecom-form" @submit.prevent="save">
      <el-form-item label="Bot ID">
        <el-input v-model="form.botId" autocomplete="off" placeholder="企业微信管理后台 → 智能机器人 → API 配置" />
      </el-form-item>
      <el-form-item label="Secret">
        <el-input
          v-model="form.secret"
          type="password"
          show-password
          autocomplete="new-password"
          :placeholder="config?.secretSet ? '已保存。留空表示不修改' : '选择“使用长连接”后点“获取”得到'"
        />
      </el-form-item>
      <el-form-item label="启用机器人">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      <span v-if="config?.updatedAt" class="muted detail">
        上次修改：{{ config.updatedBy }}，{{ dateTime(config.updatedAt) }}
      </span>
    </el-form>
    <h3>已绑定的人</h3>
    <el-table :data="bindings" empty-text="还没有人绑定">
      <el-table-column label="用户" min-width="160">
        <template #default="{ row }">{{ row.display_name }}（{{ row.username }}）</template>
      </el-table-column>
      <el-table-column label="角色" width="120">
        <template #default="{ row }">{{ roleName[row.role] }}{{ row.enabled ? '' : '（已停用）' }}</template>
      </el-table-column>
      <el-table-column label="绑定时间" width="160">
        <template #default="{ row }">{{ dateTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="160">
        <template #default="{ row }">{{ dateTime(row.last_seen_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }"><el-button link type="danger" @click="unbind(row)">解除绑定</el-button></template>
      </el-table-column>
    </el-table>
  </div>
</template>
<style scoped>
.status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
}
.detail {
  font-size: 13px;
  margin-left: 4px;
}
.wecom-form {
  max-width: 460px;
}
h3 {
  margin: 28px 0 10px;
  font-size: 16px;
}
</style>
