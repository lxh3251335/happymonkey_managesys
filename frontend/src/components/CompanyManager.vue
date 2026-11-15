<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { post, put } from '../api'
import { dict, loadDictionaries } from '../dictionaries'
import type { Company } from '../types'

/** 我方签约主体：签合同、开发票的公司。报价与合同选择签约主体，打印时使用其抬头、税号和收款账户。 */
const dialog = ref(false),
  busy = ref(false),
  editingId = ref('')
const fresh = () => ({
  name: '',
  shortName: '',
  taxNo: '',
  legalRep: '',
  address: '',
  phone: '',
  bankName: '',
  bankAccount: '',
  isDefault: false,
  active: true,
  sort: dict.companies.length + 1,
  note: '',
})
const form = ref(fresh())
onMounted(() => loadDictionaries(true).catch((e) => ElMessage.error((e as Error).message)))
function open(c?: Company) {
  editingId.value = c?.id || ''
  form.value = c
    ? {
        name: c.name,
        shortName: c.short_name || '',
        taxNo: c.tax_no || '',
        legalRep: c.legal_rep || '',
        address: c.address || '',
        phone: c.phone || '',
        bankName: c.bank_name || '',
        bankAccount: c.bank_account || '',
        isDefault: c.is_default,
        active: c.active,
        sort: c.sort,
        note: c.note || '',
      }
    : fresh()
  dialog.value = true
}
async function save() {
  if (!form.value.name.trim()) return ElMessage.warning('请填写公司全称')
  busy.value = true
  try {
    if (editingId.value) await put(`/dictionaries/companies/${editingId.value}`, form.value)
    else await post('/dictionaries/companies', form.value)
    dialog.value = false
    await loadDictionaries(true)
    ElMessage.success('签约主体已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div>
    <div class="inline-note">
      维护我方签合同、开发票的公司。新建报价和合同时选择签约主体（默认取“默认”标记的公司），报价单和合同打印其抬头、税号、地址电话和收款账户。
    </div>
    <el-button type="primary" size="small" class="section-gap" @click="open()">
      <el-icon><Plus /></el-icon>
      新增签约主体
    </el-button>
    <el-table :data="dict.companies" size="small">
      <el-table-column label="公司全称" min-width="220">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.is_default" size="small" type="success" class="gap">默认</el-tag>
          <el-tag v-if="!row.active" size="small" type="info" class="gap">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="简称" prop="short_name" width="100" />
      <el-table-column label="纳税人识别号" prop="tax_no" width="190" />
      <el-table-column label="开户行 / 账号" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ [row.bank_name, row.bank_account].filter(Boolean).join(' ') || '—' }}</template>
      </el-table-column>
      <el-table-column label="合同" prop="contracts" width="70" align="right" />
      <el-table-column label="报价" prop="quotes" width="70" align="right" />
      <el-table-column label="操作" width="70">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="editingId ? '编辑签约主体' : '新增签约主体'" width="620px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="公司全称" required><el-input v-model="form.name" maxlength="200" /></el-form-item>
          <el-form-item label="简称"><el-input v-model="form.shortName" maxlength="30" /></el-form-item>
          <el-form-item label="纳税人识别号"><el-input v-model="form.taxNo" maxlength="30" /></el-form-item>
          <el-form-item label="法定代表人"><el-input v-model="form.legalRep" maxlength="60" /></el-form-item>
          <el-form-item label="电话"><el-input v-model="form.phone" maxlength="60" /></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="999" /></el-form-item>
        </div>
        <el-form-item label="地址"><el-input v-model="form.address" maxlength="300" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="开户银行"><el-input v-model="form.bankName" maxlength="200" /></el-form-item>
          <el-form-item label="银行账号"><el-input v-model="form.bankAccount" maxlength="40" /></el-form-item>
          <el-form-item label="默认签约主体"><el-switch v-model="form.isDefault" /></el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="form.note" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.gap {
  margin-left: 6px;
}
</style>
