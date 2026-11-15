<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { post, put } from '../api'
import { dict, loadDictionaries } from '../dictionaries'
import type { City, Scenario } from '../types'

/** 应用场景、城市字典维护（管理员）。改名同步到已有产品 / 项目；停用后已有记录保留原值，新记录不能再选。 */
const props = defineProps<{ kind: 'scenario' | 'city' }>()
const isCity = computed(() => props.kind === 'city')
const keyword = ref(''),
  dialog = ref(false),
  busy = ref(false),
  editingId = ref('')
const form = ref({ name: '', province: '', aliases: '', sort: 1, active: true, note: '' })

onMounted(() => loadDictionaries(true).catch((e) => ElMessage.error((e as Error).message)))

const rows = computed(() => {
  const k = keyword.value.trim()
  const list = (isCity.value ? dict.cities : dict.scenarios) as (City | Scenario)[]
  return list.filter((r) => !k || JSON.stringify(r).includes(k))
})
function open(r?: City | Scenario) {
  editingId.value = r?.id || ''
  const c = r as City | undefined,
    s = r as Scenario | undefined
  form.value = r
    ? {
        name: r.name,
        province: c?.province || '',
        aliases: c?.aliases || '',
        sort: r.sort,
        active: r.active,
        note: s?.note || '',
      }
    : { name: '', province: '', aliases: '', sort: rows.value.length + 1, active: true, note: '' }
  dialog.value = true
}
async function save() {
  if (!form.value.name.trim()) return ElMessage.warning('请填写名称')
  busy.value = true
  const base = isCity.value ? '/dictionaries/cities' : '/dictionaries/scenarios'
  const body = isCity.value
    ? {
        name: form.value.name,
        province: form.value.province,
        aliases: form.value.aliases,
        sort: form.value.sort,
        active: form.value.active,
      }
    : { name: form.value.name, sort: form.value.sort, active: form.value.active, note: form.value.note }
  try {
    if (editingId.value) await put(`${base}/${editingId.value}`, body)
    else await post(base, body)
    dialog.value = false
    await loadDictionaries(true)
    ElMessage.success('已保存，相关记录已同步')
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
      <template v-if="isCity">
        项目的城市从这里选择。别名填区县、镇或商圈（如“大兴、通州”），新建门店未填城市时按门店名称自动识别；录入别名也会归到所属城市。
      </template>
      <template v-else>标准产品的应用场景从这里选择，如小象前置仓、快乐猴、冷库、配电房。</template>
      改名会同步到所有已有记录；不再使用的请停用。
    </div>
    <div class="dict-bar">
      <el-input
        v-model="keyword"
        clearable
        :prefix-icon="Search"
        :placeholder="isCity ? '搜索城市、省份或别名' : '搜索应用场景'"
      />
      <el-button type="primary" size="small" @click="open()">
        <el-icon><Plus /></el-icon>
        新增{{ isCity ? '城市' : '应用场景' }}
      </el-button>
    </div>
    <el-table :data="rows" size="small" max-height="560">
      <el-table-column :label="isCity ? '城市' : '应用场景'" min-width="140">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="!row.active" size="small" type="info" class="gap">已停用</el-tag>
        </template>
      </el-table-column>
      <template v-if="isCity">
        <el-table-column label="省份" prop="province" width="100" />
        <el-table-column label="别名（区县、镇、商圈）" prop="aliases" min-width="260" show-overflow-tooltip />
        <el-table-column label="项目" prop="projects" width="70" align="right" />
      </template>
      <template v-else>
        <el-table-column label="说明" prop="note" min-width="220" show-overflow-tooltip />
        <el-table-column label="产品" prop="products" width="70" align="right" />
      </template>
      <el-table-column label="排序" prop="sort" width="60" align="right" />
      <el-table-column label="操作" width="70">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialog"
      :title="`${editingId ? '编辑' : '新增'}${isCity ? '城市' : '应用场景'}`"
      width="480px"
      append-to-body
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item :label="isCity ? '城市名称' : '应用场景'" required>
            <el-input v-model="form.name" :maxlength="isCity ? 50 : 60" />
          </el-form-item>
          <el-form-item v-if="isCity" label="省份"><el-input v-model="form.province" maxlength="30" /></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sort" :min="0" :max="9999" /></el-form-item>
          <el-form-item label="状态">
            <el-switch v-model="form.active" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-form-item v-if="isCity" label="别名（逗号或顿号分隔）">
          <el-input
            v-model="form.aliases"
            type="textarea"
            :rows="2"
            maxlength="1000"
            placeholder="如：大兴、通州、顺义"
          />
        </el-form-item>
        <el-form-item v-else label="说明"><el-input v-model="form.note" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<style scoped>
.dict-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 12px 0;
}
.dict-bar .el-input {
  max-width: 280px;
}
.gap {
  margin-left: 6px;
}
</style>
