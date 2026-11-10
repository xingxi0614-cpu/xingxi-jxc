import type { Ref } from 'vue'
import { fmtMoney } from '@/utils/format'

export interface SalesFilter {
  dateRange: [string, string] | null
  customerId: number | null
  materialId: number | null
  status: string
  keyword: string
}

export function defaultFilter(): SalesFilter {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const first = `${d.getFullYear()}-${p(d.getMonth() + 1)}-01`
  const today = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
  return { dateRange: [first, today], customerId: null, materialId: null, status: '', keyword: '' }
}

export function toQuery(f: SalesFilter) {
  return {
    startDate: f.dateRange?.[0] || null,
    endDate: f.dateRange?.[1] || null,
    customerId: f.customerId,
    materialId: f.materialId,
    status: f.status || null,
    keyword: f.keyword || null
  }
}

/**
 * show-summary 用：spec = 列名 → { key: 字段, money?: 是否金额格式 }。
 * 🔴 按列名不按列序号：表格会被自动插「序号」列、固定列会排到最前，按序号写合计会落到隔壁列（2026-10-01 修）。
 */
export function salesQuerySummary(rows: Ref<any[]>, spec: Record<string, { key: string; money?: boolean }>) {
  return ({ columns }: any) => {
    const out: string[] = []
    columns.forEach((c: any, i: number) => {
      if (i === 0) { out.push('合计'); return }
      const s = spec[c.label]
      if (!s) { out.push(''); return }
      const sum = rows.value.reduce((acc, r) => acc + Number(r[s.key] || 0), 0)
      out.push(s.money ? fmtMoney(sum) : sum.toLocaleString('zh-CN', { maximumFractionDigits: 4 }))
    })
    return out
  }
}
