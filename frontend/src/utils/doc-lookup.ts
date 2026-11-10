import { get } from '@/api/request'
import { masterApi } from '@/api'
import { docStatusLabel } from '@/utils/statusLabel'

/**
 * 单据号选择器(DocNoLookup)的数据来源(2026-10-16 查询条件改造)。
 *
 * 🔴 数据隔离:不另开"查单号"接口,直接调这类单据自己的列表接口(带单号关键字、只取 50 条)。
 * 列表接口本来就有"只看本账号能看的公司 + 岗位门槛 + 金额脱敏",选择器自动跟它一模一样,
 * 不会出现"列表看不到、下拉框却列出来"的漏洞。没权限的岗位接口报 4003,选择器静默显示空,不弹红字。
 */
export interface DocColumn { prop: string; label: string; width?: number }
export interface DocSource {
  title: string
  url: string
  /** 单据日期字段(下拉第二行显示) */
  dateProp: string
  columns: DocColumn[]
  /** 这类单据特有的状态叫法(如盘点单 SUBMITTED=待财务确认),没有就用全站通用叫法 */
  statusMap?: Record<string, string>
}

export const statusText = (s: unknown) => (s ? docStatusLabel(String(s)) : '')

const common = (dateProp: string, dateLabel: string, extra: DocColumn[]): DocColumn[] => [
  { prop: 'docNo', label: '单据号', width: 215 },
  { prop: dateProp, label: dateLabel, width: 110 },
  ...extra,
  { prop: 'materialSummary', label: '物料' },
  { prop: 'statusText', label: '状态', width: 90 }
]

/**
 * 部分列表接口只给 supplierId/warehouseId(页面自己再查名称),这里同样补上名称。
 * 名称表本身也是按账号可见范围给的(仓库按公司隔离;供应商集团共用,09-17 定),只取一次。
 */
let namesPromise: Promise<{ sup: Map<number, string>; wh: Map<number, string> }> | null = null
function names() {
  if (!namesPromise) {
    namesPromise = Promise.all([
      (masterApi.allSuppliers() as Promise<any[]>).catch(() => []),
      (masterApi.warehouses(true) as Promise<any[]>).catch(() => [])
    ]).then(([sups, whs]) => ({
      sup: new Map((sups || []).map((x: any) => [x.id, x.name])),
      wh: new Map((whs || []).map((x: any) => [x.id, x.name]))
    }))
  }
  return namesPromise
}

const noMat = (cols: DocColumn[]) => cols.filter(c => c.prop !== 'materialSummary')

export const DOC_SOURCES: Record<string, DocSource> = {
  PO: { title: '选择采购订单', url: '/purchase/po', dateProp: 'orderDate',
    columns: common('orderDate', '下单日期', [{ prop: 'supplierName', label: '供应商' }, { prop: 'warehouseName', label: '仓库', width: 140 }]) },
  RECV: { title: '选择入库单', url: '/inbound/recv', dateProp: 'recvDate',
    columns: common('recvDate', '入库日期', [{ prop: 'supplierName', label: '供应商' }, { prop: 'warehouseName', label: '仓库', width: 140 }]) },
  PRET: { title: '选择采购退货单', url: '/inbound/purchase-return', dateProp: 'returnDate',
    columns: common('returnDate', '退货日期', [{ prop: 'supplierName', label: '供应商' }, { prop: 'warehouseName', label: '仓库', width: 140 }]) },
  STOCKTAKE: { title: '选择盘点单', url: '/stocktake', dateProp: 'takeDate',
    columns: common('takeDate', '盘点日期', [{ prop: 'warehouseName', label: '仓库' }, { prop: 'createdByName', label: '制单人', width: 100 }])
      .filter(c => c.prop !== 'materialSummary'),
    statusMap: { SUBMITTED: '待财务确认', APPROVED: '已确认' } },
  // ── 出库链(2026-10-20 查询面板第二批):列表接口不带物料摘要,这几类不显示「物料」列 ──
  OREQ: { title: '选择出库申请', url: '/outbound/oreq', dateProp: 'applyDate',
    columns: noMat(common('applyDate', '申请日期', [{ prop: 'projectName', label: '工程项目' }, { prop: 'materialApplicant', label: '申请人', width: 100 }, { prop: 'warehouseName', label: '仓库', width: 140 }])) },
  OUT: { title: '选择材料出库单', url: '/outbound/out', dateProp: 'outDate',
    columns: noMat(common('outDate', '出库日期', [{ prop: 'projectName', label: '工程项目' }, { prop: 'warehouseName', label: '仓库', width: 140 }])) },
  RET: { title: '选择退料单', url: '/outbound/return', dateProp: 'returnDate',
    columns: noMat(common('returnDate', '退料日期', [{ prop: 'projectName', label: '工程项目' }, { prop: 'warehouseName', label: '仓库', width: 140 }])) },
  LOAN: { title: '选择借料单', url: '/outbound/loan', dateProp: 'borrowDate',
    columns: noMat(common('borrowDate', '借料日期', [{ prop: 'borrower', label: '借料人', width: 100 }, { prop: 'projectName', label: '工程项目' }, { prop: 'warehouseName', label: '仓库', width: 140 }])) },
  TRANSFER: { title: '选择转库单', url: '/inventory/transfer', dateProp: 'transferDate',
    columns: noMat(common('transferDate', '转库日期', [{ prop: 'fromWarehouseName', label: '转出仓库' }, { prop: 'toWarehouseName', label: '转入仓库' }])) }
}

/** 默认列最近 50 条(列表接口默认按录入倒序),打字时按单号在全部数据里模糊查(10-16 定) */
export async function fetchDocs(source: DocSource, keyword: string, extra: Record<string, unknown> = {}) {
  try {
    const page: any = await get(source.url, { current: 1, size: 50, keyword: keyword.trim() || undefined, ...extra },
      { silentCodes: [4003] } as any)
    const { sup, wh } = await names()
    return ((page?.records || []) as any[]).map(r => ({
      ...r,
      supplierName: r.supplierName || sup.get(r.supplierId) || '',
      warehouseName: r.warehouseName || wh.get(r.warehouseId) || '',
      fromWarehouseName: r.fromWarehouseName || wh.get(r.fromWarehouseId) || '',
      toWarehouseName: r.toWarehouseName || wh.get(r.toWarehouseId) || '',
      statusText: source.statusMap?.[r.status] || statusText(r.status)
    }))
  } catch {
    return []
  }
}
