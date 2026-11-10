export interface ExportColumn<T = any> {
  label: string
  key?: keyof T | string
  value?: (row: T) => unknown
  /**
   * 数量列：导出 Excel 时一律按数字写(可求和)，打印仍用 value 的格式化文字。
   * 🔴 2026-10-15:报表页自己的 fmtQty 输出 "7"/"1,087.5" 这类文字，只有恰好两位小数的才被认成数字，
   *    同一列数字和文本混着，Excel 求不了和——数量列必须标 qty。
   */
  qty?: boolean
  /** 金额列：Excel 写为数字并显示两位小数；普通字符串不会按内容猜类型。 */
  money?: boolean
  /** 其他数字列（如税率）：允许把格式化数字字符串写成可计算的数字。 */
  numeric?: boolean
}

import { ElMessage, ElMessageBox } from 'element-plus'
import { selectedRowsOf } from './exportSelection'
import { buildXlsx, type XlsxCell } from './xlsxWriter'
import { exportPrintDocumentXls, printDocument, type PaperAction, type PaperSize, type PrintColumn } from './printExport'

/** 原始值：数字保持数字，交给 xlsx 按数字写（可求和）；其余一律转成文本 */
function rawValue<T>(row: T, col: ExportColumn<T>): XlsxCell {
  const raw = col.value ? col.value(row) : (col.key ? (row as any)[col.key] : '')
  if (col.qty) return toNumber(raw)
  if (col.money || col.numeric) {
    const number = toNumber(raw)
    // “未录付款”等有业务含义的占位仍保留文字，不能把它误导成 0 或丢掉。
    return number === '' && typeof raw === 'string' ? raw : number
  }
  return typeof raw === 'number' ? raw : (raw == null ? '' : String(raw))
}

/** "1,087.5" → 1087.5；空值/"-" 这类占位写成空格子，不写成 0(避免把"没有"导成"零") */
function toNumber(raw: unknown): XlsxCell {
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : ''
  const text = raw == null ? '' : String(raw).replace(/,/g, '').trim()
  if (!text || !/^-?\d+(\.\d+)?$/.test(text)) return ''
  const number = Number(text)
  return Number.isFinite(number) ? number : ''
}

function cellValue<T>(row: T, col: ExportColumn<T>) {
  const raw = col.value ? col.value(row) : (col.key ? (row as any)[col.key] : '')
  // 复制保留原金额文字；既有 qty 列仍沿用原来的数字转换。
  return String((col.qty ? toNumber(raw) : raw) ?? '')
}

