import { ElMessageBox } from 'element-plus'
import { poApi } from '@/api'

/**
 * 采购单价防错（2026-09-29）：录单时把含税单价和历史采购价比对。
 * 起因：曾有人把含税单价填进了"含税金额"栏，存货少记一大笔。
 * 阈值与后端 PoPriceCheckService 保持一致——后端才是闸门，这里只负责提前弹窗、少走一次报错。
 */
export const PRICE_LOW_RATIO = 0.2
export const PRICE_HIGH_RATIO = 5
/** 后端"缺价格说明"错误码 */
export const PRICE_CHECK_CODE = 4220

export interface PriceRef { refPrice: number; samples: number }
export interface PriceIssue {
  index: number          // 行下标（0 起）
  materialId: number
  qty: number
  amount: number
  unitIncl: number       // 当前含税单价
  refPrice: number       // 历史参考含税单价
  direction: 'low' | 'high'
  looksLikeUnit: boolean // 金额本身像一个单价 → 典型"单价填进了金额栏"
}

export async function fetchPriceRefs(materialIds: number[], excludePoId?: number | null): Promise<Record<number, PriceRef>> {
  const ids = [...new Set(materialIds.filter(Boolean))]
  if (!ids.length) return {}
  const res: any = await poApi.priceRef(ids, excludePoId)
  const out: Record<number, PriceRef> = {}
  Object.entries(res || {}).forEach(([k, v]: any) => { out[Number(k)] = { refPrice: Number(v.refPrice), samples: Number(v.samples) } })
  return out
}

/** 保存前的参考价查询可降级；登录/权限错误继续终止，价格闸门仍由保存接口执行。 */
export async function fetchPriceRefsForSave(materialIds: number[], excludePoId?: number | null): Promise<Record<number, PriceRef>> {
  try {
    return await fetchPriceRefs(materialIds, excludePoId)
  } catch (e: any) {
    const status = e?.response?.status
    if (e?.code === 4001 || e?.code === 4003 || status === 401 || status === 403) throw e
    return {}
  }
}

export function priceDeviation(unitIncl: number, ref?: PriceRef | null): 'low' | 'high' | null {
  if (!ref || !(ref.refPrice > 0) || !(unitIncl > 0)) return null
  if (unitIncl < ref.refPrice * PRICE_LOW_RATIO) return 'low'
  if (unitIncl > ref.refPrice * PRICE_HIGH_RATIO) return 'high'
  return null
}

/** lines 需带 materialId / qty / amount(含税金额) */
export function findPriceIssues(lines: any[], refs: Record<number, PriceRef>): PriceIssue[] {
  const issues: PriceIssue[] = []
  lines.forEach((l, index) => {
    const qty = Number(l.qty || 0), amount = Number(l.amount || 0)
    if (!l.materialId || qty <= 0) return
    const unitIncl = amount / qty
    const ref = refs[l.materialId]
    const direction = priceDeviation(unitIncl, ref)
    if (!direction || !ref) return
    issues.push({ index, materialId: l.materialId, qty, amount, unitIncl, refPrice: ref.refPrice, direction,
      looksLikeUnit: direction === 'low' && amount > 0 && Math.abs(amount / ref.refPrice - 1) < 0.5 })
  })
  return issues
}

export function deviationText(unitIncl: number, refPrice: number) {
  const k = unitIncl / refPrice
  return k < 1 ? `低了 ${Math.floor((1 - k) * 100)}%` : `高了 ${Math.round(k * 10) / 10} 倍`
}

/**
 * 保存兜底：前端没拦住（参考价没取到、别人刚审了一张新单改变了参考价等）而后端返回 4220 时，
 * 把后端的逐行说明给人看、让人写理由，带理由重存一次；用户取消则原样抛出。
 */
export async function saveWithPriceNote<T>(payload: any, doSave: (p: any) => Promise<T>): Promise<T> {
  try {
    return await doSave(payload)
  } catch (e: any) {
    if (e?.code !== PRICE_CHECK_CODE) throw e
    const { value } = await ElMessageBox.prompt(`${e.message}\n\n确属正常请写一句理由（审批人会看到）：`, '单价和历史采购价差得太多', {
      confirmButtonText: '确认无误，继续保存', cancelButtonText: '返回修改', inputPlaceholder: '例：供应商清库特价',
      inputValidator: (v: string) => !!(v && v.trim()) || '请填写理由', customStyle: { whiteSpace: 'pre-line' } as any
    })
    const note = String(value).trim().slice(0, 200)
    // 每行都带上即可：后端只保留真正偏离的行的理由，其余行会被清空
    payload.lines = (payload.lines || []).map((l: any) => ({ ...l, priceCheckNote: l.priceCheckNote || note }))
    return doSave(payload)
  }
}
