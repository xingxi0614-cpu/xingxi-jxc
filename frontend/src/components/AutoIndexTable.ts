import { Fragment, type VNode, cloneVNode, defineComponent, h, inject, nextTick, reactive, ref, toRaw } from 'vue'
import { ElTable, ElTableColumn, dialogInjectionKey } from 'element-plus'
import { rememberSelection } from '@/utils/exportSelection'
import { applyResized, columnKey, naturalWidth, stretchColumns, widenColumn } from './tableColumnWidth'
import { RowActions } from './RowActions'

const INDEX_PROPS = {
  type: 'index',
  label: '序号',
  width: 64,
  align: 'center',
  className: 'auto-index-column',
  labelClassName: 'auto-index-column',
  index: (index: number) => index + 1
}

function flatten(nodes: VNode[]): VNode[] {
  const result: VNode[] = []
  for (const node of nodes) {
    if (node.type === Fragment && Array.isArray(node.children)) {
      result.push(...flatten(node.children as VNode[]))
    } else {
      result.push(node)
    }
  }
  return result
}

function propText(props: Record<string, any> | null | undefined, key: string) {
  const value = props?.[key]
  return value == null ? '' : String(value)
}

function isIndexLikeColumn(node: VNode) {
  const props = node.props as Record<string, any> | null | undefined
  const type = propText(props, 'type')
  const label = propText(props, 'label').trim()
  const prop = propText(props, 'prop').trim()
  return type === 'index' || prop === 'lineNo' || label === '序号' || label === '#' || label === '行'
}

function isSelectionColumn(node: VNode) {
  return propText(node.props as Record<string, any> | null | undefined, 'type') === 'selection'
}

function isLeftFixedColumn(node: VNode) {
  const fixed = (node.props as Record<string, any> | null | undefined)?.fixed
  return fixed === true || fixed === '' || fixed === 'left'
}

/*
 * ── 表头「#」统一叫「序号」(2026-10-17 用户要求全局去掉「#」)──
 * 少数明细表自己写了 label="#" 的行号列，和全站其它表的「序号」不一致。
 */
function renameHashColumn(node: VNode) {
  const props = node.props as Record<string, any> | null | undefined
  return propText(props, 'label').trim() === '#' ? cloneVNode(node, { label: '序号' }) : node
}

/*
 * ── 合计数显示完整 ──
 * 合计往往比单行数字大好几位（采购发票数量列 100 宽，合计 14,759.00 被截成「14,759…」，2026-10-17 用户截图）。
 * 算出合计后量一下每格文字要多宽，放不下就记下来，下一次渲染把这一列加宽到放得下（只加宽，不缩窄）。
 */
let measureCtx: CanvasRenderingContext2D | null = null
function textWidth(text: string) {
  if (!measureCtx) {
    measureCtx = typeof document !== 'undefined' ? document.createElement('canvas').getContext('2d') : null
    if (!measureCtx) return text.length * 9
    measureCtx.font = `600 14px ${getComputedStyle(document.body).fontFamily || 'sans-serif'}`
  }
  return measureCtx.measureText(text).width
}

/*
 * ── 全站表格不换行 ──
 * 物料名称、供应商名称太长会在格子里折成两三行,把整行撑高、表格忽高忽低,看着不专业(2026-10-16 用户要求全局不换行)。
 * 统一给普通列打开 Element Plus 的 show-overflow-tooltip:放不下就单行显示省略号,鼠标移上去看全文。
 * 不碰的列:勾选/序号/展开这类功能列,以及页面自己写明了这个设置的列。
 * 「操作」按钮列不能硬压一行(放不下的按钮会被截掉、点不到),改由 RowActions 把多出来的按钮收进「更多」(10-16 方案 B)。
 */
function noWrapColumn(node: VNode) {
  const props = node.props as Record<string, any> | null | undefined
  if (!props) return node
  if (['selection', 'index', 'expand'].includes(propText(props, 'type'))) return node
  if (/^操作/.test(propText(props, 'label').trim())) return withRowActions(node)
  if (['show-overflow-tooltip', 'showOverflowTooltip'].some(k => k in props)) return node
  return cloneVNode(node, { showOverflowTooltip: true })
}

