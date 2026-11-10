/**
 * 极简 .xlsx 生成器（零依赖）：只做"一张带表头的数据表"这一件事。
 *
 * 为什么自己写：原来的导出是把网页表格存成 .xls，Excel 打开会弹"格式与扩展名不匹配"警告，
 * 数字全是文本不能求和，列宽也不受控（2026-10-13 用户反馈"太粗糙"）。真正的 .xlsx 就是一个 zip
 * 包着几份 XML，表头加粗/冻结/筛选/列宽/数字格式都在 XML 里写明即可，不值得为此引入一个大依赖。
 *
 * zip 用"仅存储"（不压缩）：实现短、无需压缩库；列表导出几百到几千行，体积可以接受。
 */

export type XlsxCell = string | number | null | undefined
export type XlsxNumberFormat = 'number' | 'money'

/** 样式编号，对应下面 STYLES_XML 里 cellXfs 的顺序 */
const STYLE = { text: 1, header: 2, number: 3, money: 4 } as const

const STYLES_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="微软雅黑"/></font><font><b/><sz val="11"/><color rgb="FF254B72"/><name val="微软雅黑"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD8E6F4"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFA4B2C2"/></left><right style="thin"><color rgb="FFA4B2C2"/></right><top style="thin"><color rgb="FFA4B2C2"/></top><bottom style="thin"><color rgb="FFA4B2C2"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="5">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
<xf numFmtId="49" fontId="1" fillId="2" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="常规" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`

function xmlEscape(text: string) {
  // XML 1.0 不允许大部分控制字符，混进去 Excel 会报"文件已损坏"
  return text
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function columnName(index: number) {
  let name = ''
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name
  return name
}

/**
 * 仅 number 按数字写，金额格式由列显式指定。
 * 所有字符串均按文本保存，包括 001.00 这类编码，避免猜金额导致前导零或精度丢失。
 */
function cellXml(ref: string, value: XlsxCell, format?: XlsxNumberFormat) {
  if (typeof value === 'number' && Number.isFinite(value)) return `<c r="${ref}" s="${format === 'money' ? STYLE.money : STYLE.number}"><v>${value}</v></c>`
  const text = value == null ? '' : String(value)
  if (!text) return `<c r="${ref}" s="${STYLE.text}"/>`
  return `<c r="${ref}" s="${STYLE.text}" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(text)}</t></is></c>`
}

/** 估算显示宽度：汉字等全角字符算 2，其余算 1 */
function displayWidth(value: XlsxCell) {
  let width = 0
  for (const ch of value == null ? '' : String(value)) width += ch.charCodeAt(0) > 0xff ? 2 : 1
  return width
}

function sheetXml(headers: string[], rows: XlsxCell[][], formats: XlsxNumberFormat[]) {
  const widths = headers.map(displayWidth)
  for (const row of rows.slice(0, 500)) row.forEach((v, i) => { widths[i] = Math.max(widths[i] || 0, displayWidth(v)) })
  const cols = widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${Math.min(Math.max(w + 3, 8), 50)}" customWidth="1"/>`).join('')
  const headerRow = `<row r="1" ht="22" customHeight="1">${headers.map((hd, i) =>
    `<c r="${columnName(i)}1" s="${STYLE.header}" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(hd)}</t></is></c>`).join('')}</row>`
  const body = rows.map((row, r) =>
    `<row r="${r + 2}">${headers.map((_, c) => cellXml(`${columnName(c)}${r + 2}`, row[c], formats[c])).join('')}</row>`).join('')
  const lastRef = `${columnName(Math.max(headers.length - 1, 0))}${rows.length + 1}`
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="18"/>
<cols>${cols}</cols>
<sheetData>${headerRow}${body}</sheetData>
<autoFilter ref="A1:${lastRef}"/>
</worksheet>`
}

/** 工作表名：Excel 限 31 字且不许含 \ / ? * [ ] : */
function safeSheetName(name: string) {
  return (name.replace(/[\\/?*[\]:]/g, ' ').trim() || '数据').slice(0, 31)
}

// ── zip（仅存储）────────────────────────────────────────────────────────────
const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c >>> 0
  }
  return table
})()

function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff
  for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

function zipStore(files: Array<{ name: string; text: string }>) {
  const encoder = new TextEncoder()
  const parts: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const file of files) {
    const name = encoder.encode(file.name)
    const data = encoder.encode(file.text)
    const crc = crc32(data)
    // 0x0800 = 文件名是 UTF-8；日期固定写 1980-01-01（0x0021），内容相同则文件逐字节相同
    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true); local.setUint16(4, 20, true); local.setUint16(6, 0x0800, true)
    local.setUint16(8, 0, true); local.setUint16(10, 0, true); local.setUint16(12, 0x0021, true)
    local.setUint32(14, crc, true); local.setUint32(18, data.length, true); local.setUint32(22, data.length, true)
    local.setUint16(26, name.length, true); local.setUint16(28, 0, true)
    const dir = new DataView(new ArrayBuffer(46))
    dir.setUint32(0, 0x02014b50, true); dir.setUint16(4, 20, true); dir.setUint16(6, 20, true); dir.setUint16(8, 0x0800, true)
    dir.setUint16(10, 0, true); dir.setUint16(12, 0, true); dir.setUint16(14, 0x0021, true)
    dir.setUint32(16, crc, true); dir.setUint32(20, data.length, true); dir.setUint32(24, data.length, true)
    dir.setUint16(28, name.length, true); dir.setUint32(42, offset, true)
    parts.push(new Uint8Array(local.buffer), name, data)
    central.push(new Uint8Array(dir.buffer), name)
    offset += 30 + name.length + data.length
  }
  const centralSize = central.reduce((sum, part) => sum + part.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, files.length, true); end.setUint16(10, files.length, true)
  end.setUint32(12, centralSize, true); end.setUint32(16, offset, true)
  return [...parts, ...central, new Uint8Array(end.buffer)]
}

/** 生成 .xlsx：第一行表头（加粗、底色、冻结、带筛选），其余为数据，全部带细边框 */
export function buildXlsx(sheetName: string, headers: string[], rows: XlsxCell[][], formats: XlsxNumberFormat[] = []): Blob {
  const files = [
    { name: '[Content_Types].xml', text: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>` },
    { name: '_rels/.rels', text: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>` },
    { name: 'xl/workbook.xml', text: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlEscape(safeSheetName(sheetName))}" sheetId="1" r:id="rId1"/></sheets></workbook>` },
    { name: 'xl/_rels/workbook.xml.rels', text: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>` },
    { name: 'xl/styles.xml', text: STYLES_XML },
    { name: 'xl/worksheets/sheet1.xml', text: sheetXml(headers, rows, formats) }
  ]
  return new Blob(zipStore(files) as BlobPart[], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}
