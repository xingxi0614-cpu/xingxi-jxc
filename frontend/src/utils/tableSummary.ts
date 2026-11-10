import { fmtMoney, fmtQty } from './format'

/** field = 行上的字段名；字段要先换算的（如净出库 = 出库 − 退料）用 value 传一个取数函数 */
type SumSpec = { field?: string; value?: (row: any) => number; type: 'qty' | 'money' | 'count' }

/**
 * 生成 Element Plus 表格 show-summary 的 summary-method。
 * 按列的 label 匹配要合计的字段（对 v-if 动态列安全，因为 EP 只传入已渲染的列）。
 * “合计”放在第一个非勾选列（勾选列只有 44px，放那里会被挤成竖排），未在 map 中的列留空。
 * 🔴 报表别再按列序号写合计：2026-10-01 查出出库汇总/工程材料计划两张表加了勾选列后序号错位，
 *    合计数落在隔壁列、还把单价也加总了。按 label 匹配就不会错位；单价类列不要放进 map（加总无意义）。
 *    根因之一：全站 el-table 会被 AutoIndexTable 自动插一列「序号」，EP 还会把 fixed 列排到最前，
 *    所以 summary-method 收到的列序号和模板里看到的不一样——按序号写必错。
 *
 * 用法：
 *   const summaryMethod = makeLineSummary({ 数量: { field: 'qty', type: 'qty' }, 价税合计: { field: 'amount', type: 'money' } })
 *   <el-table show-summary :summary-method="summaryMethod" ...>
 */
export function makeLineSummary(fieldMap: Record<string, SumSpec>, label = '合计', qtyFmt: (n: number) => string = fmtQty) {
  return ({ columns, data }: { columns: any[]; data: any[] }): string[] => {
    const labelAt = Math.max(0, columns.findIndex((c: any) => c.type !== 'selection'))
    return columns.map((col: any, i: number) => {
      if (i === labelAt) return label
      const spec = fieldMap[col.label]
      if (!spec) return ''
      const vals = (data || []).map((r: any) => spec.value ? spec.value(r) : r[spec.field!])
      // 金额对没权限的岗位是空的（后端脱敏）：整列都空就显示「-」，别显示 0.00 让人以为金额是零
      if (spec.type === 'money' && vals.length && vals.every(v => v == null || v === '')) return '-'
      const total = vals.reduce((s: number, v: any) => s + Number(v || 0), 0)
      return spec.type === 'qty' ? qtyFmt(total) : spec.type === 'count' ? String(total) : fmtMoney(total)
    })
  }
}