/** 给「操作」列的单元格套上 RowActions;其余插槽(表头等)原样保留 */
function withRowActions(node: VNode) {
  const slotsObj = node.children as Record<string, any> | null
  const cell = slotsObj?.default
  if (typeof cell !== 'function') return node
  return h(node.type as any, { ...(node.props || {}), key: node.key ?? undefined }, {
    ...slotsObj,
    // Element Plus 渲染单元格时要求插槽返回数组(内部会对它调 .some),单个节点会让整张表的数据行都渲染不出来
    default: (scope: any) => [h(RowActions, null, () => cell(scope))]
  })
}

const SELECTION_PROPS = { type: 'selection', width: 44, align: 'center', className: 'auto-select-column' }

/**
 * 给表格补上「勾选列」（仅 withSelection 时）和「序号列」，返回补进去的位置（合计行要按位置补空格）。
 * Element Plus 会把左冻结列整体提到最前面：补进去的列不跟着冻结，就会被挤到后面，
 * 合计行也跟着错位（2026-10-13 用户反馈序号跑到第二列）。页面上有左冻结列时一起冻结。
 */
function indexedChildren(nodes: VNode[], withSelection: boolean, summaryMin: Record<string, number>, indexOffset = 0,
  resized: Record<string, number> = {}, stretch = false) {
  const children = flatten(nodes).map(renameHashColumn).map(n => widenColumn(n, summaryMin)).map(noWrapColumn)
  const inserted: number[] = []
  let addedSelection = false
  const fixed = children.some(isLeftFixedColumn) ? { fixed: 'left' } : {}

  if (withSelection && !children.some(isSelectionColumn)) {
    children.unshift(h(ElTableColumn, { ...SELECTION_PROPS, ...fixed }))
    inserted.push(0)
    addedSelection = true
  }
  if (!children.some(isIndexLikeColumn)) {
    const at = children.findIndex(isSelectionColumn) + 1   // 有勾选列排它后面，没有就排最前
    // index-offset：前端分页的报表第 2 页从 101 接着数
    children.splice(at, 0, h(ElTableColumn, { ...INDEX_PROPS, ...fixed, index: (i: number) => i + 1 + indexOffset }))
    inserted.push(at)
  }
  // 拖动过的列宽最后套：补进来的序号列被拖宽，外框也要跟着长
  const sized = children.map(n => applyResized(n, resized))
  return { children: stretch ? stretchColumns(sized) : sized, inserted, addedSelection }
}

const TABLE_METHODS = ['clearSelection', 'getSelectionRows', 'toggleRowSelection', 'toggleAllSelection', 'toggleRowExpansion',
  'setCurrentRow', 'clearSort', 'clearFilter', 'doLayout', 'sort', 'scrollTo', 'setScrollTop', 'setScrollLeft', 'updateKeyChildren']

