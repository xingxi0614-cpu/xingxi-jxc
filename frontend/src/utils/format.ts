import dayjs from 'dayjs'

/**
 * 金额四舍五入到分，口径与后台 Java BigDecimal HALF_UP 一致（负数按绝对值进位）。
 * 🔴 2026-10-18(计算审查 U36):以前用 Math.round((v + EPSILON) * 100)，EPSILON 只够抵消 1 附近的误差——
 *    数量 1 × 单价 10.0750 在二进制里是 10.07499999…，算成 10.07，后台算是 10.08，订单和发票都少一分。
 *    现在先按 15 位有效数字把二进制误差规整掉，再用科学计数法挪小数点取整，不经过有误差的乘 100。
 */
export function roundMoney(v: number): number {
  if (!Number.isFinite(v)) return v
  const sign = v < 0 ? -1 : 1
  const abs = Number(Math.abs(v).toPrecision(15))
  // 不到半分的直接是 0(这类数转字符串会变成 1e-7 这种科学计数法,不能再拼 e2)
  if (abs < 0.005) return 0
  return sign * Number(Math.round(Number(abs + 'e2')) + 'e-2')
}

export function fmtMoney(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '-'
  const v = Number(n)
  if (!Number.isFinite(v)) return '-'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function fmtQty(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '-'
  const v = Number(n)
  if (!Number.isFinite(v)) return '-'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function fmtPrice(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '-'
  const v = Number(n)
  if (!Number.isFinite(v)) return '-'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function fmtAbsMoney(n: number | string | null | undefined): string {
  if (n === null || n === undefined || n === '') return '-'
  const v = Number(n)
  if (!Number.isFinite(v)) return '-'
  return Math.abs(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function fmtInventoryAmount(n: number | string | null | undefined, type: string | null | undefined): string {
  if (['INIT', 'IN_ESTIMATE', 'IN_INVOICE', 'OUT', 'RETURN', 'SALE_OUT', 'SALE_RETURN', 'LOAN', 'LOAN_RETURN', 'TRANSFER_OUT', 'TRANSFER_IN'].includes(type || '')) {
    return fmtAbsMoney(n)
  }
  return fmtMoney(n)
}

export function fmtDate(d: string | Date | undefined | null): string {
  if (!d) return '-'
  return dayjs(d).format('YYYY-MM-DD')
}

export function fmtDateTime(d: string | Date | undefined | null): string {
  if (!d) return '-'
  return dayjs(d).format('YYYY-MM-DD HH:mm:ss')
}

/** 全站时间统一口径：年月日时分（不含秒），用于操作日志/在线用户/用户管理等时间列 */
export function fmtMinute(d: string | Date | undefined | null): string {
  if (!d) return '-'
  return dayjs(d).format('YYYY-MM-DD HH:mm')
}

export function today(): string {
  return dayjs().format('YYYY-MM-DD')
}

export function fmtInventoryTransType(type: string | null | undefined): string {
  if (!type) return '-'
  return ({
    INIT: '期初',
    IN_ESTIMATE: '暂估入库',
    IN_INVOICE: '发票入库',
    OUT: '材料出库',
    RETURN: '退料入库',
    SALE_OUT: '销售出库',
    SALE_RETURN: '销售退货入库',
    LOAN: '借料出库',
    LOAN_RETURN: '借料归还',
    TRANSFER_OUT: '转库出库',
    TRANSFER_IN: '转库入库',
    ADJUST: '差额调整',
    ADJUST_EXPENSE: '差额-计入当期',
    OUT_PRET: '采购退货出库',
    REVERSE: '反审冲回'
  } as Record<string, string>)[type] || type
}

/**
 * 单据里的物料清单（后端给的是「编码 名称(规格)、编码 名称(规格)…」顿号串）在列表/选单里只显示第一种 + 「等 N 种」。
 * 2026-10-17 反馈：参照开票时物料一格悬浮出来一大串，没必要显示这么多；要看全部点开单据看明细。
 */
export function fmtMaterials(s: string | null | undefined): string {
  const list = String(s || '').split('、').map(x => x.trim()).filter(Boolean)
  if (!list.length) return '-'
  return list.length === 1 ? list[0] : `${list[0]} 等 ${list.length} 种`
}
