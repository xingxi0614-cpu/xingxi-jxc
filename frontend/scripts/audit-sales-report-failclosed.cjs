const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')

const source = fs.readFileSync(path.join(__dirname, '../src/views/report/sales-income-cost.vue'), 'utf8')
const setup = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(setup)
const js = ts.transpileModule(setup.replace(/^import .*$/gm, ''), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
}).outputText
let fail = false
let printed = 0
let exported = 0
const context = { ...vue, onMounted() {}, onBeforeUnmount() {}, Date, Number, String, console,
  useRouter: () => ({ push() {} }), useRoute: () => ({ query: {} }), useUserStore: () => ({ userInfo: { name: 'Tester' } }),
  salesApi: { reportIncomeCost: async () => {
    if (fail) throw new Error('销售收入成本报表存在跨月审批反序：退货单 RET 与发票 INV；请财务人工处理；报表暂停签字')
    return { company: { id: 1 }, companies: [{ id: 1 }], detail: [], total: {} }
  } },
  printIncomeCost: () => { printed++ }, exportIncomeCostXls: () => { exported++ },
  makeLineSummary: () => () => [], fmtMoney: x => x, fmtQty: x => x, pct: x => x, KIND_NAME: 'Test'
}
vm.runInNewContext(`${js}\nglobalThis.__probe = { data, loadError, load, doPrint, doExport }`, context)
const probe = context.__probe
;(async () => {
  await probe.load()
  assert.ok(probe.data.value)
  fail = true
  const rejected = probe.load()
  assert.equal(probe.data.value, null, 'stale signed report must clear before the failing request completes')
  await rejected
  assert.match(probe.loadError.value, /退货单 RET.*发票 INV/)
  assert.match(probe.loadError.value, /人工处理.*暂停签字/)
  probe.doPrint()
  probe.doExport()
  assert.equal(printed, 0)
  assert.equal(exported, 0)
  assert.match(source, /<el-alert v-if="loadError"[^>]*:title="loadError"/)
  assert.match(source, /<el-button :disabled="!data" @click="doExport"/)
  assert.match(source, /<el-button type="primary" :disabled="!data" @click="doPrint"/)
  console.log('PASS report rejection displays the actionable anomaly and blocks stale print/export')
})().catch(error => { console.error(error); process.exitCode = 1 })
