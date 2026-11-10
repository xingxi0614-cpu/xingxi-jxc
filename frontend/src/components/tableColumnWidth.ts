import { Comment, Text, type VNode, cloneVNode } from 'vue'

/*
 * 表格列宽的计算（从 AutoIndexTable.ts 拆出，2026-10-20；该文件超过 400 行）。
 * 只管"每列多宽、整张表多宽"，不碰勾选/序号/合计等其它逻辑。
 */

function propText(props: Record<string, any> | null | undefined, key: string) {
  const value = props?.[key]
  return value == null ? '' : String(value)
}

/*
 * ── 列宽兜底 ──
 * 各页面给列写死的宽度，很多放不下里面的内容：单号被截成"…"或折成两行，把整行撑高，
 * 一屏能看的行数变少（2026-10-13 用户两次反馈：先是日期列，后是发票号、单号）。
 * 逐页改几百个 width 既改不完、以后新页面还会再犯，所以统一在这里按"列名"兜一个最小宽度。
 * 宽度依据（2026-10-13 实测，表格 14px 字号，含左右内边距 24px）：
 *   - 完整日期 2026-09-23 ≈ 108px
 *   - 系统单号：常见最长 21 位(ABCD-CF-20260626-0001)，演示账套 21 位(DEMO-CD-20261013-0007) ≈ 231px
 *   - 供应商发票号：数电票 20 位数字 ≈ 190px
 *   - 物料编码：常见最长 13 位(ABCD-XYZ00001) ≈ 145px
 *   - 规格型号：常见写法(M8×30 304不锈钢) ≈ 143px
 *   - 状态标签最长四个字(部分出库) ≈ 112px
 * 只加宽「写了 width / min-width 且偏窄」的列；没写宽度的、本来就够宽的一律不动。
 * 备注/用途/规格这类自由文本长短不定，不在这里管（它们各自用省略号+悬浮提示）。
 */
const COLUMN_MIN_WIDTH_RULES: Array<[RegExp, number]> = [
  [/(日期|到期日|截止日)$/, 120],
  [/(供应商发票号|发票号码)$/, 200],
  [/(单号|订单号|发票号|合同号|申请号)$/, 234],
  [/^(来源|关联)(申请|订单|出库|入库|入库单|单据|单号)$/, 234],
  [/^单据$/, 234],
  [/编码$/, 150],
  [/^规格(型号)?$/, 150],
  [/^状态$/, 116]
]

/** 表头文字自己要占的宽度：汉字按 15px、字母数字按 8px，加内边距；可排序的列还有一个排序箭头 */
function headerMinWidth(label: string, sortable: boolean) {
  let text = 0
  for (const ch of label) text += ch.charCodeAt(0) > 255 ? 15 : 8
  return text + 26 + (sortable ? 24 : 0)
}

export function widenColumn(node: VNode, summaryMin: Record<string, number>) {
  const props = node.props as Record<string, any> | null | undefined
  const label = propText(props, 'label').trim()
  if (!label || !props) return node
  // 模板里写 min-width 时这里拿到的键名就是短横线写法；写 :min-width / minWidth 的是驼峰，都要认
  const key = ['width', 'min-width', 'minWidth'].find(k => Number(props[k]) > 0)
  const fromSummary = summaryMin[label] || 0
  if (!key) return fromSummary ? cloneVNode(node, { minWidth: fromSummary }) : node
  const sortable = props.sortable != null && props.sortable !== false
  let min = Math.max(headerMinWidth(label, sortable), fromSummary)
  for (const [pattern, ruleMin] of COLUMN_MIN_WIDTH_RULES) {
    if (pattern.test(label)) { min = Math.max(min, ruleMin); break }
  }
  return Number(props[key]) < min ? cloneVNode(node, { [key]: min }) : node
}

