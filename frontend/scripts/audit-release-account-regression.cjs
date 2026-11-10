const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')

const source = fs.readFileSync(process.env.RELEASE_NOTE_SOURCE || path.join(__dirname, '../src/components/ReleaseNoteDialog.vue'), 'utf8')
const setup = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(setup, 'ReleaseNoteDialog setup must exist')
const script = ts.transpileModule(setup.replace(/^import .*$/gm, ''), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }
}).outputText
const tick = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

async function scenario() {
  const user = vue.reactive({ sessionActive: false, userInfo: null })
  const route = vue.reactive({ path: '/home', fullPath: '/home' })
  const router = { currentRoute: { value: route }, push: async p => { route.path = p; route.fullPath = p } }
  const reads = []
  const seen = new Map()
  let pending = null
  let tourPending = null
  let cancelled = 0
  let mount = () => {}
  const sysApi = {
    latestReleaseNote: async () => pending ? pending.promise : { versionNo: 'v1', popup: 1 },
    releaseGuide: async () => ({ items: [{ key: 'required', must: true }], readKeys: user.userInfo?.id === 1 ? ['required'] : [] }),
    releaseGuideRead: async (version, key) => { reads.push([version, key]) }
  }
  const context = { ...vue, sysApi, useRouter: () => router, useUserStore: () => user,
    dayjs: () => ({ format: () => '' }), GUIDE_TAG: { improve: {} },
    localStorage: { setItem: (k, v) => seen.set(k, v), getItem: k => seen.get(k) },
    window: { addEventListener() {}, removeEventListener() {} },
    onMounted: fn => { mount = fn }, onBeforeUnmount() {}, AbortController, clearTimeout, setTimeout,
    console, module: { exports: {} }
  }
  vm.runInNewContext(`${script}\nglobalThis.__probe = { visible, note, readKeys, loaded, tourRef, go, onClosed }`, context)
  const p = context.__probe
  p.tourRef.value = { cancel: () => { cancelled++; tourPending?.resolve() }, start: async () => tourPending?.promise }
  mount()
  user.sessionActive = true
  user.userInfo = { id: 1 }
  await tick()
  assert.equal(p.visible.value, true)
  assert.deepEqual([...p.readKeys.value], ['required'])
  p.visible.value = false
  p.onClosed()
  assert.equal(seen.get('jxc_release_seen_1_v1'), '1')

  user.sessionActive = false
  user.userInfo = null
  await tick()
  assert.equal(p.note.value, null, 'logout must clear old announcement')
  assert.equal(p.visible.value, false)
  assert.deepEqual([...p.readKeys.value], [])
  user.sessionActive = true
  user.userInfo = { id: 2 }
  await tick()
  assert.equal(p.visible.value, true, 'B must load their own required guide')
  assert.deepEqual([...p.readKeys.value], [])
  assert.ok(cancelled >= 1, 'account switch cancels active guide')

  p.visible.value = false // 稍后再看
  p.onClosed()
  assert.equal(seen.has('jxc_release_seen_2_v1'), false)
  user.sessionActive = false
  user.userInfo = null
  user.sessionActive = true
  user.userInfo = { id: 2 }
  await tick()
  assert.equal(p.visible.value, true, 'same user must see deferred required guide on next login')

  pending = deferred()
  user.sessionActive = false
  user.userInfo = null
  user.sessionActive = true
  user.userInfo = { id: 1 }
  await tick()
  user.sessionActive = false
  user.userInfo = null
  pending.resolve({ versionNo: 'stale-A', popup: 1 })
  pending = null
  user.sessionActive = true
  user.userInfo = { id: 2 }
  await tick()
  assert.equal(p.note.value.versionNo, 'v1', 'A pending response must not overwrite B')
  tourPending = deferred()
  const tour = p.go({ key: 'required', path: null, target: null })
  await tick()
  user.sessionActive = false
  user.userInfo = null
  await tour
  assert.deepEqual(reads, [])
  assert.equal(p.visible.value, false, 'old tour must not reopen after logout')
}

scenario().then(() => console.log('PASS release notes: account switch, relogin, stale request, defer'))
  .catch(err => { console.error(err); process.exitCode = 1 })
