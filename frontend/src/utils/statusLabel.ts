// 单据工作流状态 → 中文（打印/导出版面用，避免打印出英文状态码）
const DOC_STATUS: Record<string, string> = {
  READY: '待实际交接',
  DRAFT: '草稿',
  PENDING: '待审批',
  PROCESSING: '审批中',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  RECEIVED: '已入库',
  PARTIAL_RECEIVED: '部分入库',
  MATCHED: '已核销',
  PARTIAL_MATCHED: '部分核销',
  INVOICED: '已开票',
  OUTBOUND: '已出库',
  OUT: '已出库',
  PARTIAL_OUT: '部分出库',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
  CANCELED: '已取消'
}

export function docStatusLabel(status: string | null | undefined): string {
  if (!status) return '-'
  return DOC_STATUS[status] || status
}
