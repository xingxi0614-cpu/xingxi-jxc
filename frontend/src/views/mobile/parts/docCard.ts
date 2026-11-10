/**
 * 手机「单据」列表里一张卡片上显示什么（2026-10-09）。
 *
 * 为什么要有：原来每张卡只有单号、日期、状态，几十张采购订单长得一模一样，只能逐张点进去找。
 * 现在多带上「跟谁有关」（供应商 / 工程项目 / 借料人）和数量、金额，不点开就能认出是哪张。
 *
 * 口径：
 *   · 以分页接口返回的字段为主，和电脑端列表页看到的是同一份数据。
 *     供应商、仓库接口只给编号，名字由页面另查档案后传进来（见 DocLookups）。
 *   · 金额是否显示完全听后端的：仓管角色读到的金额是空的（后端脱敏），这里拿到空值就不显示，
 *     不在前端另判角色——前端判断会和后端口径走岔。
 *   · 某类单据没有的字段直接跳过，不显示「-」「0」占位。
 */

function text(v: any): string { return v === null || v === undefined ? '' : String(v).trim() }

function fmtQty(v: any): string {
  const n = Number(v)
  if (v === null || v === undefined || v === '' || !isFinite(n)) return ''
  // 库里数量是 4 位小数，抹掉浮点尾巴再去掉多余的 0
  return String(Math.round(n * 1e4) / 1e4)
}
function fmtAmt(v: any): string {
  const n = Number(v)
  if (v === null || v === undefined || v === '' || !isFinite(n)) return ''
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

/** 列表接口只给编号、不给名字的两样档案，由页面查好传进来（电脑端列表也是这么对出名字的） */
export interface DocLookups { suppliers: Record<string, string>; warehouses: Record<string, string> }

/** 卡片标题：这张单跟谁有关。都没有时返回空串，由页面只显示单号 */
export function docCardTitle(type: string, row: any, look: DocLookups): string {
  if (type === 'TRANSFER') {
    const from = look.warehouses[row.fromWarehouseId], to = look.warehouses[row.toWarehouseId]
    return from && to ? `${from} → ${to}` : ''
  }
  return text(row.supplierName) || text(look.suppliers[row.supplierId]) || text(row.projectName) || text(row.borrower) || text(row.purpose)
}

/** 标题下面那行小字：经手人、来源单、数量 */
export function docCardMetas(type: string, row: any): string[] {
  const out: string[] = []
  const who = text(row.materialApplicant) || text(row.materialReceiver) || text(row.returner) || text(row.transferer)
  if (who) out.push(who)
  // 借料的标题是借料人，用途放小字里
  if (type === 'LOAN' && text(row.purpose)) out.push(text(row.purpose))
  const source = text(row.sourcePoNo) || text(row.sourceReqNo) || text(row.sourceOutNo) || text(row.sourceRecvNo)
  if (source) out.push(`来源 ${source}`)
  if (type === 'INV' && text(row.invoiceNo)) out.push(`票号 ${text(row.invoiceNo)}`)
  const qty = fmtQty(row.totalQty)
  if (qty) out.push(`数量 ${qty}`)
  return out
}

/** 物料摘要（采购订单、入库单的列表接口带这个字段；别的单据没有就不显示） */
export function docCardMaterials(row: any): string { return text(row.materialSummary) }

/** 金额：后端给了才显示（仓管拿到的是空） */
export function docCardAmount(row: any): string { return fmtAmt(row.totalAmt) }
