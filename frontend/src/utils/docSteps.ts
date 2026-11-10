/**
 * 单据步骤条的文案与进度，集中在这里算（2026-10-06 定先做盘点单/退料单/出库申请）。
 *
 * 为什么集中：用户反复问"建完单之后下一步是什么"。每种单据的步骤和"轮到谁"写在一处，
 * 改口径只改这里；页面只负责把结果交给 components/DocStepGuide.vue 显示。
 *
 * 注意：这里只管"怎么提示"，不管"能不能做"——能不能做永远由后端判断。
 * 审批人不写死角色名（各公司审批节点不同），只说"等待审批"。
 */
export interface StepGuide {
  steps: string[]
  /** 当前所在步骤的下标；等于 steps.length 表示全部完成 */
  active: number
  /** 当前步骤是否为异常态（被驳回） */
  bad?: boolean
  tone?: 'info' | 'warning' | 'success'
  /** 加粗的开头，如"下一步：录入实盘数。" */
  lead: string
  text: string
}

const n = (v: any) => Number(v || 0)
const fmt = (v: number) => String(Math.round(v * 10000) / 10000)

/** 盘点单：未完成期间仓库是冻结的，这是最容易卡住人的地方，所以每一步都提醒 */
export function stocktakeGuide(doc: any): StepGuide | null {
  if (!doc) return null
  const steps = ['建盘点单', '录入实盘数', '提交', '财务确认', '完成并解冻']
  const lines: any[] = doc.lines || []
  const filled = lines.filter(l => l.actualQty !== null && l.actualQty !== undefined && l.actualQty !== '').length
  const frozen = '盘点期间这个仓库已冻结，不能入库、出库、转库，也会挡住月末结账，请尽快做完。'
  switch (doc.status) {
    case 'DRAFT':
      return filled === 0
        ? { steps, active: 1, tone: 'warning', lead: '下一步：录入实盘数。', text: `可以在下面逐行填，也可以"导出Excel"盘完后用"导入实盘数"回填。${frozen}` }
        : { steps, active: 2, tone: 'warning', lead: '下一步：提交财务确认。', text: `已录 ${filled} / ${lines.length} 行（留空的行视为未盘、不调整）。${frozen}` }
    case 'REJECTED':
      return { steps, active: 2, bad: true, tone: 'warning', lead: '被财务驳回，下一步：改正实盘数后重新提交。', text: `${doc.rejectReason ? '驳回原因：' + doc.rejectReason + '。' : ''}${frozen}` }
    case 'SUBMITTED':
      return { steps, active: 3, tone: 'info', lead: '在等：财务确认。', text: '确认后系统自动生成盘盈盘亏调整并解冻仓库。确认之前仓库仍然冻结。' }
    case 'APPROVED':
      return { steps, active: steps.length, tone: 'success', lead: '已完成。', text: '库存已按盘点结果调整，仓库已解冻，可以正常收发料和月末结账。' }
    default:
      return null
  }
}

/** 退料单：审批通过的那一刻材料才回库 */
export function returnGuide(doc: any): StepGuide | null {
  if (!doc) return null
  const steps = ['仓管建退料单', '提交', '财务审批', '材料回库']
  switch (doc.status) {
    case 'DRAFT':
      return { steps, active: 1, tone: 'warning', lead: '下一步：仓管提交审批。', text: '审批通过之前，材料还不算回到库存，工程成本也没有冲减。' }
    case 'REJECTED':
      return { steps, active: 1, bad: true, tone: 'warning', lead: '被驳回，下一步：', text: '退料数量不能修改，请删除这张退料单后按正确数量重新建单。' }
    case 'PENDING':
      return { steps, active: 2, tone: 'info', lead: '在等：财务审批。', text: '审批通过的那一刻材料回库，并按原出库单价冲减工程成本。' }
    case 'APPROVED':
      return { steps, active: steps.length, tone: 'success', lead: '已完成，材料已回库。', text: doc.sourceReqNo ? `提醒：来源出库申请 ${doc.sourceReqNo} 在"工程项目材料计划执行表"里的待执行数量会相应增加；如工程不再领用，请采购修订申请数量。` : '工程成本已按原出库单价冲减。' }
    default:
      return null
  }
}

/** 出库申请（仓库发料模式）。转交/自提模式有自己的领料面板，不显示这条。 */
export function outReqGuide(doc: any): StepGuide | null {
  if (!doc || doc.fulfillmentMode === 'TRANSFER') return null
  const steps = ['填申请', '提交', '审批', '仓管发料', '领完']
  const left = n(doc.totalQty) - n(doc.outQty)
  switch (doc.status) {
    case 'DRAFT':
      return { steps, active: 1, tone: 'warning', lead: '下一步：提交审批。', text: '申请只是用料计划，不占库存；提交并审批通过后仓管才能发料。' }
    case 'REJECTED':
      return { steps, active: 1, bad: true, tone: 'warning', lead: '被驳回，下一步：', text: '点"编辑"按审批意见修改后重新提交。' }
    case 'PENDING':
      return { steps, active: 2, tone: 'info', lead: '在等：审批。', text: '审批通过后，仓管在"材料出库"里对这张申请发料。' }
    case 'APPROVED':
      return { steps, active: 3, tone: 'info', lead: '在等：仓管发料。', text: '如果账面库存不足，仓管需要先办采购入库才能发料。' }
    case 'PARTIAL_OUT':
      return { steps, active: 3, tone: 'warning', lead: `已发一部分，还有 ${fmt(left)} 没发。`, text: '如工程不再需要剩余数量，请采购点"修订"把数量改小，这张申请才会结束。' }
    case 'OUT':
      return { steps, active: steps.length, tone: 'success', lead: '已全部发料。', text: '如工地有剩余材料，请仓管在"退料单"里办理退料。' }
    default:
      return null
  }
}
