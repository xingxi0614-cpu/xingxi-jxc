import { ElMessage, ElMessageBox } from 'element-plus'
import { wfApi } from '@/api'

export async function requestCancelApproval(bizType: string, row: any, reload?: () => Promise<any> | any) {
  // 不再要求填写原因，仅做一次确认（取消审批会回退库存/成本，确认后单据退回草稿可改可重提）
  await ElMessageBox.confirm(
    `确定取消「${row.docNo}」的审批吗？取消后该单据退回草稿（可修改后重新提交），已产生的库存/成本会自动回退。`,
    '取消审批',
    { type: 'warning', confirmButtonText: '确定取消审批', cancelButtonText: '再想想' }
  )
  const res: any = await wfApi.cancelRequest({ bizType, bizId: row.id, bizNo: row.docNo, reason: '取消审批' })
  // 发起人就是原审批人时后端一步到位（返回的取消申请已是 APPROVED）：直接退回草稿、无需再去待办
  if (res && (res.status === 'APPROVED' || res.status === 'CANCELLED')) {
    ElMessage.success('已取消审批，单据已退回草稿，库存/成本已自动回退')
  } else {
    ElMessage.success('已提交取消审批申请，等待原审批人处理')
  }
  if (reload) await reload()
}

export async function confirmDeleteDoc(row: any, remove: (id: number) => Promise<any>, reload: () => Promise<any> | any, label = '单据') {
  await ElMessageBox.confirm(`确定删除 ${label} ${row.docNo}？删除后不可恢复。`, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await remove(row.id)
  ElMessage.success('已删除')
  await reload()
}
