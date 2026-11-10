/**
 * 查询面板(QueryDrawer)读页面表单的小工具(2026-10-16 全站查询条件改造)。
 *
 * 为什么从页面 DOM 读,而不是让每页再写一份"条件清单"配置:
 * 全站 25 个页面早就把查询项写成 <el-form-item label="…">,面板直接拿这些表单项当左栏条目,
 * 页面不用改一行就换成左右两栏;条件名和右边输入框永远是同一个东西,不会出现"清单写了、表单没有"的对不上。
 * 代价是要认得 Element Plus 几种输入控件长什么样——认不出的控件只是"已选条件"里不显示值,不影响查询本身。
 */

export interface PanelItem {
  el: HTMLElement
  /** 条件名 = 表单项标签 */
  label: string
  /** 页面在 el-form-item 上标的 data-q="query 字段名",有它"已选条件"的 × 才能清掉这一项 */
  key: string | null
  /** 页面标了 data-q-fixed 的必选项:不能去掉、「清空值」也不清 */
  fixed: boolean
}

/** 面板里的全部查询项(只取最外层,控件内部嵌的表单项不算) */
export function collectItems(root: HTMLElement | null): PanelItem[] {
  if (!root) return []
  const all = Array.from(root.querySelectorAll<HTMLElement>('.el-form-item'))
  return all
    .filter(el => !el.parentElement?.closest('.el-form-item'))
    .map(el => ({ el, label: itemLabel(el), key: el.dataset.q || null, fixed: el.dataset.qFixed !== undefined }))
    .filter(item => item.label)
}

function itemLabel(el: HTMLElement): string {
  const label = el.querySelector(':scope > .el-form-item__label')?.textContent?.trim()
  if (label) return label.replace(/[:：]$/, '')
  // 没写 label 的表单项(如单独一个「只看待核销」勾选框)用控件自己的文字
  return el.querySelector('.el-checkbox__label, .el-radio__label')?.textContent?.trim() || ''
}

const text = (n: Element | null | undefined) => n?.textContent?.replace(/\s+/g, ' ').trim() || ''

/**
 * 读出表单项当前"显示给人看"的值(下拉显示名称而不是 id),空值返回 ''。
 * Element Plus 2.14:单选下拉选中项显示在 .el-select__placeholder 里,未选时带 is-transparent;多选是一排 tag。
 */
export function itemDisplayValue(el: HTMLElement): string {
  const parts: string[] = []
  el.querySelectorAll<HTMLElement>('.el-select').forEach(sel => {
    const tags = Array.from(sel.querySelectorAll('.el-select__tags-text')).map(text).filter(Boolean)
    if (tags.length) { parts.push(tags.join('、')); return }
    const ph = sel.querySelector('.el-select__placeholder')
    if (ph && !ph.classList.contains('is-transparent')) parts.push(text(ph))
  })
  const ranges = Array.from(el.querySelectorAll<HTMLInputElement>('.el-range-input')).map(i => i.value.trim())
  if (ranges.some(Boolean)) parts.push(ranges.map(v => v || '…').join(' ~ '))
  el.querySelectorAll<HTMLInputElement>('input.el-input__inner').forEach(input => {
    if (input.closest('.el-select') || input.closest('.el-range-editor')) return
    if (input.value.trim()) parts.push(input.value.trim())
  })
  const checked = Array.from(el.querySelectorAll('.el-checkbox.is-checked .el-checkbox__label, .el-radio.is-checked .el-radio__label, .el-radio-button.is-active .el-radio-button__inner'))
    .map(text).filter(Boolean)
  if (checked.length) parts.push(checked.join('、'))
  if (el.querySelector('.el-switch.is-checked')) parts.push('是')
  return parts.join(' ')
}

/** × 清掉一项时按原值类型给空值:数组→[]、字符串→''、布尔→false、其余→null */
export function emptyLike(value: unknown): unknown {
  if (Array.isArray(value)) return []
  if (typeof value === 'string') return ''
  if (typeof value === 'boolean') return false
  return null
}

/**
 * 这一项实际怎么查(财务软件 查询框"等于"那一列,2026-10-16 用户给的参考图)。
 * 只做显示、不能改:各页面后端的查法是固定的——日期区间=介于,单号/姓名等手输文字=包含(模糊),
 * 下拉、档案选择=等于。要做成可选运算符,得逐页改后端,先不做(待定)。
 */
export function itemOperator(el: HTMLElement): string {
  if (el.querySelector('.el-range-editor, .el-date-editor--daterange')) return '介于'
  if (el.querySelector('.doc-lookup')) return '包含'
  const plainInput = Array.from(el.querySelectorAll('input.el-input__inner'))
    .some(i => !i.closest('.el-select') && !i.closest('.el-date-editor') && !i.closest('.el-input-number'))
  return plainInput ? '包含' : '等于'
}

/**
 * 给页面的表单项装上一行的"× 按钮"和"查法"标签(只装一次)。
 * 表单项是页面自己的 Vue 组件渲染的,这里只往里加两个小元素、不挪动原有节点,Vue 更新时不受影响。
 */
export function decorateRow(el: HTMLElement) {
  if (el.querySelector(':scope > .qp-row-x, :scope > .qp-row-pin')) return
  // 必选项(页面标 data-q-fixed,如收发存的「统计口径」):清空了查询就不成立,不给 ×,只能改选
  if (el.dataset.qFixed !== undefined) {
    const pin = document.createElement('span')
    pin.className = 'qp-row-pin'
    pin.title = '必选条件，不能去掉，可以直接改选'
    const op = document.createElement('span')
    op.className = 'qp-row-op'
    op.textContent = itemOperator(el)
    el.prepend(pin, op)
    return
  }
  const x = document.createElement('button')
  x.type = 'button'
  x.className = 'qp-row-x'
  x.title = '去掉这个条件'
  x.textContent = '×'
  const op = document.createElement('span')
  op.className = 'qp-row-op'
  op.textContent = itemOperator(el)
  el.prepend(x, op)
}

/**
 * 快速查询栏放哪几个条件(2026-10-16 定:日期 + 单号 + 往来单位 + 仓库)。
 * 页面在表单项上标 data-quick 就以页面为准;没标就按条件名自动挑,挑不到 2 个时取前 4 个。
 * 必填条件(如存货明细账的物料)一律放进来,否则快速栏里查不了;出库类单据没有供应商,往来单位取工程项目。
 */
export function pickQuickItems(items: PanelItem[]): Set<HTMLElement> {
  const marked = items.filter(i => i.el.dataset.quick !== undefined)
  if (marked.length) return new Set(marked.map(i => i.el))
  const rules: ((i: PanelItem) => boolean)[] = [
    i => !!i.el.querySelector('.el-range-editor, .el-date-editor--daterange'),
    i => /单号|订单号|单据号|发票号/.test(i.label),
    i => /供应商|客户|往来|工程项目/.test(i.label),
    i => /仓库/.test(i.label)
  ]
  const picked = new Set<HTMLElement>(items.filter(i => i.el.classList.contains('is-required')).map(i => i.el))
  for (const rule of rules) {
    const hit = items.find(i => !picked.has(i.el) && rule(i))
    if (hit) picked.add(hit.el)
  }
  if (picked.size < 2) items.slice(0, 4).forEach(i => picked.add(i.el))
  return picked
}
