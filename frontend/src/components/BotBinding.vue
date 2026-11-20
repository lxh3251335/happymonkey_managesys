<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, del } from '../api'
import { dateTime } from '../utils'

/**
 * 当前登录用户绑定企业微信机器人：生成一次性绑定码，在企业微信里私聊机器人发“绑定 12345678”。
 * 绑定后机器人按这个账号的角色回答和办理。
 */
interface Binding {
  bound: boolean
  created_at?: string
  last_seen_at?: string
}
const binding = ref<Binding>({ bound: false }),
  code = ref(''),
  minutes = ref(10),
  busy = ref(false)

async function load() {
  binding.value = await api<Binding>('/bot/binding').catch(() => ({ bound: false }))
}
async function newCode() {
  busy.value = true
  try {
    const r = await api<{ code: string; minutes: number }>('/bot/binding/code', { method: 'POST' })
    code.value = r.code
    minutes.value = r.minutes
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function unbind() {
  try {
    await ElMessageBox.confirm('解除后，机器人不再回答你的消息，直到重新绑定。', '解除企业微信绑定', {
      confirmButtonText: '解除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await del('/bot/binding')
    code.value = ''
    await load()
    ElMessage.success('已解除绑定')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)
</script>
<template>
  <div class="bot-binding">
    <h3>企业微信机器人</h3>
    <p v-if="binding.bound" class="muted">
      已绑定（{{ dateTime(binding.created_at) }}）。最近一次使用：{{ dateTime(binding.last_seen_at) }}
    </p>
    <p v-else class="muted">还没有绑定。</p>
    <div v-if="code" class="code-box">
      <p>在企业微信里和机器人单聊，发送：</p>
      <p class="code mono">绑定 {{ code }}</p>
      <p class="muted">{{ minutes }} 分钟内有效，只能用一次，不要发在群里。</p>
      <el-button size="small" @click="load">刷新</el-button>
    </div>
    <div class="actions">
      <el-button :loading="busy" @click="newCode">{{ binding.bound ? '换绑：生成绑定码' : '生成绑定码' }}</el-button>
      <el-button v-if="binding.bound" type="danger" plain @click="unbind">解除绑定</el-button>
    </div>
  </div>
</template>
<style scoped>
.bot-binding {
  margin-top: 32px;
  padding-top: 20px;
  border-top: 1px solid var(--line, #dfe5e0);
}
h3 {
  margin: 0 0 6px;
  font-size: 16px;
}
.code-box {
  margin: 12px 0;
  padding: 14px 16px;
  border-radius: 8px;
  background: #f3f6f2;
}
.code-box p {
  margin: 0 0 8px;
}
.code {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 1px;
}
.actions {
  margin-top: 12px;
}
</style>
