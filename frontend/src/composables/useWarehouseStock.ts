import { ref } from 'vue'
import { get } from '@/api/request'

/**
 * 数量按"万分之一"取整后再算（库里数量是 4 位小数）。
 * 为什么：JS 小数直接相加有误差，0.1+0.2 = 0.30000000000000004，
 * 和库存 0.3 一比就成了"超库存"——明明刚好够却误报(2026-10-08 复查发现)。
 */
const SCALE = 10000
const toUnits = (v: unknown) => Math.round(Number(v || 0) * SCALE)

const totalUnits = (values: unknown[]): number => {
  let total = 0
  for (const v of values) total += toUnits(v)
  return total
}

/** 几行数量合计(已消除小数误差) */
export function sumQty(values: unknown[]): number {
  return totalUnits(values) / SCALE
}

/**
 * 两个数量相减(已消除小数误差)，如 剩余 = 申请量 − 已出量。
 * 为什么：0.3 − 0.1 在 JS 里是 0.19999999999999998，页面会把这串数字原样显示出来，
 * 再填 0.2 还会被判"超过剩余数量"(2026-10-08 手机端材料出库实测复现)。
 */
export function subQty(a: unknown, b: unknown): number {
  return (toUnits(a) - toUnits(b)) / SCALE
}

/** 合计是否超过上限(库存、申请剩余量等)；刚好相等不算超 */
export function exceedsStock(values: unknown[], stock: number): boolean {
  return totalUnits(values) > toUnits(stock)
}

/**
 * 某个仓库的"当前库存"速查表（材料出库制单用，电脑端和手机端共用）。
 *
 * 为什么要有它：材料出库在制单/提交时不校验库存，要到审批扣库存那一步才报"库存不足"，
 * 仓管开了单才发现发不了（曾有一批单据就是这么卡住的）。这里把库存就地显示出来。
 *
 * 口径（给后来人）：
 * - 显示的是打开那一刻的账面数，只做提醒，不拦保存。2026-10-20 起提交审批时后端会按「账面 − 其他已提交未扣库存单据的占用」
 *   拦一次（SubmitStockGuard），审批过账时库存引擎再拦一次。
 * - 这里没有扣掉别的"已提交未审批"单据的占用，所以这里显示"够"，提交时仍可能因为被占用而被拦（提示里会写占用单号）。
 * - 按仓库一次拉全表，不逐行请求。
 * - 读取失败/无权限 = 未知(null)，绝不能当成 0 显示成"无库存"。
 */
export function useWarehouseStock() {
  const stockMap = ref<Record<number, number> | null>(null)
  const stockLoading = ref(false)
  let requestId = 0

  async function loadStock(warehouseId: number | null | undefined) {
    const id = ++requestId
    stockMap.value = null
    if (!warehouseId) { stockLoading.value = false; return }
    stockLoading.value = true
    try {
      // ⚠️ 参数名是 whId(不是 warehouseId)——传错后端会退化成"全部仓库合计"，库存数就错了。
      //    零库存行默认不返回，查不到即视为 0（见 stockOf）。
      //    4003=没有库存查看权限：静默显示"暂不可用"，不弹红字打断制单。
      const rows: any[] = await get('/inventory/balance', { whId: warehouseId }, { silentCodes: [4003] } as any)
      const m: Record<number, number> = {}
      for (const r of (Array.isArray(rows) ? rows : [])) {
        const mid = Number(r.materialId)
        // 同一物料若有多行（多库位），合计
        if (!Number.isNaN(mid)) m[mid] = sumQty([m[mid], r.qty])
      }
      // 换了申请单时旧请求可能晚回来，丢弃过期结果
      if (id === requestId) stockMap.value = m
    } catch {
      if (id === requestId) stockMap.value = null
    } finally {
      if (id === requestId) stockLoading.value = false
    }
  }

  /** 该物料在当前仓库的库存；null = 查询中或没查到（未知） */
  function stockOf(materialId: number | null | undefined): number | null {
    if (materialId == null || stockMap.value === null) return null
    return stockMap.value[materialId] ?? 0
  }

  return { stockMap, stockLoading, loadStock, stockOf }
}
