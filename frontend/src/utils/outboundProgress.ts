/** 实际出库/退料用于展示；outQty仍是扣除可再领退料后的额度进度。 */
export function grossOutQty(row: Record<string, any>): number {
  if (row.grossOutQty != null) return Number(row.grossOutQty)
  return Number(row.outQty || 0) + Number(row.reissueQty || 0)
}

export function netOutQty(row: Record<string, any>): number {
  return row.netOutQty != null ? Number(row.netOutQty) : grossOutQty(row) - Number(row.returnQty || 0)
}
