// 回归：材料出库「关联出库申请制单」的剩余数量小数误差(2026-10-08 本地实测复现)。
// 0.3-0.1 在 JS 里是 0.19999999999999998：页面把这串数字原样显示成"可出/剩余"，
// 手机端再填 0.2 会被判"超过可出数量"、提交按钮置灰(后端 BigDecimal 其实放行)。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const read = p => fs.readFileSync(path.join(__dirname, '../src', p), 'utf8')
const js = ts.transpileModule(read('composables/useWarehouseStock.ts'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const moduleRef = { exports: {} }
vm.runInNewContext(js, { module: moduleRef, exports: moduleRef.exports, require: () => ({ ref: v => ({ value: v }), get: async () => [] }), Math, Number })
const { subQty, exceedsStock } = moduleRef.exports
assert.equal(typeof subQty, 'function', 'useWarehouseStock 必须导出 subQty')

// 相减结果必须是干净的数(接口返回的数量可能是数字也可能是字符串)
assert.equal(subQty(0.3, 0.1), 0.2)
assert.equal(subQty('0.3', '0.1'), 0.2)
assert.equal(subQty(0.3, 0.2), 0.1)
assert.equal(subQty(0.8, 0.1), 0.7)
assert.equal(subQty(1.1, 0.2), 0.9)
assert.equal(subQty(100.3, 0.1), 100.2)
assert.equal(subQty(12.3456, 0.0001), 12.3455, '4 位小数不能丢')
assert.equal(subQty(5, null), 5)
assert.equal(subQty(0.1, 0.3), -0.2, '不够减由调用方自己兜 0')
// 刚好填满剩余量：不许拦
assert.equal(exceedsStock(['0.2'], subQty(0.3, 0.1)), false, '申请 0.3 已出 0.1，再出 0.2 不应判超量')
assert.equal(exceedsStock([0.1], subQty(0.3, 0.2)), false)
assert.equal(exceedsStock([0.7], subQty(0.8, 0.1)), false)
// 真超了：必须拦(哪怕只超万分之一)
assert.equal(exceedsStock(['0.2001'], subQty(0.3, 0.1)), true)
assert.equal(exceedsStock([0.7001], subQty(0.8, 0.1)), true, '0.8-0.1 浮点偏大也不能放过超量')

// 两个页面必须走公用算法，不许各自再写一遍小数相减/比较
const fnBody = (src, name) => {
  const start = src.indexOf('function ' + name + '(')
  assert.notEqual(start, -1, '找不到函数 ' + name)
  const next = src.indexOf('\nfunction ', start + 1)
  return src.slice(start, next === -1 ? start + 600 : next)
}
const rawMinus = /Number\([^)]*\)\s*-\s*/   // Number(x) - … 这种直接相减
const desktop = read('views/outbound/out/list.vue')
for (const name of ['reqRemain', 'lineRemain']) {
  const body = fnBody(desktop, name)
  assert.match(body, /subQty\(/, `电脑端 ${name} 必须用 subQty`)
  assert.doesNotMatch(body, rawMinus, `电脑端 ${name} 不应直接相减`)
}
const createFn = fnBody(desktop, 'createOutFromReq')
assert.match(createFn, /exceedsStock\(\[line\.outQtyInput\], lineRemain\(line\)\)/, '电脑端超量判断必须用 exceedsStock')
assert.doesNotMatch(createFn, /outQtyInput\)\s*>\s*lineRemain/, '电脑端不应直接比较小数')

const mobile = read('views/mobile/MaterialOut.vue')
const remainOf = fnBody(mobile, 'remainOf')
assert.match(remainOf, /subQty\(/, '手机端 remainOf 必须用 subQty')
assert.doesNotMatch(remainOf, /Number\(line\.qty[^)]*\)\s*-/, '手机端 remainOf 不应直接相减')
const over = fnBody(mobile, 'over')
assert.match(over, /exceedsStock\(/, '手机端超量判断必须用 exceedsStock')
assert.doesNotMatch(over, /\)\s*>\s*Number\(/, '手机端不应直接比较小数')
console.log('PASS out-request remain qty decimal (desktop + mobile)')
