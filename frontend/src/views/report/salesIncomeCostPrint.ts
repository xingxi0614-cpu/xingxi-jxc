/**
 * 销售收入成本明细表 —— 打印版与 Excel 导出。
 *
 * 为什么不用通用的 printDocument：它只支持"表头字段 + 一张明细表"，这张表要"按月汇总 + 明细"两段、
 * 底部签字栏(制单人/财务负责人，用户 2026-10-01 定)，所以单独排版，但页眉版式沿用全站
 * (标题居中、logo+公司名靠右、左上打印时间)。
 *
 * 打印宽度：A4 横版可打印区 277mm；明细表用固定列宽(合计 100%)，单号/日期不折行、物料名允许折行，
 * 原型阶段用 Chrome 实打 PDF 核对过毛利率列不出界。
 *
 * Excel：用户要"导出后能自己改"，所以金额/数量写成真数字(带千分位格式)，不像通用导出那样全转文本，
 * 拿到 Excel 里可以直接改数、加公式。
 */
import { openPrintPreview } from '@/composables/usePrintPreview'
import { escapeHtml } from '@/utils/printExport'
import { fmtMoney, fmtQty } from '@/utils/format'

export interface IncomeCostData {
  company: { id: number; name: string }
  taxRate: number
  startDate: string
  endDate: string
  summary: any[]
  total: any
  detail: any[]
}

export const KIND_NAME = '商品销售'
export const reportTitle = () => `${KIND_NAME}收入成本明细表`
const SCOPE_NOTE = '口径：仅含销售模块。收入=已审批销售发票不含税金额(按开票日期归月)；成本=对应销售出库单结转成本(移动加权平均)；'
  + '退货按退货日期冲减当月。材料出库/领料(维修改造等)不在本表；采购发票与入库差异见"采购成本差异调整表"。'

export const pct = (v: any) => (v === null || v === undefined || v === '' ? '—' : `${Number(v).toFixed(1)}%`)
export const taxRateLabel = (r: any) => r.mixed_rate
  ? `多税率${Number(r.min_tax_rate)}%–${Number(r.max_tax_rate)}%`
  : `税率${Number(r.tax_rate)}%`
const ym = (d: string) => `${d.slice(0, 4)}年${Number(d.slice(5, 7))}月`
export const periodText = (d: IncomeCostData) =>
  d.startDate.slice(0, 7) === d.endDate.slice(0, 7) ? ym(d.startDate) : `${ym(d.startDate)} 至 ${ym(d.endDate)}`

function stamp() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const matText = (r: any) => `${r.material_code || ''} ${r.material_name || ''}${r.spec ? `(${r.spec})` : ''}`

/** 单元格渲染器：打印版输出格式化文本，Excel 版输出真数字(由 class 决定 Excel 数字格式) */
type Cell = (type: 'money' | 'qty' | 'count' | 'pct', v: any) => string
const printCell: Cell = (type, v) => `<td class="r">${type === 'money' ? fmtMoney(v) : type === 'qty' ? fmtQty(v) : type === 'pct' ? pct(v) : v}</td>`
// Excel 只认单元格上的内联 mso-number-format(全站导出同一写法)；金额=千分位两位小数的真数字，可直接改/加公式
const XLS_FMT = { money: '\\#\\,\\#\\#0\\.00', qty: '0\\.\\#\\#\\#\\#', count: '0' } as const
const xlsCell: Cell = (type, v) => {
  if (type === 'pct') return `<td style="mso-number-format:'\\@';text-align:right">${pct(v)}</td>`
  const n = v === null || v === undefined || v === '' ? '' : String(Number(v))
  return `<td style="mso-number-format:'${XLS_FMT[type]}';text-align:right">${n}</td>`
}
/** 文本格：打印版用 class 控制折行；Excel 版强制按文本(防单号/日期被 Excel 自动转成数字或日期) */
const txt = (forPrint: boolean, cls: string, html: string) =>
  forPrint ? `<td class="${cls}">${html}</td>` : `<td style="mso-number-format:'\\@'">${html}</td>`

