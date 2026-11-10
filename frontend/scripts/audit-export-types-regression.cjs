const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const vue = require('vue')
const root = path.join(__dirname, '../src')

function loadTs(file, dependencies = {}) {
  const source = fs.readFileSync(path.join(root, file), 'utf8')
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  new Function('require', 'exports', 'module', js)(name => {
    assert.ok(name in dependencies, `Unexpected import: ${name}`)
    return dependencies[name]
  }, module.exports, module)
  return module.exports
}
const writer = loadTs('utils/xlsxWriter.ts')
const selection = loadTs('utils/exportSelection.ts', { vue })
let captured, copied
global.URL.createObjectURL = blob => { captured = blob; return 'blob:regression' }
global.document = { createElement: () => ({ click() {} }) }
global.setTimeout = () => 0
Object.defineProperty(global, 'navigator', { configurable: true, value: { clipboard: { writeText: async text => { copied = text } } } })
const table = loadTs('utils/tableExport.ts', {
  'element-plus': { ElMessage: { warning() {}, success() {} } },
  './exportSelection': selection, './xlsxWriter': writer, './printExport': {}
})

// Read the actual stored ZIP entries; assertions inspect the XLSX cell types,
// rather than assuming a download name or a formatter call proves correctness.
async function sheet(blob) {
  const bytes = Buffer.from(await blob.arrayBuffer())
  for (let p = 0; bytes.readUInt32LE(p) === 0x04034b50;) {
    const size = bytes.readUInt32LE(p + 18), nameLength = bytes.readUInt16LE(p + 26), extraLength = bytes.readUInt16LE(p + 28)
    const name = bytes.subarray(p + 30, p + 30 + nameLength).toString()
    const start = p + 30 + nameLength + extraLength
    if (name === 'xl/worksheets/sheet1.xml') return bytes.subarray(start, start + size).toString()
    p = start + size
  }
  throw new Error('Worksheet not found')
}
function cell(xml, ref) {
  const result = xml.match(new RegExp(`<c r="${ref}"[^>]*(?:/>|>[\\s\\S]*?</c>)`))
  assert.ok(result, `Missing cell ${ref}`)
  return result[0]
}
function numeric(xml, ref) {
  const c = cell(xml, ref)
  assert.ok(!c.includes('inlineStr'), `${ref} must be an Excel numeric cell`)
  const value = c.match(/<v>(.*?)<\/v>/)
  assert.ok(value, `${ref} must contain a numeric value`)
  return Number(value[1])
}

function readColumns(file) {
  const source = fs.readFileSync(path.join(root, file), 'utf8')
  const expression = source.match(/const exportColumns: ExportColumn\[\] = (\[[\s\S]*?\n\])/)[1]
  const js = ts.transpileModule(`const columns = ${expression}; export { columns }`, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  new Function('exports', 'fmtMoney', 'docStatusLabel', js)(exports, x => Number(x).toFixed(2), x => x)
  return exports.columns
}

function verifyNumericColumnCoverage() {
  const files = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(path.join(dir, e.name)) : [path.join(dir, e.name)])
  let checked = 0
  for (const file of files(path.join(root, 'views')).filter(file => file.endsWith('.vue'))) {
    const source = fs.readFileSync(file, 'utf8')
    if (!source.includes('exportRowsToExcel')) continue
    const setup = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
    const sf = ts.createSourceFile(file, setup, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
    function inExport(node) {
      for (let p = node.parent; p; p = p.parent) {
        if (ts.isVariableDeclaration(p) && (p.name.getText(sf) === 'exportColumns' || p.type?.getText(sf).includes('ExportColumn'))) return true
        if (ts.isFunctionDeclaration(p) && ['exportExcel', 'exportColumns'].includes(p.name?.getText(sf))) return true
      }
      return false
    }
    function visit(node) {
      if (ts.isObjectLiteralExpression(node) && inExport(node)) {
        const properties = new Map(node.properties.filter(ts.isPropertyAssignment).map(p => [p.name.getText(sf), p.initializer.getText(sf)]))
        if (properties.has('label') && /\b(fmtMoney|fmtPrice|fmtInventoryAmount|fmtUnitPrice|money|price)\s*\(/.test(properties.get('value') || '')) {
          checked++
          assert.equal(properties.get('money'), 'true', `${path.relative(root, file)} ${properties.get('label')} must declare money`)
        }
      }
      ts.forEachChild(node, visit)
    }
    visit(sf)
  }
  assert.ok(checked > 50, 'Must cover the existing amount/price export columns across the application')
  return checked
}

;(async () => {
  const strings = ['001.00', '00012.50', '99999999999999999.00', '1,000.00', '-0.00', '=SUM(A1:A2)']
  const textXml = await sheet(writer.buildXlsx('文本', strings.map((_, i) => `文本${i}`), [strings]))
  strings.forEach((value, i) => {
    const c = cell(textXml, `${String.fromCharCode(65 + i)}2`)
    assert.ok(c.includes('t="inlineStr"') && c.includes(`>${value}</t>`), `Keep exact string ${value}`)
  })

  const columns = [
    { label: '编码', key: 'code' },
    { label: '金额', money: true, value: r => r.money },
    { label: '数量', qty: true, value: r => r.qty },
    { label: '税率', numeric: true, key: 'rate' },
    { label: '待录金额', money: true, key: 'placeholder' }
  ]
  const rows = [
    { code: '001.00', money: '1,234.50', qty: '1,001.2345', rate: '13.00', placeholder: '未录付款' },
    { code: '00012.50', money: '-234.50', qty: '2.7655', rate: '0.00', placeholder: null }
  ]
  table.exportRowsToExcel('回归', columns, rows)
  const xml = await sheet(captured)
  assert.ok(cell(xml, 'A2').includes('>001.00</t>'))
  assert.equal(numeric(xml, 'B2') + numeric(xml, 'B3'), 1000, 'Excel SUM reads both monetary values')
  assert.ok(cell(xml, 'B2').includes('s="4"'), 'Money retains the two-decimal number format')
  assert.equal(numeric(xml, 'C2') + numeric(xml, 'C3'), 1004, 'Quantities retain four-place precision and remain summable')
  assert.equal(numeric(xml, 'D2'), 13)
  assert.ok(cell(xml, 'E2').includes('>未录付款</t>'), 'Business placeholders must not silently disappear')
  assert.ok(!cell(xml, 'E3').includes('<v>'), 'Missing amount is not zero')
  await table.copyRowsForPaste(columns, rows)
  assert.ok(copied.includes('1,234.50'), 'Clipboard keeps its existing formatted text')

  const customer = readColumns('views/sales/order/list.vue').find(c => c.label === '客户')
  table.exportRowsToExcel('客户', [customer], [{ customerName: '', customerUnit: '回归客户单位' }, { customerName: '回归姓名', customerUnit: '回归客户单位' }])
  const customerXml = await sheet(captured)
  assert.ok(cell(customerXml, 'A2').includes('>回归客户单位</t>'))
  assert.ok(cell(customerXml, 'A3').includes('>回归姓名</t>'))

  console.log(`PASS XLSX exact text, monetary/quantity SUM, money style, blanks/placeholders, clipboard and customer fallback; ${verifyNumericColumnCoverage()} numeric export formatters covered`)
})().catch(error => { console.error(error); process.exitCode = 1 })
