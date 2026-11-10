import { ElMessageBox } from 'element-plus'
import { outApi } from '@/api'

/**
 * 同一工程重复出库申请提醒(2026-10-16,口径与后端 OutReqDuplicates 一致)。
 *
 * <p>只管【工程类】;同一工程已有未完成(待审批 / 已审批 / 部分出库)的申请时,保存前弹一次提示。
 * <b>只提醒、不拦</b>:选"仍新建"照常保存。提示文字不引用制度条文(用户要求),只说清楚该怎么办。
 * 查询失败不挡保存 —— 这是提醒,不能因为它出问题把正常建单卡住。</p>
 */
export type DupChoice = 'continue' | 'abort' | { revise: any } | { view: any }

const STATUS: Record<string, string> = { PENDING: '待审批', APPROVED: '已审批', PARTIAL_OUT: '部分出库' }
const REVISABLE = ['APPROVED', 'PARTIAL_OUT']

function esc(s: any) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string))
}

/**
 * @param canRevise 电脑端可以直接打开修订/原申请;手机端没有修订页面,只给"仍新建 / 先不建了"
 */
export async function checkDuplicateOreq(opts: {
  category: string; projectId: number | null; projectName?: string; excludeId?: number | null; canRevise: boolean
}): Promise<DupChoice> {
  if (opts.category !== '工程类' || !opts.projectId) return 'continue'
  let list: any[] = []
  try {
    list = (await outApi.oreqOpenOnProject(opts.projectId, opts.excludeId || undefined)) as any || []
  } catch { return 'continue' }
  if (!list.length) return 'continue'
  const revisable = list.find(r => REVISABLE.includes(r.status))
  const items = list.slice(0, 5).map(r => `<li>${esc(r.docNo)}（${esc(STATUS[r.status] || r.status)}）</li>`).join('')
  const more = list.length > 5 ? `<div>…共 ${list.length} 张</div>` : ''
  const name = opts.projectName ? `「${esc(opts.projectName)}」` : '这个工程'
  const hint = opts.canRevise
    ? (revisable ? '材料计划有变化（增补、变更），建议直接在原申请上做修订，不再另建新申请。'
                 : '原申请还在审批中，可以先查看原申请；审批通过后，材料计划有变化可在原申请上做修订。')
    : '材料计划有变化（增补、变更），建议在电脑端的「出库申请」里对原申请做修订，不再另建新申请。'
  const confirmText = opts.canRevise ? (revisable ? '去修订原申请' : '查看原申请') : '先不建了'
  try {
    await ElMessageBox.confirm(
      `<div>${name}已有 <b>${list.length}</b> 张还没完成的出库申请：</div><ul style="margin:6px 0;padding-left:18px">${items}</ul>${more}<div>${hint}</div>`,
      '这个工程已有出库申请',
      { type: 'warning', dangerouslyUseHTMLString: true, distinguishCancelAndClose: true,
        confirmButtonText: confirmText, cancelButtonText: '仍新建' })
  } catch (action) {
    // 点"仍新建" = cancel → 照常保存;点右上角 × 或按 Esc = close → 这次先不保存
    return action === 'cancel' ? 'continue' : 'abort'
  }
  if (!opts.canRevise) return 'abort'
  return revisable ? { revise: revisable } : { view: list[0] }
}
