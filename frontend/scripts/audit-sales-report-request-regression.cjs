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
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const pending = []
const unmount = []
let printed = 0, exported = 0
const context = {
  ...vue, Date, Number, String, console,
  onMounted() {}, onBeforeUnmount(fn) { unmount.push(fn) },
  useRouter: () => ({ push() {} }), useRoute: () => ({ query: {} }), useUserStore: () => ({ userInfo: { name: 'Tester' } }),
  salesApi: { reportIncomeCost: (query) => {
    const request = deferred()
    pending.push({ query: { ...query }, ...request })
    return request.promise
  } },
  printIncomeCost: () => { printed++ }, exportIncomeCostXls: () => { exported++ },
  makeLineSummary: () => () => [], fmtMoney: x => x, fmtQty: x => x, pct: x => x, KIND_NAME: 'Test'
}
vm.runInNewContext(`${js}\nglobalThis.__probe = { data, companies, companyId, range, loadError, loading, load, doPrint, doExport }`, context)
const p = context.__probe
const result = (id) => ({ company: { id }, companies: [{ id }], detail: [{ id }], total: {} })

;(async () => {
  const old = p.load()
  const latest = p.load()
  pending[1].resolve(result(2)); await latest
  pending[0].resolve(result(1)); await old
  assert.equal(p.data.value.company.id, 2, 'old success must not replace current report')
  assert.equal(p.companyId.value, 2)
  assert.equal(p.companies.value[0].id, 2)
  assert.equal(p.loading.value, false)

  const oldFailure = p.load()
  const newest = p.load()
  pending[2].reject(new Error('stale failure')); await oldFailure
  assert.equal(p.loadError.value, '', 'old failure must not hide current query')
  assert.equal(p.loading.value, true, 'old finally must not stop current spinner')
  pending[3].resolve(result(2)); await newest
  p.doPrint(); p.doExport()
  assert.equal(printed, 1); assert.equal(exported, 1)

  const dateRequest = p.load()
  p.range.value = ['2026-09-01', '2026-09-30']
  assert.equal(p.data.value, null, 'changing the period invalidates printable data immediately')
  p.doPrint(); p.doExport()
  assert.equal(printed, 1); assert.equal(exported, 1)
  pending[4].resolve(result(9)); await dateRequest
  assert.equal(p.data.value, null, 'old period response must remain discarded')
  assert.equal(p.loading.value, false)

  const nextPeriod = p.load()
  assert.equal(pending[5].query.startDate, '2026-09-01')
  pending[5].resolve(result(2)); await nextPeriod
  assert.equal(p.data.value.company.id, 2)

  const leaving = p.load()
  assert.equal(unmount.length, 1)
  unmount[0]()
  pending[6].resolve(result(8)); await leaving
  assert.equal(p.data.value, null, 'unmounted view must discard a pending result')
  console.log('PASS report request order, stale failure/finally, period invalidation, print/export and unmount')
})().catch(error => { console.error(error); process.exitCode = 1 })
