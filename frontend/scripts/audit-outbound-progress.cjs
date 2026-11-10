const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const source = fs.readFileSync(path.join(__dirname, '../src/utils/outboundProgress.ts'), 'utf8')
const moduleRef = { exports: {} }
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText,
  { module: moduleRef, exports: moduleRef.exports })
const { grossOutQty, netOutQty } = moduleRef.exports
for (const [label, row, gross, net] of [
  ['部分退料后还要再领', { outQty: 6, reissueQty: 4, grossOutQty: 10, returnQty: 4, netOutQty: 6 }, 10, 6],
  ['全部退回', { outQty: 0, reissueQty: 10, grossOutQty: 10, returnQty: 10, netOutQty: 0 }, 10, 0],
  ['再次领取后累计超过计划但净额合法', { outQty: 10, reissueQty: 4, grossOutQty: 14, returnQty: 4, netOutQty: 10 }, 14, 10],
  ['混合再领和不再领退料', { outQty: 6, reissueQty: 4, grossOutQty: 10, returnQty: 6, netOutQty: 4 }, 10, 4],
  ['兼容接口切换前的累计回补字段', { outQty: 6, reissueQty: 4, returnQty: 4 }, 10, 6],
  ['新接口实际流水优先于旧进度', { outQty: 999, reissueQty: 999, grossOutQty: 10, returnQty: 10, netOutQty: 0 }, 10, 0],
  ['没有退料', { outQty: 3, returnQty: 0 }, 3, 3],
]) {
  assert.equal(grossOutQty(row), gross, label+'累计出库')
  assert.equal(netOutQty(row), net, label+'净出库')
}
console.log('PASS outbound actual quantities: partial/full/mixed return and reissue')
