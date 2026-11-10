import { openPrintPreview } from '@/composables/usePrintPreview'
import { useUserStore } from '@/stores/user'
import { useViewCompanyStore } from '@/stores/viewCompany'

export type PaperSize = 'A4' | 'B5' | 'A5'
export type PaperAction = 'print' | 'export'
export type PrintOrientation = 'portrait' | 'landscape'

export interface PrintColumn<T = any> {
  label: string
  key?: keyof T | string
  value?: (row: T, index: number) => unknown
  width?: string
  align?: 'left' | 'center' | 'right'
}

export interface PrintField {
  label: string
  value: unknown
  span?: number
}

export interface SignatureBlock {
  label: string
  lines?: string[]
}

export interface PrintQr {
  label?: string
  docNo?: string
  url: string
  dataUrl: string
}

export interface PrintDocumentOptions<T = any> {
  title: string
  subtitle?: string
  fileName?: string
  paper?: PaperSize
  orientation?: 'portrait' | 'landscape'
  fields?: PrintField[]
  columns: PrintColumn<T>[]
  rows: T[]
  signatures?: SignatureBlock[]
  note?: string
  qr?: PrintQr
  /** 单据自己的归属组织 id：抬头按它印所属公司名(管理员打印别家公司的单据时才印得对)。报表类不传。 */
  orgId?: number | string | null
}

const paperNames: Record<PaperSize, string> = {
  A4: 'A4',
  B5: 'B5',
  A5: 'A5'
}

export function paperLabel(paper: PaperSize) {
  return paperNames[paper] || paper
}

