const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '../src/views/report/salesIncomeCostPrint.ts'), 'utf8')
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const moduleRef = { exports: {} }
let preview = ''
vm.runInNewContext(js, {
  module: moduleRef, exports: moduleRef.exports,
  require: name => name.includes('usePrintPreview') ? { openPrintPreview: html => { preview = html } }
    : name.includes('printExport') ? { escapeHtml: x => String(x) }
      : { fmtMoney: x => Number(x || 0).toFixed(2), fmtQty: x => String(x) },
  location: { origin: 'http://127.0.0.1' }, Date, Number, String
})
const { printIncomeCost, taxRateLabel } = moduleRef.exports
const mixed = { kind: 'RETURN', biz_date: '2026-10-02', doc_no: 'RET', customer: 'Test', source_out_no: 'OUT',
  material_code: 'M', material_name: 'Test', qty: -2, revenue: -200, tax: -19, cost: -120, profit: -80,
  margin: 40, rate_warn: true, mixed_rate: true, min_tax_rate: 6, max_tax_rate: 13, tax_rate: 13 }
const single = { ...mixed, doc_no: 'RET2', mixed_rate: false, min_tax_rate: 6, max_tax_rate: 6, tax_rate: 6 }
assert.equal(taxRateLabel(mixed), '多税率6%–13%')
assert.equal(taxRateLabel(single), '税率6%')
printIncomeCost({ company: { name: 'Test' }, taxRate: 13, startDate: '2026-10-01', endDate: '2026-10-31',
  summary: [], total: { revenue: -300, tax: -25, cost: -180, profit: -120 }, detail: [mixed, single] }, 'Tester')
assert.match(preview, /\(多税率6%–13%\)/)
assert.match(preview, /\(税率6%\)/)
const vue = fs.readFileSync(path.join(__dirname, '../src/views/report/sales-income-cost.vue'), 'utf8')
assert.match(vue, /\{\{ taxRateLabel\(row\) \}\}/, 'screen must render the same accurate rate label')
console.log('PASS mixed and single tax-rate labels on screen and print')
