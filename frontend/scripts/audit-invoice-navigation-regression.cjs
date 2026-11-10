const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const vue = require('vue')
const vueRouter = require('vue-router')
const pinia = require('pinia')
const { parse, compileScript } = require('@vue/compiler-sfc')
const root = path.join(__dirname, '../src')

function evaluate(source, dependencies = {}) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  new Function('require', 'module', 'exports', js)(name => {
    if (name.endsWith('.vue')) return { default: {} }
    assert.ok(name in dependencies, `Unexpected import ${name}`)
    return dependencies[name]
  }, module, module.exports)
  return module.exports
}
const navigation = evaluate(fs.readFileSync(path.join(root, 'utils/invoiceNavigation.ts'), 'utf8'))
const storage = new Map()
global.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
const { useTabsStore } = evaluate(fs.readFileSync(path.join(root, 'stores/tabs.ts'), 'utf8'), { vue, pinia })
const errors = [], calls = [], instances = new Map()
const materials = [{ id: 1, code: 'REVIEW-MAT', name: '回归物料' }]
const api = new Proxy({}, { get: (_, method) => async (...args) => {
  calls.push([method, ...args])
  if (method === 'page' || method === 'invoicePage') return { records: [], total: 0 }
  if (method === 'allMaterials') return materials
  if (method === 'allSuppliers') return [{ id: 7, name: '回归供应商' }]
  if (method === 'outAvailableInvoice') return [{ id: 1, docNo: 'REVIEW-OUT-001' }]
  if (method === 'estimatesForInvoicing') return [{ id: 10, docNo: 'REVIEW-RECV-001' }]
  if (method === 'detail') return { lines: [{ id: 100, materialId: 1, qty: 2, invoicedQty: 0, unitPrice: 10 }] }
  return []
} })
const mocks = {
  vue, 'vue-router': vueRouter,
  'element-plus': { ElMessage: { warning() {}, success() {} }, ElMessageBox: {} },
  '@/api': { salesApi: api, masterApi: api, invoiceApi: api, recvApi: api },
  '@/utils/routeQuery': { applyRouteQuery() {} }, '@/utils/docActions': {},
  '@/stores/user': { useUserStore: () => ({ hasFinanceAccessOrAdmin: () => true, hasFinanceAccess: () => true }) },
  '@/utils/format': { fmtMoney: String, fmtPrice: String, fmtQty: String, fmtDate: String, today: () => '2026-10-16' },
  '@/utils/tableSort': { makeSortHandler: () => () => {} },
  '@/utils/tableSummary': { makeLineSummary: () => () => [] },
  '@/utils/notify': { showError: error => errors.push(error) },
  '@/utils/tableExport': {}, '@/utils/statusLabel': {}, '@/utils/printExport': {}, '@/utils/docQr': {}, '@/utils/docGuard': {},
  '@/icons': {}, '@/utils/invoiceNavigation': navigation,
  '@/composables/useUnsavedGuard': { useUnsavedGuard: () => ({ beforeClose() {}, cancel() {} }) },
  // 2026-10-17 列表状态页签：本测试只验跳转开票，页签数字给个空实现
  '@/composables/useStatusCounts': { useStatusCounts: () => ({ counts: {}, refresh() {}, clear() {} }) }
}
function invoiceComponent(file) {
  const sfc = parse(fs.readFileSync(path.join(root, file), 'utf8'))
  const component = evaluate(compileScript(sfc.descriptor, { id: file }).content, mocks).default
  const setup = component.setup
  component.setup = (props, context) => {
    const state = setup(props, context)
    instances.set(state.entryFullPath, state)
    return state
  }
  component.render = () => vue.h('invoice')
  return component
}
// Call the real report/estimate entry function, not a reimplementation of it.
function entryFunction(file, router) {
  const setup = parse(fs.readFileSync(path.join(root, file), 'utf8')).descriptor.scriptSetup.content
  const sf = ts.createSourceFile(file, setup, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const fn = sf.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'goInvoice')
  assert.ok(fn, `${file} must have its entry handler`)
  const js = ts.transpileModule(`${fn.getText(sf)}\nreturn goInvoice`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
  return new Function('router', 'navigateToInvoice', js)(router, navigation.navigateToInvoice)
}
const renderer = vue.createRenderer({
  createElement: type => ({ type, children: [], parent: null }),
  createText: text => ({ type: 'text', text, parent: null }),
  createComment: text => ({ type: 'comment', text, parent: null }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text }, patchProp() {},
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const i = anchor ? parent.children.indexOf(anchor) : -1
    if (i < 0) parent.children.push(node); else parent.children.splice(i, 0, node)
  },
  remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); node.parent = null }
})
const Report = { render: () => vue.h('report') }
const router = vueRouter.createRouter({ history: vueRouter.createMemoryHistory(), routes: [
  { path: '/report/sales-income-cost', component: Report },
  { path: '/invoice/estimate', component: Report },
  { path: '/sale/invoice', component: invoiceComponent('views/sales/invoice/list.vue') },
  { path: '/invoice/list', component: invoiceComponent('views/invoice/list.vue') }
] })
let rejectNext = false, redirectNext = false
router.beforeEach(() => {
  if (rejectNext) { rejectNext = false; return false }
  if (redirectNext) { redirectNext = false; return '/report/sales-income-cost' }
})
const store = pinia.createPinia()
pinia.setActivePinia(store)
const tabs = useTabsStore()
router.afterEach((to, _from, failure) => { if (!failure) tabs.addFromRoute(to) })
const App = { render: () => vue.h(vueRouter.RouterView, null, {
  default: ({ Component, route }) => vue.h(vue.KeepAlive, { max: 20 }, {
    default: () => Component ? vue.h(Component, { key: route.fullPath }) : null
  })
}) }
const app = renderer.createApp(App)
app.use(router); app.use(store)
async function flush() { for (let n = 0; n < 24; n++) { await Promise.resolve(); await vue.nextTick() } }

