import { Comment, Fragment, Text, type VNode, defineComponent, h } from 'vue'
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus'
import './row-actions.css'

/*
 * ── 「操作」列按钮多了收进「更多」(2026-10-16 定方案 B) ──
 * 全站表格已改成不换行,但操作列不能硬压一行(放不下的按钮会被截掉、点不到);
 * 单据列表按状态同时露 4~6 个按钮(如待审批:查看、打印、取消提交、审批、驳回),于是折成两行把行撑高。
 * 规则(由 AutoIndexTable 给每个「操作」列自动套上,页面不用改):
 *   · 连同「查看」不超过 4 个能点的按钮 → 原样显示;
 *   · 超过 → 「查看」+ 按优先级挑最多 2 个主操作外露,不够 2 个用「打印」补,其余收进「更多 ▾」;
 *   · 外露的仍按页面原来的先后顺序排;下拉里点哪项就是调用原按钮的点击,权限判断、二次确认都不变。
 * 只认 Element Plus 的按钮;页面若用了别的东西(提示框包着的按钮等),那个东西原样留在外面。
 */
const MAX_PLAIN = 4
const MAX_PRIMARY = 2
const PRIORITY = ['审批', '驳回', '提交', '编辑', '修改', '下推入库', '归还', '修订', '取消提交', '撤回']

function flatten(nodes: unknown): VNode[] {
  const out: VNode[] = []
  for (const n of (Array.isArray(nodes) ? nodes : [nodes]) as VNode[]) {
    if (!n || n.type === Comment) continue
    if (n.type === Fragment) out.push(...flatten(n.children))
    else if (n.type === Text && !String(n.children ?? '').trim()) continue
    else out.push(n)
  }
  return out
}

const isButton = (v: VNode) => (v.type as any)?.name === 'ElButton'

/** 按钮上的文字(模板里 <el-button>文字</el-button> 编译成默认插槽返回的文本节点) */
function labelOf(v: VNode): string {
  const slot = (v.children as any)?.default
  const parts = typeof slot === 'function' ? flatten(slot()) : []
  return parts.map(p => (typeof p.children === 'string' ? p.children : '')).join('').trim()
}

/** 调用原按钮的点击处理(模板里 @click 可能编译成单个函数或数组) */
function clickOf(v: VNode) {
  const handler = (v.props as any)?.onClick
  return (e: MouseEvent) => (Array.isArray(handler) ? handler : [handler]).forEach(fn => typeof fn === 'function' && fn(e))
}

export const RowActions = defineComponent({
  name: 'RowActions',
  setup(_, { slots }) {
    return () => {
      const nodes = flatten(slots.default?.())
      const buttons = nodes.filter(isButton)
      if (buttons.length <= MAX_PLAIN) return h('div', { class: 'row-actions' }, nodes)

      const first = buttons[0]
      const rest = buttons.slice(1)
      const picked = new Set<VNode>()
      for (const name of PRIORITY) {
        if (picked.size >= MAX_PRIMARY) break
        const hit = rest.find(b => !picked.has(b) && labelOf(b) === name)
        if (hit) picked.add(hit)
      }
      if (picked.size < MAX_PRIMARY) {
        const print = rest.find(b => !picked.has(b) && labelOf(b) === '打印')
        if (print) picked.add(print)
      }
      const outside = nodes.filter(n => !isButton(n) || n === first || picked.has(n))
      const folded = rest.filter(b => !picked.has(b))

      const more = h(ElDropdown, { trigger: 'click', placement: 'bottom-end', class: 'row-actions__more' }, {
        default: () => h(ElButton, { link: true, type: 'primary' }, () => '更多 ▾'),
        dropdown: () => h(ElDropdownMenu, null, () => folded.map(b => {
          const p = (b.props || {}) as any
          return h(ElDropdownItem, {
            disabled: p.disabled === true || p.disabled === '',
            class: p.type === 'danger' ? 'row-actions__danger' : p.type === 'warning' ? 'row-actions__warning' : '',
            onClick: clickOf(b)
          }, () => labelOf(b))
        }))
      })
      return h('div', { class: 'row-actions' }, [...outside, more])
    }
  }
})

export default RowActions
