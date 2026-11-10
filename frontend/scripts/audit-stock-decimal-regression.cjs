// 回归：材料出库「当前库存」提示的小数误报(复查发现)。
// 0.1+0.2 在 JS 里是 0.30000000000000004，直接和库存 0.3 比会把"刚好够"报成"超库存"。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const read = p => fs.readFileSync(path.join(__dirname, '../src', p), 'utf8')
const js = ts.transpileModule(read('composables/useWarehouseStock.ts'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const moduleRef = { exports: {} }
vm.runInNewContext(js, { module: moduleRef, exports: moduleRef.exports, require: () => ({ ref: v => ({ value: v }), get: async () => [] }), Math, Number })
const { sumQty, exceedsStock } = moduleRef.exports

// 刚好够：不许报
assert.equal(exceedsStock(['0.1', '0.2'], 0.3), false, '0.1+0.2 对库存 0.3 不应报不足')
assert.equal(exceedsStock([1.1, 2.2], 3.3), false, '1.1+2.2 对库存 3.3 不应报不足')
assert.equal(exceedsStock([0.1, 0.2, 0.3, 0.4], 1), false)
assert.equal(exceedsStock(['8', '12'], 20), false)
// 真超了：必须报（哪怕只超万分之一）
assert.equal(exceedsStock(['0.1', '0.2001'], 0.3), true)
assert.equal(exceedsStock([0.7, 0.1], 0.7999), true, '0.7+0.1 浮点偏小也不能漏报')
assert.equal(exceedsStock([5], 0), true)
// 空行/未填不算数量
assert.equal(exceedsStock(['', null, undefined, '3'], 3), false)
assert.equal(sumQty(['0.1', '0.2']), 0.3)
assert.equal(sumQty([1.1, 2.2]), 3.3)
// 两个页面必须走公用算法，不许各自再写一遍小数相加
for (const page of ['views/outbound/out/list.vue', 'views/mobile/MaterialOut.vue']) {
  const src = read(page)
  const fn = src.slice(src.indexOf('function stockTip'), src.indexOf('function stockTip') + 900)
  assert.match(fn, /exceedsStock\(/, page + ' 的库存提示必须用 exceedsStock')
  assert.doesNotMatch(fn, /\.reduce\(/, page + ' 不应自己累加小数')
}
console.log('PASS stock tip decimal rounding (desktop + mobile)')
