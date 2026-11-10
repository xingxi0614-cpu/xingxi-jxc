import { wfApi } from '@/api'

const actionMap: Record<string, string> = {
  SUBMIT: '提交',
  APPROVE: '通过',
  REJECT: '驳回',
  CANCEL: '撤销',
  RECALL: '撤回'
}

const nodeMap: Record<string, string> = {
  MATERIAL_ACCOUNTANT: '材料会计',
  FINANCE_MANAGER: '财务部门负责人',
  DEPUTY_GENERAL_MANAGER: '分管副总',
  GENERAL_MANAGER: '总经理',
  TECH_MANAGER: '技术部经理',
  ENGINEERING_LEADER: '工程副总',
  OPERATION_LEADER: '运营副总',
  MARKET_LEADER: '市场副总',
  BRANCH_MANAGER: '网点负责人',
  AREA_MANAGER: '片区经理',
  FINANCE_REVIEW: '材料会计',
  MATERIAL_ACCOUNTANT_REVIEW: '材料会计'
}

export async function approvalRecordNote(docNo: string | undefined, baseNote = '') {
  if (!docNo) return baseNote
  try {
    const records = await wfApi.records(docNo) as any[]
    if (!records?.length) return baseNote
    const lines = records.map((record, index) => {
      const node = nodeMap[record.node] || record.node || '制单'
      const action = actionMap[record.action] || record.action || ''
      const approver = record.approverName || '-'
      const time = record.createdAt ? String(record.createdAt).replace('T', ' ').slice(0, 16) : ''
      const comment = record.comment ? `（${record.comment}）` : ''
      return `${index + 1}. ${node}${action ? ' ' + action : ''}：${approver}${time ? ' ' + time : ''}${comment}`
    })
    return [baseNote, '审批记录：', ...lines].filter(Boolean).join('\n')
  } catch {
    return baseNote
  }
}