;(async () => {
  await router.push('/report/sales-income-cost')
  await router.isReady()
  app.mount({ children: [], parent: null })
  for (const test of [
    { report: '/report/sales-income-cost', target: '/sale/invoice?fromOut=REVIEW-OUT-001', entry: 'views/report/sales-income-cost.vue', row: { doc_no: 'REVIEW-OUT-001' }, check: s => assert.equal(s.pickedOut.value?.docNo, 'REVIEW-OUT-001') },
    { report: '/invoice/estimate', target: '/invoice/list?createForSupplier=7&recvNo=REVIEW-RECV-001', entry: 'views/invoice/estimate.vue', row: { supplierId: 7, docNo: 'REVIEW-RECV-001' }, check: s => { assert.equal(s.form.supplierId, 7); assert.equal(s.picker.keyword, 'REVIEW-RECV-001'); assert.equal(s.picker.visible, true) } }
  ]) {
    await router.push(test.target) // An initial bookmarked/deep URL still works.
    await flush()
    const state = instances.get(test.target)
    assert.ok(state.createVisible.value, 'Initial deep link opens its form')
    test.check(state)
    state.createVisible.value = false
    if (state.picker) state.picker.visible = false
    await router.push(test.report); await flush()
    await router.push(test.target); await flush() // ordinary PageTabs navigation
    assert.equal(state.createVisible.value, false, 'Ordinary tab return must not reopen a consumed form')
    const click = entryFunction(test.entry, router)
    for (let n = 0; n < 3; n++) {
      await router.push(test.report); await flush()
      await click(test.row); await flush()
      assert.equal(router.currentRoute.value.fullPath, test.target, 'No nonce/query URL change')
      assert.equal(instances.get(test.target), state, 'Repeated same-document clicks reuse the same KeepAlive instance')
      assert.equal(state.createVisible.value, true, 'Each deliberate report click opens the form')
      test.check(state)
      state.createVisible.value = false
      if (state.picker) state.picker.visible = false
    }
    assert.equal(tabs.tabs.filter(tab => tab.path === router.currentRoute.value.path).length, 1, 'No duplicate invoice tabs')
    await router.push(test.report); await flush()
    rejectNext = true
    assert.ok(await click(test.row), 'Canceled navigation returns a failure')
    await router.push(test.target); await flush()
    assert.equal(state.createVisible.value, false, 'A canceled navigation must not leave a stale opening intent')
    await router.push(test.report); await flush()
    redirectNext = true
    await click(test.row); await flush()
    await router.push(test.target); await flush()
    assert.equal(state.createVisible.value, false, 'A redirected navigation must not leave a stale opening intent')
  }
  assert.equal(instances.size, 2, 'Repeated clicks must not grow invoice KeepAlive keys')
  const throwPath = '/sale/invoice?fromOut=THROW'
  await assert.rejects(navigation.navigateToInvoice({ resolve: () => ({ fullPath: throwPath }), push: async () => { throw Error('guard failed') } }, throwPath), /guard failed/)
  assert.equal(navigation.takeInvoiceNavigationIntent(throwPath), false, 'Rejected navigation clears its pending intent')
  assert.deepEqual(errors, [])
  app.unmount()
  console.log('PASS real sales/purchase SFC + vue-router + KeepAlive: deep links, repeated entries, ordinary tabs, stable URL/cache/tab counts and canceled/redirected/rejected navigation cleanup')
})().catch(error => { console.error(error); process.exitCode = 1 })
