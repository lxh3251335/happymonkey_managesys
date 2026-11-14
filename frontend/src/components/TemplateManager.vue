<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, post, put } from '../api'
import type { QuoteTemplate } from '../types'
import { day } from '../utils'

const templates = ref<QuoteTemplate[]>([]),
  dialog = ref(false),
  busy = ref(false),
  editingId = ref('')
const blank = () => ({
  name: '',
  companyName: '',
  title: '客户报价单',
  address: '',
  contact: '',
  phone: '',
  email: '',
  taxNo: '',
  bankName: '',
  bankAccount: '',
  terms: '',
  footer: '',
  contractTerms: '',
  active: true,
})
const form = ref(blank())

async function load() {
  try {
    templates.value = await api<QuoteTemplate[]>('/quotes/templates')
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
function open(t?: QuoteTemplate) {
  editingId.value = t?.id || ''
  form.value = t
    ? {
        name: t.name,
        companyName: t.company_name,
        title: t.title,
        address: t.address || '',
        contact: t.contact || '',
        phone: t.phone || '',
        email: t.email || '',
        taxNo: t.tax_no || '',
        bankName: t.bank_name || '',
        bankAccount: t.bank_account || '',
        terms: t.terms || '',
        footer: t.footer || '',
        contractTerms: t.contract_terms || '',
        active: t.active,
      }
    : blank()
  dialog.value = true
}
async function save() {
  if (!form.value.name.trim() || !form.value.companyName.trim() || !form.value.title.trim())
    return ElMessage.warning('请填写模板名称、公司抬头和单据标题')
  busy.value = true
  try {
    if (editingId.value) await put(`/quotes/templates/${editingId.value}`, form.value)
    else await post('/quotes/templates', form.value)
    dialog.value = false
    await load()
    ElMessage.success('报价模板已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function makeDefault(t: QuoteTemplate) {
  try {
    await post(`/quotes/templates/${t.id}/default`, {})
    await load()
    ElMessage.success(`已将“${t.name}”设为默认模板`)
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
onMounted(load)
</script>
<template>
  <div>
    <div class="inline-note">
      报价模板决定对客报价单的公司抬头、联系方式、收款账户和标准条款。报价出具时会固定当时的模板内容，之后修改模板不会改变已出具的报价单。
    </div>
    <el-button type="primary" class="section-gap" @click="open()">新增报价模板</el-button>
    <el-table :data="templates">
      <el-table-column label="模板名称" min-width="160">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.is_default" size="small" type="success" class="tag-gap">默认</el-tag>
          <el-tag v-if="!row.active" size="small" type="info" class="tag-gap">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="公司抬头" prop="company_name" min-width="200" show-overflow-tooltip />
      <el-table-column label="单据标题" prop="title" width="130" />
      <el-table-column label="联系电话" prop="phone" width="140" />
      <el-table-column label="使用报价" prop="used" width="90" align="right" />
      <el-table-column label="更新" width="110">
        <template #default="{ row }">{{ day(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
          <el-button v-if="!row.is_default && row.active" link @click="makeDefault(row)">设为默认</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialog"
      :title="editingId ? '编辑报价模板' : '新增报价模板'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="模板名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
          <el-form-item label="单据标题" required>
            <el-input v-model="form.title" maxlength="100" placeholder="例如：客户报价单" />
          </el-form-item>
        </div>
        <el-form-item label="公司抬头" required>
          <el-input v-model="form.companyName" maxlength="200" placeholder="报价单顶部显示的公司全称" />
        </el-form-item>
        <el-form-item label="公司地址"><el-input v-model="form.address" maxlength="300" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="联系人"><el-input v-model="form.contact" maxlength="100" /></el-form-item>
          <el-form-item label="联系电话"><el-input v-model="form.phone" maxlength="60" /></el-form-item>
          <el-form-item label="电子邮箱"><el-input v-model="form.email" maxlength="120" /></el-form-item>
          <el-form-item label="纳税人识别号"><el-input v-model="form.taxNo" maxlength="60" /></el-form-item>
          <el-form-item label="开户银行"><el-input v-model="form.bankName" maxlength="200" /></el-form-item>
          <el-form-item label="银行账号"><el-input v-model="form.bankAccount" maxlength="60" /></el-form-item>
        </div>
        <el-form-item label="标准报价条款（每条一行）">
          <el-input v-model="form.terms" type="textarea" :rows="5" maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="页脚说明">
          <el-input v-model="form.footer" maxlength="500" placeholder="例如：本报价单经双方签字盖章后生效" />
        </el-form-item>
        <el-form-item label="合同条款（一键输出合同时使用，每条一行）">
          <el-input v-model="form.contractTerms" type="textarea" :rows="6" maxlength="6000" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存模板</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.tag-gap {
  margin-left: 6px;
}
</style>
