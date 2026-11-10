import { reactive } from 'vue'

/**
 * 状态页签上的「每种状态几张」。
 *
 * 做法：用页面现有的分页查询接口，把当前筛选条件原样带上、只把状态换成每一种、每页只要 1 条，
 * 读返回的 total。这样数字和"点这个页签后列表显示的共 N 条"必然一致（同一个接口、同一套条件），
 * 也不用为每种单据在后端各加一个统计接口。代价是每次查询多发 N 个轻量请求（每个只取 1 行）。
 *
 * 并发防线：连续快速点查询时，只认最后一次的结果（seq），避免旧结果覆盖新结果。
 */
export function useStatusCounts(fetchPage: (q: any) => Promise<any>, statuses: string[]) {
  const counts = reactive<Record<string, number | undefined>>({})
  let seq = 0

  async function refresh(query: Record<string, any>) {
    const my = ++seq
    const base = { ...query, current: 1, size: 1, sortField: '', sortOrder: '' }
    const keys = ['', ...statuses]
    const results = await Promise.allSettled(keys.map(s => fetchPage({ ...base, status: s })))
    if (my !== seq) return
    keys.forEach((k, i) => {
      const r = results[i]
      // 某个状态查失败就不显示数字（宁可空着，也不显示一个错的 0）
      counts[k] = r.status === 'fulfilled' ? Number((r.value as any)?.total ?? 0) : undefined
    })
  }

  function clear() {
    seq++
    for (const k of Object.keys(counts)) counts[k] = undefined
  }

  return { counts, refresh, clear }
}