function safeFileName(name: string) {
  return name.replace(/[\\/:*?"<>|]/g, '_')
}

/**
 * 分页列表「导出全部查询结果」用：页面传一个"按我自己的查询条件取第几页"的函数，写法和它 load() 里那句一致。
 * 🔴 2026-10-20 用户立项：以前一行不勾时只导出当前这一页（20~100 条），不是全部查询结果，财务拿去对账会少数据。
 */
export interface ExportAll<T> {
  /** 查询结果总条数（页面上「共 N 条」那个数） */
  total: number
  /** 按当前查询条件取第 current 页、每页 size 条，返回接口原样结果（{ records, total }） */
  fetch: (page: { current: number; size: number }) => Promise<any>
  /** 页面在前端又筛了一道的（如借料「只看未还清」），对取回的全部记录用同一条件再筛 */
  filter?: (row: T) => boolean
}
/** 一次最多导出多少条：再多浏览器生成文件会很慢，也不像是要导出的量（后端每页最多 500 条） */
export const EXPORT_ALL_MAX = 20000
const EXPORT_PAGE_SIZE = 500

async function fetchAllRows<T>(all: ExportAll<T>): Promise<T[]> {
  const want = Math.min(all.total, EXPORT_ALL_MAX)
  const out: T[] = []
  for (let current = 1; out.length < want; current++) {
    const r: any = await all.fetch({ current, size: EXPORT_PAGE_SIZE })
    const recs: T[] = r?.records || []
    if (!recs.length) break
    out.push(...recs)
  }
  const rows = out.slice(0, want)
  return all.filter ? rows.filter(all.filter) : rows
}

/**
 * 全站列表「导出Excel」的统一入口。
 * - 导哪些记录：列表里勾了哪几行就只导哪几行（按列表顺序）；一行没勾 = 导出【全部查询结果】
 *   （分页列表传了 all 时按同样的查询条件把每一页都取回来；没传 all 的报表页本来就是全部结果，导出当前显示的全部）。
 *   勾选由表格组件统一登记（见 exportSelection.ts），页面不用自己传。
 * - 导成什么：真正的 .xlsx（表头加粗冻结、带筛选、带边框、数量金额是可求和的数字）。
 */
export async function exportRowsToExcel<T>(
  fileName: string,
  columns: ExportColumn<T>[],
  rows: T[],
  sheetName = '数据',
  all?: ExportAll<T>
) {
  if (!rows.length) { ElMessage.warning('当前列表没有可导出的记录'); return }
  const picked = selectedRowsOf(rows)
  let exportRows = picked || rows
  let fetched = false
  if (!picked && all && all.total > rows.length) {
    if (all.total > EXPORT_ALL_MAX) {
      try {
        await ElMessageBox.confirm(`查询结果共 ${all.total} 条，一次最多导出前 ${EXPORT_ALL_MAX} 条。建议缩小查询条件分批导出，或继续导出前 ${EXPORT_ALL_MAX} 条。`,
          '导出条数过多', { confirmButtonText: `导出前 ${EXPORT_ALL_MAX} 条`, cancelButtonText: '取消', type: 'warning' })
      } catch { return }
    }
    const tip = ElMessage({ message: `正在取全部 ${Math.min(all.total, EXPORT_ALL_MAX)} 条查询结果…`, type: 'info', duration: 0 })
    try { exportRows = await fetchAllRows(all) } finally { tip.close() }
    fetched = true
    if (!exportRows.length) { ElMessage.warning('没有可导出的记录'); return }
  }
  const body = exportRows.map(row => columns.map(col => rawValue(row, col)))
  const formats = columns.map(col => col.money ? 'money' as const : 'number' as const)
  const url = URL.createObjectURL(buildXlsx(sheetName, columns.map(col => col.label), body, formats))
  const a = document.createElement('a')
  a.href = url
  a.download = `${safeFileName(fileName)}.xlsx`
  a.click()
  // 立刻回收地址在个别浏览器上会让下载落空，稍等一下再回收
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
  ElMessage.success(picked ? `已导出勾选的 ${picked.length} 条记录`
    : `未勾选记录，已导出${fetched ? '查询结果' : '当前列表'}全部 ${exportRows.length} 条`)
}

export async function copyRowsForPaste<T>(columns: ExportColumn<T>[], rows: T[]) {
  const header = columns.map(col => col.label).join('\t')
  const body = rows.map(row => columns.map(col => cellValue(row, col).replace(/\t/g, ' ').replace(/\r?\n/g, ' ')).join('\t'))
  const text = [header, ...body].join('\n')
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.left = '-9999px'
  document.body.appendChild(ta)
  ta.focus()
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
}

export function rowsForExport<T>(rows: T[], selectedRows: T[] = []) {
  return selectedRows.length ? selectedRows : rows
}

export function printOrExportRows<T>(
  title: string,
  columns: ExportColumn<T>[],
  rows: T[],
  action: PaperAction,
  paper: PaperSize,
  subtitle = '查询结果'
) {
  const printColumns: PrintColumn<T>[] = columns.map(col => ({
    label: col.label,
    key: col.key,
    value: col.value ? (row: T) => col.value?.(row) : undefined
  }))
  const options = {
    title,
    subtitle: `${subtitle} · ${paper}`,
    fileName: `${title}-${paper}`,
    paper,
    orientation: 'landscape' as const,
    columns: printColumns,
    rows
  }
  action === 'print' ? printDocument(options) : exportPrintDocumentXls(options)
}
