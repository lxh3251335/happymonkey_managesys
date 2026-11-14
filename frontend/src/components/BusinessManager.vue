<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { post, put } from '../api'
import { businesses, loadBusinesses } from '../business'
import type { BusinessGroup, BusinessLine } from '../types'

const groupDialog = ref(false),
  lineDialog = ref(false),
  busy = ref(false),
  groupId = ref(''),
  lineId = ref('')
const groupForm = ref({ name: '', sort: 1, active: true })
const lineForm = ref({ groupId: '', name: '', shortName: '', sort: 1, active: true, note: '' })

onMounted(() => loadBusinesses(true).catch((e) => ElMessage.error((e as Error).message)))

function openGroup(g?: BusinessGroup) {
  groupId.value = g?.id || ''
  groupForm.value = g
    ? { name: g.name, sort: g.sort, active: g.active }
    : { name: '', sort: businesses.groups.length + 1, active: true }
  groupDialog.value = true
}
function openLine(g: BusinessGroup, l?: BusinessLine) {
  lineId.value = l?.id || ''
  lineForm.value = l
    ? {
        groupId: l.group_id,
        name: l.name,
        shortName: l.short_name || '',
        sort: l.sort,
        active: l.active,
        note: l.note || '',
      }
    : { groupId: g.id, name: '', shortName: '', sort: g.lines.length + 1, active: true, note: '' }
  lineDialog.value = true
}
async function saveGroup() {
  if (!groupForm.value.name.trim()) return ElMessage.warning('请填写集团名称')
  busy.value = true
  try {
    if (groupId.value) await put(`/businesses/groups/${groupId.value}`, groupForm.value)
    else await post('/businesses/groups', groupForm.value)
    groupDialog.value = false
    await loadBusinesses(true)
    ElMessage.success('客户集团已保存')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function saveLine() {
  if (!lineForm.value.name.trim()) return ElMessage.warning('请填写业务线名称')
  busy.value = true
  try {
    const body = { ...lineForm.value, shortName: lineForm.value.shortName || null }
    if (lineId.value) await put(`/businesses/lines/${lineId.value}`, body)
    else await post('/businesses/lines', body)
    lineDialog.value = false
    await loadBusinesses(true)
    ElMessage.success('业务线已保存，相关项目已同步')
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
      客户集团（如美团、京东、阿里巴巴）下设业务线（如小象前置仓、快乐猴超市、京东折扣店、盒马鲜生）。项目、项目类型、经营总览都按业务线归类。
      业务线改名会同步到所有项目；不再使用的业务线请停用（已有项目保留原值，新项目不能再选）。简称用于录入和导入时识别，如“小象”。
    </div>
    <el-button type="primary" size="small" class="section-gap" @click="openGroup()">
      <el-icon><Plus /></el-icon>
      新增客户集团
    </el-button>
    <div v-for="g in businesses.groups" :key="g.id" class="group-block">
      <div class="group-head">
        <strong>{{ g.name }}</strong>
        <el-tag v-if="!g.active" size="small" type="info">已停用</el-tag>
        <span class="muted">{{ g.lines.length }} 条业务线</span>
        <span class="spacer"></span>
        <el-button link type="primary" @click="openLine(g)">新增业务线</el-button>
        <el-button link @click="openGroup(g)">编辑集团</el-button>
      </div>
      <el-table :data="g.lines" size="small" empty-text="暂无业务线">
        <el-table-column label="业务线" min-width="150">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="!row.active" size="small" type="info" class="gap">已停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="简称" prop="short_name" width="110" />
        <el-table-column label="说明" prop="note" min-width="200" show-overflow-tooltip />
        <el-table-column label="项目" prop="projects" width="70" align="right" />
        <el-table-column label="项目类型" prop="project_types" width="90" align="right" />
        <el-table-column label="排序" prop="sort" width="60" align="right" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLine(g, row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="groupDialog" :title="groupId ? '编辑客户集团' : '新增客户集团'" width="440px">
      <el-form label-position="top">
        <el-form-item label="集团名称" required>
          <el-input v-model="groupForm.name" maxlength="60" placeholder="如：京东、阿里巴巴" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="排序"><el-input-number v-model="groupForm.sort" :min="0" :max="999" /></el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="groupForm.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="groupDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="lineDialog" :title="lineId ? '编辑业务线' : '新增业务线'" width="520px">
      <div v-if="lineId" class="inline-note">修改名称会同步更新所有使用该业务线的项目和项目类型。</div>
      <el-form label-position="top">
        <el-form-item label="所属客户集团" required>
          <el-select v-model="lineForm.groupId">
            <el-option v-for="g in businesses.groups" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="业务线名称" required>
            <el-input v-model="lineForm.name" maxlength="30" placeholder="如：京东折扣店、盒马鲜生" />
          </el-form-item>
          <el-form-item label="简称（可选）">
            <el-input v-model="lineForm.shortName" maxlength="30" placeholder="如：盒马" />
          </el-form-item>
          <el-form-item label="排序"><el-input-number v-model="lineForm.sort" :min="0" :max="999" /></el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="lineForm.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-form-item label="说明"><el-input v-model="lineForm.note" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lineDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="saveLine">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.group-block {
  margin-top: 14px;
  border: 1px solid #e4eae0;
  border-radius: 8px;
  overflow: hidden;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #f6f9f4;
  font-size: 13px;
}
.group-head strong {
  font-size: 14px;
}
.spacer {
  flex: 1;
}
.gap {
  margin-left: 6px;
}
</style>
