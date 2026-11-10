import { computed, ref, watch, type Ref } from 'vue'

/**
 * 报表前端分页（2026-10-17）。
 *
 * 为什么要：收发存汇总、出库汇总、工程材料计划执行这几张报表，后端一次返回全部行（服务器只用 0.05~0.4 秒），
 * 慢在浏览器把几千行 × 二十多列一次性画出来（用户反馈打开要卡 5 秒以上）。
 * 只画当前一页（默认 100 行），合计行、顶部汇总卡、导出 / 打印 / 复制仍按全部行算，口径不变。
 */
export function useClientPager<T>(rows: Ref<T[]>, defaultSize = 100) {
  const page = ref(1)
  const size = ref(defaultSize)
  const pageRows = computed(() => rows.value.slice((page.value - 1) * size.value, page.value * size.value))
  /** 本页第一行之前有多少行：给序号列接着往下数，不要每页都从 1 开始 */
  const offset = computed(() => (page.value - 1) * size.value)
  // 重新查询后回到第一页
  watch(rows, () => { page.value = 1 })
  watch(size, () => { page.value = 1 })
  return { page, size, pageRows, offset }
}