function summaryRows(d: IncomeCostData, c: Cell) {
  const line = (r: any, cls = '') => `<tr class="${cls}"><td style="mso-number-format:'\\@'">${escapeHtml(r.month)}</td>${c('count', r.invoice_count)}
    ${c('money', r.revenue)}${Number(r.return_revenue) ? c('money', r.return_revenue) : '<td class="r">—</td>'}
    ${c('money', r.tax)}${c('money', r.total)}${c('money', r.cost)}${c('money', r.profit)}${c('pct', r.margin)}</tr>`
  return d.summary.map(r => line(r)).join('') + line(d.total, 'total')
}
const SUM_HEAD = `<tr><th style="width:12%">月份</th><th>发票张数</th><th>收入(不含税)</th><th>其中:退货冲减</th><th>税额(原票)</th><th>价税合计</th><th>销售成本</th><th>毛利</th><th style="width:8%">毛利率</th></tr>`

/** 单号太长放不下时只在"-"处折行(<wbr>)，不会从数字中间断开；各公司单号前缀长短不一(ABC/DEMO) */
const docNo = (v: any, forPrint: boolean) => (forPrint ? escapeHtml(v).replace(/-/g, '-<wbr>') : escapeHtml(v))

function detailRows(d: IncomeCostData, c: Cell, forPrint: boolean) {
  const br = forPrint ? '<br>' : ' '
  const rows = d.detail.map(r => {
    const doc = r.kind === 'RETURN' ? `[退货]${br}${docNo(r.doc_no, forPrint)}` : `${docNo(r.doc_no, forPrint)}${r.invoice_no ? `${br}发票号 ${escapeHtml(r.invoice_no)}` : ''}`
    // 非 13% 的历史发票：打印版在税额下注明；Excel 版注在单号格(税额格要保持纯数字可计算)
    const warn = r.rate_warn ? `(${taxRateLabel(r)})` : ''
    const tax = forPrint && warn ? `<td class="r">${fmtMoney(r.tax)}<br>${warn}</td>` : c('money', r.tax)
    return `<tr>${txt(forPrint, 'nw', escapeHtml(r.biz_date))}${txt(forPrint, 'doc', doc + (!forPrint && warn ? ' ' + warn : ''))}
    ${txt(forPrint, '', escapeHtml(r.customer))}${txt(forPrint, 'doc', docNo(r.source_out_no, forPrint))}${txt(forPrint, '', escapeHtml(matText(r)))}
    ${c('qty', r.qty)}${c('money', r.revenue)}${tax}${c('money', r.cost)}${c('money', r.profit)}${c('pct', r.margin)}</tr>`
  }).join('')
  const t = d.total
  return rows + `<tr class="total"><td colspan="6">合计</td>${c('money', t.revenue)}${c('money', t.tax)}${c('money', t.cost)}${c('money', t.profit)}${c('pct', t.margin)}</tr>`
}
const DET_COLS = [8, 14, 6, 14, 16, 4.5, 9, 8, 7.5, 7.5, 5.5] // 合计 100%
const DET_HEAD = `<tr><th>日期</th><th>单号</th><th>客户</th><th>来源出库单</th><th>物料</th><th>数量</th><th>收入(不含税)</th><th>税额(原票)</th><th>成本</th><th>毛利</th><th>毛利率</th></tr>`

