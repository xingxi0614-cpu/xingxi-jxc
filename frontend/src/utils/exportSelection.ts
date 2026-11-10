import { toRaw } from 'vue'

/**
 * 列表「勾了哪几行」的登记处：表格(AutoIndexTable)在勾选变化时登记，导出(exportRowsToExcel)时来取。
 * 用表格的数据数组本身当钥匙——页面把同一份 rows 既交给表格显示、又交给导出，两边天然对得上，
 * 所以 30 多个列表页不用各写一套"记住勾选"的代码。列表重新查询/翻页后是新数组，旧勾选自动作废。
 */
const selectedByData = new WeakMap<object, any[]>()

export function rememberSelection(data: unknown, selection: any[]) {
  if (Array.isArray(data)) selectedByData.set(toRaw(data), selection || [])
}

/** 返回这份数据里被勾中的行（保持列表原顺序）；没有勾选、或这份数据不是来自带勾选框的表格，返回 null */
export function selectedRowsOf<T>(rows: T[]): T[] | null {
  const selection = selectedByData.get(toRaw(rows) as object)
  if (!selection?.length) return null
  const picked = new Set(selection.map(row => toRaw(row)))
  const result = rows.filter(row => picked.has(toRaw(row as any)))
  return result.length ? result : null
}