/** 打印页眉时间：统一为 年-月-日 时:分（不含秒、不用斜杠）。 */
function printStamp() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function escapeHtml(value: unknown) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function safeFileName(name: string) {
  return name.replace(/[\\/:*?"<>|]/g, '_')
}

function cellValue<T>(row: T, col: PrintColumn<T>, index: number) {
  const raw = col.value ? col.value(row, index) : (col.key ? (row as any)[col.key] : '')
  return raw == null ? '' : String(raw)
}

function buildFieldTable(fields: PrintField[], paper: PaperSize) {
  if (!fields.length) return ''
  const pairPerRow = paper === 'A5' ? 1 : 2
  const rows: string[] = []
  let current: string[] = []
  let usedPairs = 0
  const flush = () => {
    if (!current.length) return
    while (usedPairs < pairPerRow) {
      current.push('<th class="empty"></th><td class="empty"></td>')
      usedPairs += 1
    }
    rows.push(`<tr>${current.join('')}</tr>`)
    current = []
    usedPairs = 0
  }
  fields.forEach(field => {
    const value = String(field.value ?? '')
    const fullRow = Number(field.span || 1) >= 2 || value.length > 18
    if (fullRow) {
      flush()
      rows.push(`<tr><th>${escapeHtml(field.label)}</th><td colspan="${pairPerRow * 2 - 1}">${escapeHtml(value)}</td></tr>`)
      return
    }
    if (usedPairs >= pairPerRow) flush()
    current.push(`<th>${escapeHtml(field.label)}</th><td>${escapeHtml(value)}</td>`)
    usedPairs += 1
  })
  flush()
  const colgroup = Array.from({ length: pairPerRow }, () => '<col class="meta-label" /><col class="meta-value" />').join('')
  return `<table class="meta"><colgroup>${colgroup}</colgroup>${rows.join('')}</table>`
}

function buildLineTable<T>(columns: PrintColumn<T>[], rows: T[]) {
  const header = columns.map(col => {
    const width = col.width ? ` style="width:${escapeHtml(col.width)}"` : ''
    return `<th${width}>${escapeHtml(col.label)}</th>`
  }).join('')
  const body = rows.length
    ? rows.map((row, index) => {
      const cells = columns.map(col => {
        const align = col.align ? ` ${col.align}` : ''
        return `<td class="${align.trim()}">${escapeHtml(cellValue(row, col, index))}</td>`
      }).join('')
      return `<tr>${cells}</tr>`
    }).join('')
    : `<tr><td colspan="${columns.length}" class="center empty-line">暂无明细</td></tr>`
  return `<table class="lines"><thead><tr>${header}</tr></thead><tbody>${body}</tbody></table>`
}

function buildSignatureTable(signatures: SignatureBlock[]) {
  if (!signatures.length) return ''
  const cell = (block: SignatureBlock) => {
    const lines = (block.lines?.length ? block.lines : ['签字：', '日期：'])
      .map(line => `<div>${escapeHtml(line)}</div>`)
      .join('')
    return `<td><strong>${escapeHtml(block.label)}</strong>${lines}</td>`
  }
  const perRow = 4
  // ≤4 个：一行（现有单据不变）；>4 个：每行最多 4 个，末行补空格对齐（如领料单 4+3）
  if (signatures.length <= perRow) {
    return `<table class="sign"><tr>${signatures.map(cell).join('')}</tr></table>`
  }
  const rows: string[] = []
  for (let i = 0; i < signatures.length; i += perRow) {
    const chunk = signatures.slice(i, i + perRow)
    let cells = chunk.map(cell).join('')
    for (let p = chunk.length; p < perRow; p++) cells += '<td class="empty"></td>'
    rows.push(`<tr>${cells}</tr>`)
  }
  return `<table class="sign">${rows.join('')}</table>`
}

function buildQrBlock(qr?: PrintQr) {
  if (!qr) return ''
  const label = qr.label ? `<div class="qr-label">${escapeHtml(qr.label)}</div>` : ''
  const docNo = qr.docNo ? `<div class="qr-no">${escapeHtml(qr.docNo)}</div>` : ''
  return `<div class="print-qr">
    <div class="qr-text">
      ${label}
      ${docNo}
      <div class="qr-url">${escapeHtml(qr.url)}</div>
    </div>
    <img src="${escapeHtml(qr.dataUrl)}" alt="单据二维码" />
  </div>`
}

export function buildPrintDocumentHtml<T>(options: PrintDocumentOptions<T>, withLogo = true) {
  const paper = options.paper || 'A4'
  const orientation = options.orientation || 'portrait'
  const pageMargin = paper === 'A5' ? '8mm' : paper === 'B5' ? '9mm' : '10mm'
  // 纸张可用尺寸（已扣页边距）；横向时宽高对调，避免宽表(报表)挤在纵向窄页里
  const portraitWidth = paper === 'A5' ? '132mm' : paper === 'B5' ? '164mm' : '190mm'
  const portraitHeight = paper === 'A5' ? '194mm' : paper === 'B5' ? '232mm' : '277mm'
  const sheetWidth = orientation === 'landscape' ? portraitHeight : portraitWidth
  const sheetMinHeight = orientation === 'landscape' ? portraitWidth : portraitHeight
  const sheetPadding = paper === 'A5' ? '6mm' : '8mm 9mm'
  const titleSize = paper === 'A5' ? '18px' : '21px'
  const cellPadding = paper === 'A5' ? '4px 5px' : '5px 7px'
  const cellSize = paper === 'A5' ? '10.5px' : '11.5px'
  const labelWidth = paper === 'A5' ? '22mm' : '24mm'
  const signHeight = paper === 'A5' ? '52px' : '66px'
  // 抬头公司名的取法，按优先级：
  // ① 顶栏选了「只看某家公司」→ 就印那家(此时页面上所有数据都是它的)；
  // ② 单据带了归属组织 → 印单据自己所属的公司(管理员看全部公司时，打谁的单印谁的名)；
  // ③ 都没有(管理员看全部公司时打跨公司报表) → 印登录人所在公司，对管理员即集团名。
  // 普通账号只能看到自己公司的数据，①不适用、②③结果相同，所以不受影响。
  const viewCompany = useViewCompanyStore()
  const company = viewCompany.selectedName
    || viewCompany.companyOfOrg(options.orgId)
    || useUserStore().userInfo?.companyName || useUserStore().userInfo?.orgName || ''
  const note = options.note ? `<div class="note">${escapeHtml(options.note)}</div>` : ''
  return `<!doctype html>
<html>
<head>
  <meta charset="UTF-8" />
  <title>${escapeHtml(options.title)}</title>
  <style>
    @page { size: ${paper} ${orientation}; margin: ${pageMargin}; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: "Microsoft YaHei", "SimSun", Arial, sans-serif; color: #111827; background: #eef2f7; font-size: 12px; }
    .sheet { width: ${sheetWidth}; min-height: ${sheetMinHeight}; margin: 0 auto; padding: ${sheetPadding}; background: #fff; border: 1px solid #d1d5db; box-shadow: 0 12px 30px rgba(15, 23, 42, .10); }
    .doc-top { display: flex; justify-content: flex-start; align-items: center; color: #6b7280; font-size: 11px; line-height: 1.4; margin-bottom: 2px; }
    /* 页眉一行：标题绝对居中，公司名+logo 靠右（左右两翼等宽 flex 保证标题不偏） */
    .head-wrap { display: flex; align-items: center; padding: 2px 0 8px; }
    .head-side { flex: 1 1 0; min-width: 0; }
    .brand { flex: 1 1 0; display: flex; justify-content: flex-end; align-items: center; gap: 7px; min-width: 0; }
    .brand img { height: ${paper === 'A5' ? '22px' : '28px'}; width: auto; flex: 0 0 auto; }
    .company { font-size: ${paper === 'A5' ? '11.5px' : '13px'}; font-weight: 600; color: #1f2937; letter-spacing: .5px; text-align: left; line-height: 1.3; }
    .title { flex: 0 0 auto; text-align: center; font-size: ${titleSize}; font-weight: 700; letter-spacing: 1px; }
    .subtitle { text-align: center; color: #4b5563; font-size: 11px; margin-bottom: 7px; }
    .print-qr { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 6px 0 8px; padding: 7px 9px; border: 1px solid #d1d5db; background: #f9fafb; }
    .print-qr img { width: 72px; height: 72px; flex: 0 0 auto; border: 1px solid #e5e7eb; background: #fff; }
    .qr-text { min-width: 0; line-height: 1.5; color: #374151; }
    .qr-label { font-size: 11px; color: #4b5563; }
    .qr-no { font-size: 15px; font-weight: 700; color: #111827; }
    .qr-url { font-size: 9px; color: #6b7280; word-break: break-all; }
    table { width: 100%; border-collapse: collapse; table-layout: fixed; margin-top: 8px; }
    th, td { border: 1px solid #9ca3af; padding: ${cellPadding}; font-size: ${cellSize}; line-height: 1.42; word-break: normal; overflow-wrap: anywhere; vertical-align: middle; mso-number-format: "\\@"; }
    th { background: #f3f4f6; font-weight: 700; color: #111827; white-space: nowrap; }
    /* 数字/日期/单位等右对齐与居中列永不折行(名称/备注等左对齐长文本列才允许换行) */
    td.right, td.center { white-space: nowrap; }
    .meta { margin-top: 8px; }
    .meta-label { width: ${labelWidth}; }
    .meta th { text-align: left; background: #f8fafc; white-space: nowrap; }
    .meta td { min-height: 25px; color: #111827; }
    .meta .empty { background: #fff; border-left: 0; border-right: 0; }
    .lines th { text-align: center; }
    .lines td { min-height: 26px; }
    .lines tbody tr { page-break-inside: avoid; }
    .right { text-align: right; }
    .center { text-align: center; }
    .sign { margin-top: 8px; }
    .sign td { height: ${signHeight}; vertical-align: top; }
    .sign strong { display: block; margin-bottom: 8px; }
    .sign div { line-height: 1.65; }
    .note { margin-top: 8px; color: #374151; font-size: 11px; line-height: 1.65; white-space: pre-line; }
    @media print {
      html, body { width: auto; height: auto; background: #fff; }
      .sheet { width: 100%; min-height: 0; padding: 0; border: 0; box-shadow: none; }
      body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    }
  </style>
</head>
<body>
  <div class="sheet">
    <div class="doc-top"><span>打印时间 ${printStamp()}</span></div>
    <div class="head-wrap">
      <div class="head-side"></div>
      <div class="title">${escapeHtml(options.title)}</div>
      <div class="brand">${withLogo ? `<img src="${location.origin}/logo-mark.svg" alt="" onerror="this.style.display='none'" />` : ''}${company ? `<span class="company">${escapeHtml(company)}</span>` : ''}</div>
    </div>
    ${buildFieldTable(options.fields || [], paper)}
    ${buildLineTable(options.columns, options.rows)}
    ${buildSignatureTable(options.signatures || [])}
    ${note}
  </div>
</body>
</html>`
}

export function exportPrintDocumentXls<T>(options: PrintDocumentOptions<T>) {
  // 导出 Excel 不带 logo 图片（外链图片会触发 Excel 安全提示）
  const html = buildPrintDocumentHtml(options, false)
  const blob = new Blob(['\ufeff', html], { type: 'application/vnd.ms-excel;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${safeFileName(options.fileName || options.title)}.xls`
  a.click()
  URL.revokeObjectURL(url)
}

export function printDocument<T>(options: PrintDocumentOptions<T>) {
  // 改为应用内「预览 → 打印」：先按所选纸张渲染版面供核对，再由用户在预览框点「打印」出单。
  const html = buildPrintDocumentHtml(options)
  openPrintPreview(html, options.title, options.paper || 'A4')
  return true
}
