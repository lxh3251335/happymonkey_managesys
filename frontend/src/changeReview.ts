import { ElMessage, ElMessageBox } from 'element-plus'
import { post } from './api'
import type { ContractChange } from './types'

/** 让用户填写原因（会保留在操作记录中）；取消时返回 null。 */
export async function askReason(title: string) {
  try {
    const r = await ElMessageBox.prompt('请填写原因（保留在操作记录中）', title, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => !!v?.trim() || '请填写原因',
    })
    return r.value.trim()
  } catch {
    return null
  }
}

/**
 * 审批、驳回或撤回一条合同变更申请。合同详情和“变更审批”列表共用。
 * 返回是否办理成功（用户取消或出错时为 false）。
 */
export async function reviewChange(x: ContractChange, action: 'approve' | 'reject' | 'withdraw') {
  let note: string | null = ''
  if (action === 'reject') {
    note = await askReason('驳回变更申请')
    if (!note) return false
  } else if (action === 'approve') {
    try {
      await ElMessageBox.confirm('批准后合同内容立即按申请修改。', '批准变更', { type: 'warning' })
    } catch {
      return false
    }
  }
  try {
    await post(`/contracts/changes/${x.id}/${action}`, action === 'withdraw' ? {} : { note })
    ElMessage.success({ approve: '已批准，合同已更新', reject: '已驳回', withdraw: '已撤回' }[action])
    return true
  } catch (e) {
    ElMessage.error((e as Error).message)
    return false
  }
}
