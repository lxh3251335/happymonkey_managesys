<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import BusinessManager from '../components/BusinessManager.vue'
import DictionaryManager from '../components/DictionaryManager.vue'
import CompanyManager from '../components/CompanyManager.vue'
import BotBinding from '../components/BotBinding.vue'
import WecomBotSettings from '../components/WecomBotSettings.vue'
import { api, post, put, session } from '../api'
import type { User, Audit, ImportBatch } from '../types'
import { actionName, dateTime, day, roleName } from '../utils'
const tab = ref('account'),
  users = ref<User[]>([]),
  audits = ref<Audit[]>([]),
  imports = ref<ImportBatch[]>([]),
  dialog = ref(false),
  busy = ref(false),
  error = ref(''),
  dictTab = ref<'business' | 'scenario' | 'city'>('business')
const password = ref({ oldPassword: '', newPassword: '' })
const user = ref({ username: '', displayName: '', role: 'SALES', password: '' })
const editDialog = ref(false),
  editing = ref<User>(),
  edit = ref({ displayName: '', role: 'SALES', enabled: true, password: '' })
function openEdit(u: User) {
  editing.value = u
  edit.value = { displayName: u.display_name, role: u.role, enabled: u.enabled !== false, password: '' }
  editDialog.value = true
}
async function saveUser() {
  if (!editing.value) return
  if (!edit.value.displayName.trim()) return ElMessage.warning('请填写显示名称')
  if (edit.value.password && edit.value.password.length < 12) return ElMessage.warning('重置密码至少12位')
  busy.value = true
  try {
    const id = editing.value.id
    await put(`/admin/users/${id}`, {
      displayName: edit.value.displayName,
      role: edit.value.role,
      enabled: edit.value.enabled,
    })
    if (edit.value.password) await post(`/admin/users/${id}/password`, { password: edit.value.password })
    editDialog.value = false
    await load()
    ElMessage.success(edit.value.password ? '用户已更新，密码已重置' : '用户已更新')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function load() {
  if (session.user?.role !== 'ADMIN') return
  try {
    ;[users.value, audits.value, imports.value] = await Promise.all([
      api<User[]>('/admin/users'),
      api<Audit[]>('/admin/audit'),
      api<ImportBatch[]>('/admin/imports'),
    ])
    error.value = ''
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function changePassword() {
  if (password.value.newPassword.length < 12) return ElMessage.warning('新密码至少12位')
  busy.value = true
  try {
    await post('/auth/password', password.value)
    password.value = { oldPassword: '', newPassword: '' }
    ElMessage.success('密码已更新')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function createUser() {
  busy.value = true
  try {
    await post('/admin/users', user.value)
    dialog.value = false
    user.value = { username: '', displayName: '', role: 'SALES', password: '' }
    await load()
    ElMessage.success('用户已创建')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
function summary(s: string) {
  try {
    const x = JSON.parse(s)
    return `客户 ${x.customers} 家 / 项目 ${x.projects} 个 / 明细 ${x.records} 行 / 隔离 ${x.excluded} 行 / 待复核 ${x.warnings} 项`
  } catch {
    return s
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader eyebrow="WORKSPACE SETTINGS" title="系统管理" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section class="panel settings-panel">
      <el-tabs v-model="tab">
        <el-tab-pane label="账户安全" name="account" />
        <template v-if="session.user?.role === 'ADMIN'">
          <el-tab-pane label="用户与角色" name="users" />
          <el-tab-pane label="数据字典" name="business" />
          <el-tab-pane label="签约主体" name="companies" />
          <el-tab-pane label="企业微信机器人" name="wecom" />
          <el-tab-pane label="操作审计" name="audit" />
          <el-tab-pane label="历史数据导入" name="imports" />
        </template>
      </el-tabs>
      <div v-if="tab === 'account'" class="account-form">
        <h2>{{ session.user?.display_name }}</h2>
        <p class="muted">{{ session.user?.username }} / {{ roleName[session.user?.role || ''] }}</p>
        <el-form label-position="top" @submit.prevent="changePassword">
          <el-form-item label="原密码">
            <el-input v-model="password.oldPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码（至少12位）">
            <el-input v-model="password.newPassword" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-button type="primary" :loading="busy" @click="changePassword">更新密码</el-button>
        </el-form>
        <BotBinding />
      </div>
      <div v-if="tab === 'users'">
        <el-button type="primary" class="section-gap" @click="dialog = true">新增用户</el-button>
        <el-table :data="users">
          <el-table-column label="用户名" prop="username" />
          <el-table-column label="显示名称" prop="display_name" />
          <el-table-column label="角色">
            <template #default="{ row }">{{ roleName[row.role] }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template v-if="tab === 'business'">
        <el-radio-group v-model="dictTab" size="small" class="dict-switch">
          <el-radio-button value="business">客户集团与业务线</el-radio-button>
          <el-radio-button value="scenario">应用场景</el-radio-button>
          <el-radio-button value="city">城市</el-radio-button>
        </el-radio-group>
        <BusinessManager v-if="dictTab === 'business'" />
        <DictionaryManager v-else :key="dictTab" :kind="dictTab" />
      </template>
      <CompanyManager v-if="tab === 'companies'" />
      <WecomBotSettings v-if="tab === 'wecom'" />
      <el-table v-if="tab === 'audit'" :data="audits" max-height="600">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ dateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作人" prop="actor" width="130" />
        <el-table-column label="操作" min-width="180">
          <template #default="{ row }">{{ actionName[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column label="说明" prop="detail" min-width="420" show-overflow-tooltip />
      </el-table>
      <div v-if="tab === 'imports'">
        <el-table :data="imports">
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ day(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="导入概况" min-width="400">
            <template #default="{ row }">{{ summary(row.detail) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </section>
    <el-dialog v-model="dialog" title="新增内部用户" width="520px">
      <el-form label-position="top">
        <el-form-item label="用户名（字母、数字、下划线，3–40位）"><el-input v-model="user.username" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="user.displayName" maxlength="100" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="user.role">
            <el-option v-for="(name, key) in roleName" :key="key" :label="name" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始密码（至少12位）">
          <el-input v-model="user.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="createUser">创建用户</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="editDialog" :title="`编辑用户 ${editing?.username || ''}`" width="520px">
      <el-form label-position="top">
        <el-form-item label="显示名称"><el-input v-model="edit.displayName" maxlength="100" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="edit.role">
            <el-option v-for="(name, key) in roleName" :key="key" :label="name" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="账户状态">
          <el-switch v-model="edit.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="重置密码（留空则不修改，至少12位）">
          <el-input v-model="edit.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.dict-switch {
  margin-bottom: 12px;
}
</style>