export const AutoIndexTable = defineComponent({
  name: 'AutoIndexTable',
  inheritAttrs: false,
  setup(_, { attrs, slots, expose }) {
    /*
     * 🔴 2026-10-01 修：全站 el-table 被本组件包了一层，页面上 ref 拿到的是本组件而不是 ElTable，
     * 于是 tableRef.value.toggleRowSelection / clearSelection 等全是 undefined：
     *   - 采购发票「选择入库明细」单击整行勾选直接报错，点了没反应；
     *   - 采购入库「从订单下推」默认勾选不显示，界面看着没勾、实际按全选在走（所见≠所存）。
     * 把 ElTable 的实例方法原样转出去，页面照常用。
     */
    const inner = ref<any>(null)
    const call = (name: string) => (...args: any[]) => inner.value?.[name]?.(...args)
    expose(Object.fromEntries(TABLE_METHODS.map(m => [m, call(m)])))
    // 勾选了几行（带 export-select 的列表表格用）：大于 0 时表格上方出现批量操作栏（2026-10-17 全站界面改版）
    const picked = ref(0)
    let pickedData: unknown = null
    // 合计放不下的列要加宽到多少（按列名记；见上方「合计数显示完整」）
    const summaryMin = reactive<Record<string, number>>({})
    // 用户拖动表头边线改过的列宽（按「字段|列名」记；见上方「拖动列宽后，表格外框跟着变宽」）
    const resized = reactive<Record<string, number>>({})
    // 在 el-dialog 里面吗（弹框会 provide 这个键）——决定限高、铺满与表头吸顶，见下方「页面表格不在表内上下滚动，表头吸顶」
    const inDialog = inject(dialogInjectionKey, null) != null
    function growForSummary(columns: any[], summary: any[]) {
      const grow: Record<string, number> = {}
      summary.forEach((v, i) => {
        const col = columns[i]
        const label = String(col?.label || '').trim()
        if (!label || v == null || v === '' || typeof v === 'object') return
        // 表格数字用等宽数字(tabular-nums)、合计行加粗，画布量出来会偏窄一点：按 8% 放宽，再加左右内边距 24 和边框
        const need = Math.ceil(textWidth(String(v)) * 1.08) + 32
        const cur = Number(col.realWidth || col.width || col.minWidth || 0)
        if (need > cur && need > (summaryMin[label] || 0)) grow[label] = need
      })
      // 渲染过程中不能改响应式数据，放到下一拍
      if (Object.keys(grow).length) nextTick(() => Object.assign(summaryMin, grow))
    }
    return () => {
      let inserted: number[] = []
      let addedSelection = false
      // 模板里写 :summary-method 时，这里拿到的键名是短横线写法，两种都要认（只认驼峰会漏掉大部分页面）
      const originalSummaryMethod = (attrs.summaryMethod ?? attrs['summary-method']) as ((params: any) => any[]) | undefined
      // 横向溢出时保持滚动条可见，同时允许键盘聚焦滚动区域。
      const tableProps: Record<string, any> = {
        border: true, scrollbarAlwaysOn: true, scrollbarTabindex: 0, ...attrs, ref: inner
      }
      // 页面上的表格列宽不随页面拉伸（见上方「表格有多宽就画多宽」），各页面写的 :fit 一律按 false 处理；
      // 弹框里的表格铺满弹框宽度（见上方「弹框里的表格铺满弹框宽度」）
      tableProps.fit = inDialog
      /*
       * ── 页面表格不在表内上下滚动，表头吸顶 ──
       * 以前约 100 张页面表格写了 max-height（表格限高、表里面上下滚），表格右边就多一条竖向滚动条；
       * 「不想要这种滚动条」，所以整页往下滚。随后又发现「标题行不固定了」——表头要一直看得见。
       * 所以页面上的表格去掉限高、整页滚动，表头吸在页面顶部（auto-table--page，样式见 index.scss），
       * 宽表的横向滚动条也贴在屏幕底边，不用翻到表格最底下才拖得到。
       * 弹框里的表格保留限高：明细一多弹框会长出屏幕，底部「保存/关闭」按钮要翻半天才找得到。
       * 写死 height 的（都是弹框里挑单据的小表）不动。
       */
      if (!inDialog) {
        delete tableProps.maxHeight
        delete tableProps['max-height']
      }
      tableProps.class = [attrs.class, inDialog ? 'auto-table--dialog' : 'auto-table--page']
      // 列表页在 <el-table> 上写 export-select = 每行前面出勾选框，勾了哪几行「导出Excel」就只导哪几行
      const withSelection = 'export-select' in attrs || 'exportSelect' in attrs
      const indexOffset = Number(attrs.indexOffset ?? attrs['index-offset'] ?? 0) || 0
      delete tableProps.indexOffset
      delete tableProps['index-offset']
      delete tableProps['export-select']
      delete tableProps.exportSelect
      // 勾选变化统一登记，导出时按"同一份数据"取回；页面自己的 @selection-change 照常触发
      const pageSelectionChange = attrs.onSelectionChange as undefined | ((rows: any[]) => void) | Array<(rows: any[]) => void>
      tableProps.onSelectionChange = (rows: any[]) => {
        rememberSelection(attrs.data, rows)
        picked.value = rows?.length || 0
        pickedData = toRaw(attrs.data)
        for (const fn of Array.isArray(pageSelectionChange) ? pageSelectionChange : [pageSelectionChange]) fn?.(rows)
      }

      if (originalSummaryMethod) {
        delete tableProps['summary-method']
        tableProps.summaryMethod = (params: any) => {
          let summary = originalSummaryMethod(params)
          // 只动「本组件补了勾选列」的表格；其余表格的合计行保持原样，不扩大影响面
          if (!addedSelection) {
            if (Array.isArray(summary)) growForSummary(params.columns, summary)
            return summary
          }
          if (inserted.length && Array.isArray(summary) && summary.length === params.columns.length - inserted.length) {
            summary = [...summary]
            for (const at of inserted) summary.splice(at, 0, '')
          }
          // 不少页面的合计是"第 0 列写'合计'"。第 0 列若是我们补的勾选列（只有 44px 宽），
          // 两个字会被截成"合…"，挪到紧挨着的序号列下面显示。
          if (addedSelection && Array.isArray(summary) && summary[0] && !summary[1]) {
            summary = [...summary]
            summary[1] = summary[0]
            summary[0] = ''
          }
          if (Array.isArray(summary)) growForSummary(params.columns, summary)
          return summary
        }
      }

      const pageDragend = attrs.onHeaderDragend as undefined | ((...a: any[]) => void) | Array<(...a: any[]) => void>
      tableProps.onHeaderDragend = (newWidth: number, oldWidth: number, column: any, event: MouseEvent) => {
        if (column?.label && newWidth > 0) resized[columnKey(column.property, column.label)] = Math.round(newWidth)
        for (const fn of Array.isArray(pageDragend) ? pageDragend : [pageDragend]) fn?.(newWidth, oldWidth, column, event)
      }

      // 换了一批数据（重新查询/翻页）旧勾选就作废，栏也收起
      if (picked.value && pickedData !== toRaw(attrs.data)) picked.value = 0

      // 页面上的表格先排一遍列，算出各列宽度之和：表格宽度取「列宽之和」和「页面可用宽度」里小的那个（弹框里铺满，不算）
      if (slots.default && !inDialog) {
        const probe = indexedChildren(slots.default() as VNode[], withSelection, summaryMin, indexOffset, resized)
        const total = naturalWidth(probe.children)
        // 外框宽度 = 列宽之和，不再 +2：Element Plus 的外框线是叠画在表格边上的、不占宽度，
        // 多加的 2px 在最后一列和外框之间空出一道缝，右边看着是两条竖线（10-20 用户截图：领料申请列表、详情明细）。
        if (total) tableProps.style = [attrs.style, { width: `min(100%, ${total}px)` }]
      }

      const table = h(ElTable, tableProps, {
        ...slots,
        default: slots.default
          ? (...args: any[]) => {
              const result = indexedChildren(slots.default!(...args) as VNode[], withSelection, summaryMin, indexOffset, resized, inDialog)
              inserted = result.inserted
              addedSelection = result.addedSelection
              return result.children
            }
          : undefined
      })
      if (!withSelection) return table
      // 批量操作栏：只做提示和取消勾选；「导出Excel」等按钮仍在页面右上角，勾了就只导勾选的行。
      // 带勾选框的表格始终返回 [栏位, 表格] 两个位置（没勾时栏位是空），
      // 否则栏出现/消失会让表格整个重建，刚勾的行就丢了。
      const bar = !picked.value ? null : h('div', { class: 'table-batch-bar', role: 'status' }, [
        h('b', null, `已选 ${picked.value} 行`),
        h('span', null, '右上角「导出Excel」只导出勾选的行'),
        h('span', { class: 'table-batch-bar__sp' }),
        h('button', { type: 'button', class: 'table-batch-bar__clear', onClick: () => inner.value?.clearSelection?.() }, '取消勾选')
      ])
      // 外面包一层 div 当唯一根节点:页面写在 <el-table> 上的 v-loading 才落得到(计算审查 U17:
      // 以前返回 Fragment 多根节点,Vue 指令挂不上,查询中遮罩不出现、旧数据还能点)。
      // 两个子位置始终都在(栏位没勾时为空),表格不会因为栏出现/消失而重建。
      return h('div', { class: 'auto-index-table-wrap' }, [bar, table])
    }
  }
})

export default AutoIndexTable
