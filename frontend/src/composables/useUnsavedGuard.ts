import { onBeforeUnmount, watch, type Ref } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 给一块填写区域拍「快照」：所有输入框的值、勾选状态、正文文字。两次快照不同 = 用户改过内容。
 * 弹窗（useUnsavedGuard）和手机建单页（useLeaveGuard）共用。
 */
export function formSnapshot(el: HTMLElement): string {
  const body = el.querySelector<HTMLElement>('.el-dialog__body') || el
  const values: string[] = []
  // 带 data-guard-ignore 的区域不算「填写内容」（如"盲盘打印"这类只影响打印的勾选）
  const counted = (n: Element) => !n.closest('[data-guard-ignore]')
  body.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea').forEach(i => { if (counted(i)) values.push(i.value) })
  // 开关、勾选、单选在 Element Plus 里靠 is-checked 样式体现
  // 比较每一个控件的身份和状态；仅比较总数会漏掉 A→B 这种等量替换。
  const checked = Array.from(body.querySelectorAll<HTMLElement>('.el-radio, .el-checkbox, .el-switch'))
    .filter(counted)
    .map((n, index) => `${index}:${n.classList.contains('is-checked') ? 1 : 0}`)
    .join(',')
  // 正文文字里也要扣掉被忽略区域的文字（如稍后才返回的"已结账"提示，它出现不代表用户改了东西）
  let text = body.textContent || ''
  body.querySelectorAll('[data-guard-ignore]').forEach(n => { text = text.replace(n.textContent || '', '') })
  return values.join('\u0001') + '\u0002' + checked + '\u0002' + text
}

/**
 * 填写弹窗的「没保存就关」保护。
 *
 * 为什么要有：Element Plus 的弹窗点外面、按 Esc、点 × 都会直接关，一张填了十几行的单据手一滑就全没了。
 *
 * 怎么判断「改过没有」：不去读各页面的表单变量（每页写法不一样，漏一个字段就会漏报），
 * 而是给弹窗里看得见的内容拍个「快照」——所有输入框的值、勾选状态、正文文字。
 *   - 基准快照在用户第一次点/敲弹窗时才拍，所以打开后程序自动带出的内容（编辑回填、默认值）不算改动；
 *   - 关闭时再拍一次，和基准不一样才问。没碰过、或碰了没改，照旧一点就关。
 *
 * 只拦「用户主动关」：保存成功后页面自己把弹窗设为不可见，不经过这里，不会多问。
 *
 * 用法：
 *   const createGuard = useUnsavedGuard(createVisible)
 *   <el-dialog v-model="createVisible" :before-close="createGuard.beforeClose">
 *   <el-button @click="createGuard.cancel">取消</el-button>
 *   保存后弹窗不关的页面：保存成功后调 createGuard.markSaved()
 */
export function useUnsavedGuard(visible: Ref<boolean>) {
  let dialogEl: HTMLElement | null = null
  let baseline: string | null = null
  let listening = false

  /** 当前最上层的弹窗。本弹窗刚打开、用户第一次操作时，最上层的就是它（子弹窗还没机会打开）。 */
  function topDialog(): HTMLElement | null {
    let top: HTMLElement | null = null
    let topZ = -1
    document.querySelectorAll<HTMLElement>('.el-overlay').forEach(overlay => {
      if (overlay.offsetParent === null && getComputedStyle(overlay).display === 'none') return
      const dialog = overlay.querySelector<HTMLElement>('.el-dialog')
      if (!dialog) return
      const z = Number(getComputedStyle(overlay).zIndex) || 0
      if (z >= topZ) { topZ = z; top = dialog }
    })
    return top
  }

  function onFirstTouch() {
    if (baseline !== null) return
    dialogEl = topDialog()
    if (dialogEl) baseline = formSnapshot(dialogEl)
    stop()
  }
  function start() {
    if (listening) return
    listening = true
    // 捕获阶段：赶在输入框真正改值之前拍基准
    document.addEventListener('pointerdown', onFirstTouch, true)
    document.addEventListener('keydown', onFirstTouch, true)
  }
  function stop() {
    if (!listening) return
    listening = false
    document.removeEventListener('pointerdown', onFirstTouch, true)
    document.removeEventListener('keydown', onFirstTouch, true)
  }

  watch(visible, open => {
    baseline = null
    dialogEl = null
    stop()
    if (open) start()
  })
  onBeforeUnmount(stop)

  function isDirty(): boolean {
    if (baseline === null || !dialogEl || !dialogEl.isConnected) return false
    return formSnapshot(dialogEl) !== baseline
  }

  /** 弹窗不关、但内容已经存好了（如盘点单「保存实盘数」）：重新起算，之后没再改就不问。 */
  function markSaved() {
    baseline = null
    dialogEl = null
    if (visible.value) start()
  }

  /** 给 el-dialog 的 :before-close 用。 */
  function beforeClose(done: () => void) {
    if (!isDirty()) return done()
    ElMessageBox.confirm('现在关掉，刚才填的内容会全部丢失。', '还没保存', {
      type: 'warning',
      // 主按钮（默认焦点、回车）是「继续填」，避免手快把内容丢了
      confirmButtonText: '继续填',
      cancelButtonText: '不保存，关闭',
      distinguishCancelAndClose: true,
      closeOnClickModal: false
    }).catch((action: string) => {
      if (action === 'cancel') done()
    })
  }

  /** 给「取消」按钮用，和点 × 走同一套判断。 */
  function cancel() {
    beforeClose(() => { visible.value = false })
  }

  return { beforeClose, cancel, isDirty, markSaved }
}
