/* Real formSnapshot function with a minimal Element Plus DOM shape. */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const source = fs.readFileSync(process.env.UNSAVED_SOURCE || path.join(__dirname, '../src/composables/useUnsavedGuard.ts'), 'utf8')
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
const moduleRef = { exports: {} }
vm.runInNewContext(js, { module: moduleRef, exports: moduleRef.exports, require: () => ({}) })
const { formSnapshot } = moduleRef.exports
function dom(kind) {
  let selected = 'A'
  const choices = ['A', 'B'].map(choice => ({
    classList: { contains: name => name === 'is-checked' && choice === selected },
    closest: () => null
  }))
  const input = { value: 'unchanged', closest: () => null }
  const node = {
    textContent: 'A B', querySelector: () => null,
    querySelectorAll: selector => selector === 'input, textarea' ? [input]
      : selector === '.el-radio, .el-checkbox, .el-switch' ? choices : []
  }
  const first = formSnapshot(node)
  assert.equal(formSnapshot(node), first)
  selected = 'B'
  assert.notEqual(formSnapshot(node), first, `${kind} must detect equal-count choice replacement`)
  selected = 'A'
  assert.equal(formSnapshot(node), first, `${kind} restore must be clean`)
}
for (const kind of ['radio', 'checkbox', 'switch']) dom(kind)
console.log('PASS 3 unsaved-choice identity checks')