export function printIncomeCost(d: IncomeCostData, printer: string) {
  const html = `<!doctype html><html><head><meta charset="UTF-8" /><title>${escapeHtml(reportTitle())}</title><style>
    @page { size: A4 landscape; margin: 10mm; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: "Microsoft YaHei", "SimSun", Arial, sans-serif; color: #111; background: #eef2f7; font-size: 11px; }
    .sheet { width: 277mm; margin: 0 auto; padding: 6mm 0; background: #fff; }
    .top { color: #6b7280; font-size: 10.5px; }
    .head { display: flex; align-items: center; padding: 2px 0 6px; }
    .side { flex: 1 1 0; min-width: 0; }
    .brand { flex: 1 1 0; display: flex; justify-content: flex-end; align-items: center; gap: 7px; }
    .brand img { height: 26px; }
    .company { font-size: 13px; font-weight: 600; }
    .title { flex: 0 0 auto; font-size: 19px; font-weight: 700; letter-spacing: 2px; }
    .meta { display: flex; justify-content: space-between; margin: 2px 0 4px; }
    h3 { font-size: 12px; margin: 10px 0 4px; }
    table { width: 100%; border-collapse: collapse; table-layout: fixed; }
    th, td { border: 1px solid #000; padding: 3px 4px; font-size: 10.5px; line-height: 1.35; word-break: break-all; vertical-align: middle; }
    th { font-weight: 700; text-align: center; background: #f3f4f6; }
    td.r { text-align: right; white-space: nowrap; word-break: normal; font-variant-numeric: tabular-nums; }
    td.nw { white-space: nowrap; word-break: normal; }
    td.doc { word-break: keep-all; overflow-wrap: normal; }
    tr.total td { font-weight: 700; }
    tr { page-break-inside: avoid; }
    thead { display: table-header-group; }
    .sign { display: flex; justify-content: space-between; margin-top: 22px; font-size: 12px; padding: 0 24px; }
    .sign span { display: inline-block; width: 130px; border-bottom: 1px solid #000; margin-left: 6px; }
    .note { margin-top: 10px; font-size: 10px; color: #333; line-height: 1.5; }
    @media print { body { background: #fff; } .sheet { width: auto; padding: 0; } th { -webkit-print-color-adjust: exact; print-color-adjust: exact; } }
  </style></head><body><div class="sheet">
    <div class="top">打印时间 ${stamp()}　打印人 ${escapeHtml(printer)}</div>
    <div class="head"><div class="side"></div><div class="title">${escapeHtml(reportTitle())}</div>
      <div class="brand"><img src="${location.origin}/logo-mark.svg" alt="" onerror="this.style.display='none'" /><span class="company">${escapeHtml(d.company.name)}</span></div></div>
    <div class="meta"><span>开票期间：${periodText(d)}</span><span>税率：${Number(d.taxRate)}%　　金额单位：元</span></div>
    <h3>一、按月汇总</h3><table><thead>${SUM_HEAD}</thead><tbody>${summaryRows(d, printCell)}</tbody></table>
    <h3>二、明细</h3><table><colgroup>${DET_COLS.map(w => `<col style="width:${w}%">`).join('')}</colgroup>
      <thead>${DET_HEAD}</thead><tbody>${detailRows(d, printCell, true)}</tbody></table>
    <div class="sign"><div>制单人：<span></span></div><div>财务负责人：<span></span></div><div>日期：<span></span></div></div>
    <div class="note">${escapeHtml(SCOPE_NOTE)}</div>
  </div></body></html>`
  openPrintPreview(html, reportTitle(), 'A4')
}

/** Excel：数值单元格写真数字 + 千分位格式，导出后可直接改数/加公式 */
export function exportIncomeCostXls(d: IncomeCostData) {
  const fmt = `<style>
    th, td { border: .5pt solid #999; padding: 4px 6px; } th { background: #eef3f8; } tr.total td { font-weight: bold; }
  </style>`
  const blank = '<tr><td colspan="11" style="border:0"></td></tr>'
  const html = `<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="UTF-8" />${fmt}</head><body>
    <table>
      <tr><td colspan="11" style="font-size:16px;font-weight:bold;text-align:center;border:0">${escapeHtml(reportTitle())}</td></tr>
      <tr><td colspan="4" style="border:0">编制单位：${escapeHtml(d.company.name)}</td><td colspan="4" style="border:0">开票期间：${periodText(d)}</td><td colspan="3" style="border:0">税率：${Number(d.taxRate)}%　金额单位：元</td></tr>
      ${blank}<tr><td colspan="11" style="font-weight:bold;border:0">一、按月汇总</td></tr>
      ${SUM_HEAD}${summaryRows(d, xlsCell)}
      ${blank}<tr><td colspan="11" style="font-weight:bold;border:0">二、明细</td></tr>
      ${DET_HEAD}${detailRows(d, xlsCell, false)}
      ${blank}<tr><td colspan="4" style="border:0">制单人：</td><td colspan="4" style="border:0">财务负责人：</td><td colspan="3" style="border:0">日期：</td></tr>
      <tr><td colspan="11" style="border:0">${escapeHtml(SCOPE_NOTE)}</td></tr>
    </table></body></html>`
  const blob = new Blob(['\ufeff', html], { type: 'application/vnd.ms-excel;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${reportTitle()}_${d.company.name}_${d.startDate}_${d.endDate}.xls`.replace(/[\\/:*?"<>|]/g, '_')
  a.click()
  URL.revokeObjectURL(url)
}