/*
 * ── 拖动列宽后，表格外框跟着变宽 ──
 * 表头边线可以拖动改列宽（border 表格 Element Plus 自带）。但外框宽度是按页面写的列宽算死的（见下方「表格有多宽就画多宽」），
 * 拖宽一列后外框不长，最右边的列被挤出框外、被截断，还冒出横向滚动条，看着像整列错位了（2026-10-20 用户截图：现存量「结存单价」）。
 * 现在把用户拖到的宽度按「字段+列名」记下来，每次渲染套回这一列，外框宽度也按它重算——拖宽多少，表格就跟着长多少，
 * 长到页面放不下为止，再多才横向滚动。只在当前页面有效，刷新或重新打开页面恢复默认宽度。
 */
export function columnKey(prop: unknown, label: unknown) {
  return `${prop ?? ''}|${String(label ?? '').trim()}`
}
export function applyResized(node: VNode, resized: Record<string, number>) {
  const props = node.props as Record<string, any> | null | undefined
  if (!props || !propText(props, 'label').trim()) return node
  const w = resized[columnKey(props.prop, props.label)]
  return w ? cloneVNode(node, { width: w }) : node
}

/*
 * ── 弹框里的表格铺满弹框宽度 ──
 * 详情弹框里上面是信息表（铺满）、下面是明细表（只画到最后一列），右边对不齐（10-20 用户截图：销售订单详情）。
 * 弹框里的表格一律铺满宽度，多出来的宽度只分给名称、规格、备注这类文字列；数量、金额、日期、单号列宽度不变，
 * 免得重蹈 10-17 的覆辙（多出来的宽度平摊给所有列，比例失调）。一列文字列都没有的表，才平摊给所有普通列。
 */
const FLEX_LABEL = /(名称|规格|备注|说明|摘要|用途|地址|项目|工程|供应商|客户|原因|内容|事由)/
function isPlainColumn(node: VNode) {
  const props = node.props as Record<string, any> | null | undefined
  if (!props || ['selection', 'index', 'expand'].includes(propText(props, 'type'))) return false
  const label = propText(props, 'label').trim()
  return !!label && !/^操作/.test(label) && columnWidth(node) > 0
}
function toFlex(node: VNode) {
  return cloneVNode(node, { width: undefined, 'min-width': undefined, minWidth: columnWidth(node) })
}
export function stretchColumns(children: VNode[]) {
  const text = children.filter(n => isPlainColumn(n) && FLEX_LABEL.test(propText(n.props as any, 'label')))
  const pick = new Set(text.length ? text : children.filter(isPlainColumn))
  return children.map(n => pick.has(n) ? toFlex(n) : n)
}

/*
 * ── 表格有多宽就画多宽 ──
 * 列宽加起来比页面窄时，别去「填满」右边：
 *   - 10-17 改成铺满宽度 → 多出来的几百像素被平摊给物料编码、名称，比例失调（10-18 用户截图）；
 *   - 10-18 改成补一列空白列 → 右边一大块空白表头，同样难看，用户否了（10-18 第二张截图）。
 * 现在：表格（外框、表头、合计行）宽度 = 各列宽度之和，画到最后一列就结束，右边留页面底色；
 * 页面变窄、列加起来放不下时，表格不超过页面宽度，改为横向滚动（左侧冻结列照常冻结）。
 * 列宽一律按页面里写的 width / min-width；某列两样都没写（如分组表头），算不出总宽，就保持原来占满一行的样子。
 */
export function columnWidth(node: VNode) {
  const props = node.props as Record<string, any> | null | undefined
  if (!props) return 0
  for (const k of ['width', 'min-width', 'minWidth']) {
    const v = Number(props[k])
    if (v > 0) return v
  }
  return 0
}
export function naturalWidth(children: VNode[]) {
  let total = 0
  for (const n of children) {
    // 列与列之间的 <!-- 注释 -->、v-if 为假的列,在这里都是不占位的注释节点,跳过。
    // 以前把它们当成"没写宽度的列",整张表放弃计算、退回占满整行:表头只到最后一列,外框却画到最右边,
    // 看着像右边还有列被藏起来了(2026-10-19 用户截图:月末结账「对账检查明细」)。
    if (n.type === Comment || n.type === Text) continue
    const w = columnWidth(n)
    if (!w) return 0
    total += w
  }
  return total
}
