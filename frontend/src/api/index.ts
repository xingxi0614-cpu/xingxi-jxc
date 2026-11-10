/**
 * 统一 API 入口
 *
 * 用法：
 *   import { authApi, poApi } from '@/api'
 *   const list = await poApi.page({ current: 1, size: 20 })
 */
import service, { get, post, put, del } from './request'

export { authApi } from './auth'

/* ============ 通知 ============ */
export const notifApi = {
  my:           (limit = 50) => get('/notif/my', { limit }),
  unreadCount:  ()           => get('/notif/unread-count'),
  read:         (id: number) => post(`/notif/${id}/read`),
  readAll:      ()           => post('/notif/read-all')
}

/* ============ 主数据 ============ */
export const masterApi = {
  suppliers:     (params: any) => get('/master/suppliers', params),
  allSuppliers:  (params: any = {}) => get('/master/suppliers/all', params),
  nextSupplierCode: ()         => get('/master/suppliers/next-code'),
  saveSupplier:  (s: any)      => s.id ? put(`/master/suppliers/${s.id}`, s) : post('/master/suppliers', s),
  importSuppliers: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post('/master/suppliers/import', form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  supplierImportTemplate: () => get<Blob>('/master/suppliers/import-template', undefined, { responseType: 'blob' }),
  delSupplier:   (id: number)  => del(`/master/suppliers/${id}`),
  batchAssignSupplierOrg: (ids: number[], orgId: number) => put('/master/suppliers/batch-assign-org', { ids, orgId }),
  removeSupplierScope: (id: number, orgId: number) => del(`/master/suppliers/${id}/scope/${orgId}`),

  materials:     (params: any) => get('/master/materials', params),
  allMaterials:  (params: any = {}) => get('/master/materials/all', params),
  materialCats:  (params: any = {}) => get('/master/material-cats', params),
  nextMaterialCode: (catId: number) => get('/master/materials/next-code', { catId }),
  saveMaterial:  (m: any)      => m.id ? put(`/master/materials/${m.id}`, m) : post('/master/materials', m),
  importMaterials: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post('/master/materials/import', form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  materialImportTemplate: () => get<Blob>('/master/materials/import-template', undefined, { responseType: 'blob' }),
  delMaterial:   (id: number)  => del(`/master/materials/${id}`),
  batchAssignMaterialOrg: (ids: number[], orgId: number) => put('/master/materials/batch-assign-org', { ids, orgId }),
  removeMaterialScope: (id: number, orgId: number) => del(`/master/materials/${id}/scope/${orgId}`),
  batchDeleteMaterials: (ids: number[]) => service.delete('/master/materials/batch', { data: { ids } }),

  warehouses:      (params: any = false) => get('/master/warehouses', typeof params === 'boolean' ? { includeDisabled: params } : params),
  saveWarehouse:   (w: any) => w.id ? put(`/master/warehouses/${w.id}`, w) : post('/master/warehouses', w),
  delWarehouse:    (id: number) => del(`/master/warehouses/${id}`),

  projects:        (params: any = false) => get('/master/projects', typeof params === 'boolean' ? { includeDisabled: params } : params),
  saveProject:     (p: any) => p.id ? put(`/master/projects/${p.id}`, p) : post('/master/projects', p),
  importProjects: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post('/master/projects/import', form, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  projectImportTemplate: () => get<Blob>('/master/projects/import-template', undefined, { responseType: 'blob' }),
  delProject:      (id: number) => del(`/master/projects/${id}`),
  projectCodePrefix: (orgId?: number) => get<string>('/master/projects/code-prefix', { orgId })
}

/* ============ 系统 ============ */
/* ============ 顶栏滚动通知(只有系统管理员能发;live 所有登录用户可读本公司的) ============ */
export const tickerApi = {
  live:      () => get('/ticker/live'),
  list:      () => get('/ticker'),
  companies: () => get('/ticker/companies'),
  create:    (data: any) => post('/ticker', data),
  update:    (id: number, data: any) => put(`/ticker/${id}`, data),
  setStatus: (id: number, enabled: boolean) => post(`/ticker/${id}/status`, { enabled }),
  remove:    (id: number) => del(`/ticker/${id}`)
}

export const sysApi = {
  users:        (params: any) => get('/sys/users', params),
  saveUser:     (u: any)      => u.id ? put(`/sys/users/${u.id}`, u) : post('/sys/users', u),
  importUsers:  (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post('/sys/users/import', form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  userImportTemplate: () => get<Blob>('/sys/users/import-template', undefined, { responseType: 'blob' }),
  resetPwd:     (id: number, password: string)  => post(`/sys/users/${id}/reset-password`, { password }),
  unlockUser:   (id: number)  => post(`/sys/users/${id}/unlock`),
  delUser:      (id: number)  => del(`/sys/users/${id}`),
  roles:        ()            => get('/sys/roles'),
  roleMenus:    (id: number)  => get(`/sys/roles/${id}/menus`),
  saveRoleMenus:(id: number, menuIds: number[]) => put(`/sys/roles/${id}/menus`, { menuIds }),
  /** 角色增删改(仅系统管理员;角色是全系统共用的，改了影响所有公司) */
  createRole:   (data: any)   => post('/sys/roles', data),
  updateRole:   (id: number, data: any) => put(`/sys/roles/${id}`, data),
  delRole:      (id: number)  => del(`/sys/roles/${id}`),
  orgTree:      ()            => get('/sys/orgs/tree'),
  saveOrg:       (o: any)      => o.id ? put(`/sys/orgs/${o.id}`, o) : post('/sys/orgs', o),
  saveOrgApprovalMode: (id: number, data: any) => put(`/sys/orgs/${id}/approval-mode`, data),
  delOrg:        (id: number)  => del(`/sys/orgs/${id}`),
  menuTree:     ()            => get('/sys/menus/tree'),
  params:       ()            => get('/sys/params'),
  optionList:   (key: string) => get(`/sys/options/${key}`),
  saveParam:    (key: string, data: any) => put(`/sys/params/${key}`, data),
  initializationPreview: ()   => get('/sys/initialization/preview'),
  formalReset: (confirmText: string) => post('/sys/initialization/formal-reset', { confirmText }),
  onlineSummary: ()          => get('/sys/online-users/summary'),
  onlineUsers:  ()            => get('/sys/online-users'),
  kickOnlineUser:(userId: number) => post(`/sys/online-users/${userId}/kick`),
  latestReleaseNote: (signal?: AbortSignal) => get('/sys/release-notes/latest', undefined, { signal }),
  releaseNotes:  (params: any) => get('/sys/release-notes', params),
  // 更新说明逐条带看：条目+我已看过的、记一条已看、必看未读名单(管理员/账套管理员/财务)
  releaseGuide:  (version: string, signal?: AbortSignal) => get('/sys/release-notes/guide', { version }, { signal }),
  releaseGuideRead: (version: string, itemKey: string, signal?: AbortSignal) => post('/sys/release-notes/guide/read', { version, itemKey }, { signal }),
  releaseGuideUnread: (version: string) => get('/sys/release-notes/guide/unread', { version }),
  docRules:     ()            => get('/master/doc-rules'),
  workflows:    ()            => get('/master/workflows')
}

/* ============ 采购订单 ============ */
export const poApi = {
  page:    (params: any) => get('/purchase/po', params),
  detail:  (id: number)  => get(`/purchase/po/${id}`),
  create:  (data: any)   => post('/purchase/po', data),
  update:  (id: number, data: any) => put(`/purchase/po/${id}`, data),
  submit:  (id: number)  => post(`/purchase/po/${id}/submit`),
  withdraw:(id: number)  => post(`/purchase/po/${id}/withdraw`),
  delete:  (id: number)  => del(`/purchase/po/${id}`),
  approve: (id: number, comment: string) => post(`/purchase/po/${id}/approve`, { comment }),
  reject:  (id: number, comment: string) => post(`/purchase/po/${id}/reject`, { comment }),
  correctTax: (id: number, fixes: any[]) => post(`/purchase/po/${id}/correct-tax`, fixes),
  // 历史参考含税单价（单价录成金额防错）：materialIds 用逗号串，Spring 自动转 List<Long>
  priceRef: (materialIds: number[], excludePoId?: number | null) => get('/purchase/po/price-ref', { materialIds: materialIds.join(','), excludePoId: excludePoId || undefined })
}

/* ============ 入库 ============ */
export const recvApi = {
  page:    (params: any) => get('/inbound/recv', params),
  detail:  (id: number)  => get(`/inbound/recv/${id}`),
  fromPo:  (data: any)   => post('/inbound/recv/from-po', data),
  submit:  (id: number)  => post(`/inbound/recv/${id}/submit`),
  withdraw:(id: number)  => post(`/inbound/recv/${id}/withdraw`),
  delete:  (id: number)  => del(`/inbound/recv/${id}`),
  approve: (id: number, comment: string) => post(`/inbound/recv/${id}/approve`, { comment }),
  reject:  (id: number, comment: string) => post(`/inbound/recv/${id}/reject`,  { comment }),
  estimatesForInvoicing: (supplierId?: number) => get('/inbound/recv/estimates-for-invoicing', { supplierId })
}

/* ============ 采购退货（退回供应商） ============ */
export const purchaseReturnApi = {
  page:       (params: any) => get('/inbound/purchase-return', params),
  detail:     (id: number)  => get(`/inbound/purchase-return/${id}`),
  returnable: (recvId: number) => get(`/inbound/purchase-return/returnable/${recvId}`),
  fromRecv:   (data: any)   => post('/inbound/purchase-return/from-recv', data),
  submit:     (id: number)  => post(`/inbound/purchase-return/${id}/submit`),
  withdraw:   (id: number)  => post(`/inbound/purchase-return/${id}/withdraw`),
  delete:     (id: number)  => del(`/inbound/purchase-return/${id}`),
  approve:    (id: number, comment: string) => post(`/inbound/purchase-return/${id}/approve`, { comment }),
  reject:     (id: number, comment: string) => post(`/inbound/purchase-return/${id}/reject`,  { comment })
}

/* ============ 发票 ============ */
export const invoiceApi = {
  page:    (params: any) => get('/invoice', params),
  detail:  (id: number)  => get(`/invoice/${id}`),
  create:  (data: any)   => post('/invoice', data),
  submit:  (id: number)  => post(`/invoice/${id}/submit`),
  withdraw:(id: number)  => post(`/invoice/${id}/withdraw`),
  delete:  (id: number)  => del(`/invoice/${id}`),
  approve: (id: number, comment: string) => post(`/invoice/${id}/approve`, { comment }),
  reject:  (id: number, comment: string) => post(`/invoice/${id}/reject`, { comment })
}

/* ============ 出库 ============ */
export const outApi = {
  pickupDetail: (id: number) => get(`/outbound/oreq/${id}/pickup`),
  pickupCreate: (id: number,data: any) => post(`/outbound/oreq/${id}/pickup`,data),
  pickupExtend: (id: number,data: any) => post(`/outbound/oreq/${id}/pickup/extend`,data),
  pickupClose: (id: number,data: any) => post(`/outbound/oreq/${id}/pickup/close`,data),
  pickupReview: (id: number,eventId: number,data: any) => post(`/outbound/oreq/${id}/pickup/close/${eventId}`,data),
  oreqPage:    (params: any) => get('/outbound/oreq', params),
  oreqDetail:  (id: number)  => get(`/outbound/oreq/${id}`),
  // 同一工程是否已有未完成的工程类申请(保存前提醒用,只提醒不拦)
  oreqOpenOnProject: (projectId: number, excludeId?: number) => get('/outbound/oreq/open-on-project', { projectId, excludeId }),
  oreqCreate:  (data: any)   => post('/outbound/oreq', data),
  oreqUpdate:  (id: number, data: any) => put(`/outbound/oreq/${id}`, data),
  oreqSubmit:  (id: number)  => post(`/outbound/oreq/${id}/submit`),
  oreqWithdraw:(id: number)  => post(`/outbound/oreq/${id}/withdraw`),
  oreqDelete:  (id: number)  => del(`/outbound/oreq/${id}`),
  oreqApprove: (id: number, c: string) => post(`/outbound/oreq/${id}/approve`, { comment: c }),
  oreqReject:  (id: number, c: string) => post(`/outbound/oreq/${id}/reject`,  { comment: c }),
  oreqRevisions: (id: number) => get(`/outbound/oreq/${id}/revisions`),
  oreqRevisionCreate: (id: number, data: any) => post(`/outbound/oreq/${id}/revision`, data),
  oreqRevisionDetail: (id: number) => get(`/outbound/oreq/revision/${id}`),
  oreqRevisionSubmit: (id: number) => post(`/outbound/oreq/revision/${id}/submit`),
  oreqRevisionApprove: (id: number, c: string) => post(`/outbound/oreq/revision/${id}/approve`, { comment: c }),
  oreqRevisionReject: (id: number, c: string) => post(`/outbound/oreq/revision/${id}/reject`, { comment: c }),

  outPage:     (params: any) => get('/outbound/out', params),
  outDetail:   (id: number)  => get(`/outbound/out/${id}`),
  outFromOreq: (data: any)   => post('/outbound/out/from-oreq', data),
  outSubmit:   (id: number)  => post(`/outbound/out/${id}/submit`),
  outWithdraw: (id: number)  => post(`/outbound/out/${id}/withdraw`),
  outDelete:   (id: number)  => del(`/outbound/out/${id}`),
  outApprove:  (id: number, c: string) => post(`/outbound/out/${id}/approve`, { comment: c }),
  outReject:   (id: number, c: string) => post(`/outbound/out/${id}/reject`,  { comment: c }),

  returnPage:   (params: any) => get('/outbound/return', params),
  returnDetail: (id: number)  => get(`/outbound/return/${id}`),
  returnFromOut:(data: any)   => post('/outbound/return/from-out', data),
  returnFromOreq:(data: any)  => post('/outbound/return/from-oreq', data),
  returnSubmit: (id: number)  => post(`/outbound/return/${id}/submit`),
  returnWithdraw:(id: number) => post(`/outbound/return/${id}/withdraw`),
  returnDelete: (id: number)  => del(`/outbound/return/${id}`),
  returnApprove:(id: number, c: string) => post(`/outbound/return/${id}/approve`, { comment: c }),
  returnReject: (id: number, c: string) => post(`/outbound/return/${id}/reject`,  { comment: c }),

  loanPage:   (params: any) => get('/outbound/loan', params),
  loanDetail: (id: number)  => get(`/outbound/loan/${id}`),
  loanCreate: (data: any)   => post('/outbound/loan', data),
  loanReturn: (id: number, data: any) => post(`/outbound/loan/${id}/return`, data),
  loanDelete: (id: number)  => del(`/outbound/loan/${id}`),
  loanSubmit: (id: number)  => post(`/outbound/loan/${id}/submit`),
  loanWithdraw: (id: number) => post(`/outbound/loan/${id}/withdraw`),
  loanApprove: (id: number, comment: string) => post(`/outbound/loan/${id}/approve`, { comment }),
  loanReject: (id: number, comment: string) => post(`/outbound/loan/${id}/reject`, { comment })
}

/* ============ 库存 ============ */
export const inventoryApi = {
  balance:      (params: any) => get('/inventory/balance', params),
  single:       (matId: number, whId: number) => get(`/inventory/balance/${matId}/${whId}`),
  openingImport: (warehouseId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post(`/inventory/opening/import?warehouseId=${warehouseId}`, form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  openingImportTemplate: () => get<Blob>('/inventory/opening/import-template', undefined, { responseType: 'blob' }),
  stockLimitList: (params: any) => get('/inventory/stock-limit', params),
  stockLimitSave: (items: any[]) => post('/inventory/stock-limit', items),
  transactions: (params: any) => get('/inventory/transactions', params),
  transactionsPage: (params: any) => get('/inventory/transactions/page', params),
  transferHandover: (id: number,data: any) => post(`/inventory/transfer/${id}/handover`,data),
  transferCancelPickup: (id: number,data: any) => post(`/inventory/transfer/${id}/cancel-pickup`,data),
  transferPage:   (params: any) => get('/inventory/transfer', params),
  transferDetail: (id: number) => get(`/inventory/transfer/${id}`),
  transferCreate: (data: any) => post('/inventory/transfer', data),
  transferUpdate: (id: number, data: any) => put(`/inventory/transfer/${id}`, data),
  transferSubmit: (id: number) => post(`/inventory/transfer/${id}/submit`),
  transferWithdraw:(id: number) => post(`/inventory/transfer/${id}/withdraw`),
  transferDelete: (id: number) => del(`/inventory/transfer/${id}`),
  transferApprove:(id: number, comment: string) => post(`/inventory/transfer/${id}/approve`, { comment }),
  transferReject: (id: number, comment: string) => post(`/inventory/transfer/${id}/reject`, { comment }),
  recalc:       (params: any) => post('/inventory/recalc', params)
}

/* ============ 库存期间锁定 ============ */
/** 工作台「需要你留意」：按角色与账套算好的异常提醒（只含条数与单号） */
export const workApi = {
  alerts: () => get('/work/alerts'),
  // 建单提前提示（已结账/盘点中）。仅提示用，查不到就不提示，保存时后端照常校验
  docGuard: (params: { date?: string; warehouseIds?: string; sourceOrgId?: number; operation?: string }) => get('/work/doc-guard', params, { silentCodes: [4003, 500] } as any)
}

export const periodCloseApi = {
  reminders: () => get('/period-close/reminders'),
  records: (params: any) => get('/period-close/records', params),
  check:   (data: any)   => post('/period-close/check', data),
  close:   (data: any)   => post('/period-close/close', data),
  reopen:  (data: any)   => post('/period-close/reopen', data)
}

/* ============ 数据备份/导出 ============ */
export const backupApi = {
  exportExcel: () => get<Blob>('/sys/backup/export-excel', undefined, { responseType: 'blob' }),
  exportSql:   () => get<Blob>('/sys/backup/export-sql', undefined, { responseType: 'blob' })
}

/* ============ 演示账套 ============ */
export const demoApi = {
  reset: () => post('/system/demo/reset', {})
}

/* ============ 盘点单 ============ */
export const stocktakeApi = {
  page:       (params: any) => get('/stocktake', params),
  detail:     (id: number) => get(`/stocktake/${id}`),
  create:     (data: any) => post('/stocktake', data),
  saveActual: (id: number, lines: any[]) => put(`/stocktake/${id}/actual`, lines),
  importActual: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post(`/stocktake/${id}/import-actual`, form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  sheet:      (id: number, blind: 0 | 1) => get<Blob>(`/stocktake/${id}/sheet`, { blind }, { responseType: 'blob' }),
  submit:     (id: number) => post(`/stocktake/${id}/submit`),
  approve:    (id: number) => post(`/stocktake/${id}/approve`),
  reject:     (id: number, reason: string) => post(`/stocktake/${id}/reject`, { reason }),
  remove:     (id: number) => del(`/stocktake/${id}`)
}

/* ============ 单据附件 ============ */
export const attachmentApi = {
  list: (bizType: string, bizId: number) => get('/attachment', { bizType, bizId }, { silentCodes: [4003] } as any),
  upload: (bizType: string, bizId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post(`/attachment/upload?bizType=${bizType}&bizId=${bizId}`, form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 180000 })
  },
  download: (id: number) => get<Blob>(`/attachment/${id}/download`, undefined, { responseType: 'blob', timeout: 180000 }),
  remove: (id: number) => del(`/attachment/${id}`)
}

/* ============ 付款登记 ============ */
export const paymentApi = {
  list:   (q: any)     => get('/payment', q),
  create: (data: any)  => post('/payment', data),
  remove: (id: number) => del(`/payment/${id}`)
}

/* ============ 销售管理 ============ */
export const salesApi = {
  customers:     (keyword?: string) => get('/sale/customers', keyword ? { keyword } : {}),
  // 销售订单
  orderPage:     (q: any)              => get('/sale/order', q),
  orderAvailable:(q: any)              => get('/sale/order/available', q),
  orderDetail:   (id: number)          => get(`/sale/order/${id}`),
  orderCreate:   (data: any)           => post('/sale/order', data),
  orderUpdate:   (id: number, data: any) => put(`/sale/order/${id}`, data),
  orderSubmit:   (id: number)          => post(`/sale/order/${id}/submit`),
  orderWithdraw: (id: number)          => post(`/sale/order/${id}/withdraw`),
  orderApprove:  (id: number, comment = '') => post(`/sale/order/${id}/approve`, { comment }),
  orderReject:   (id: number, comment = '') => post(`/sale/order/${id}/reject`, { comment }),
  orderDelete:   (id: number)          => del(`/sale/order/${id}`),
  // 销售出库
  outPage:       (q: any)              => get('/sale/out', q),
  outExport:     (q: any)              => get<Blob>('/sale/out/export', q, { responseType: 'blob', timeout: 120000 }),
  outAvailableInvoice: (keyword?: string) => get('/sale/out/available-invoice', keyword ? { keyword } : {}),
  outDetail:     (id: number)          => get(`/sale/out/${id}`),
  outFromSo:     (data: any)           => post('/sale/out/from-so', data),
  outSubmit:     (id: number)          => post(`/sale/out/${id}/submit`),
  outWithdraw:   (id: number)          => post(`/sale/out/${id}/withdraw`),
  outApprove:    (id: number, comment = '') => post(`/sale/out/${id}/approve`, { comment }),
  outReject:     (id: number, comment = '') => post(`/sale/out/${id}/reject`, { comment }),
  outDelete:     (id: number)          => del(`/sale/out/${id}`),
  // 销售发票
  invoicePage:   (q: any)              => get('/sale/invoice', q),
  invoiceDetail: (id: number)          => get(`/sale/invoice/${id}`),
  invoiceFromOut:(data: any)           => post('/sale/invoice/from-out', data),
  invoiceUpdate: (id: number, data: any) => put(`/sale/invoice/${id}`, data),
  invoiceSubmit: (id: number)          => post(`/sale/invoice/${id}/submit`),
  invoiceWithdraw:(id: number)         => post(`/sale/invoice/${id}/withdraw`),
  invoiceApprove:(id: number, comment = '') => post(`/sale/invoice/${id}/approve`, { comment }),
  invoiceReject: (id: number, comment = '') => post(`/sale/invoice/${id}/reject`, { comment }),
  invoiceDelete: (id: number)          => del(`/sale/invoice/${id}`),
  // 收款登记
  receiptList:   (q: any)              => get('/sale/receipt', q),
  receiptSummary: (q: any)             => get('/sale/receipt/summary', q),
  receiptCreate: (data: any)           => post('/sale/receipt', data),
  receiptDelete: (id: number)          => del(`/sale/receipt/${id}`),
  arBalance:     (orgId?: number)      => get('/sale/receipt/ar-balance', orgId ? { orgId } : {}),
  // 销售退货
  returnPage:    (q: any)              => get('/sale/return', q),
  returnDetail:  (id: number)          => get(`/sale/return/${id}`),
  returnFromOut: (data: any)           => post('/sale/return/from-out', data),
  returnSubmit:  (id: number)          => post(`/sale/return/${id}/submit`),
  returnWithdraw:(id: number)          => post(`/sale/return/${id}/withdraw`),
  returnApprove: (id: number, comment = '') => post(`/sale/return/${id}/approve`, { comment }),
  returnReject:  (id: number, comment = '') => post(`/sale/return/${id}/reject`, { comment }),
  returnDelete:  (id: number)          => del(`/sale/return/${id}`),
  // 执行查询
  execOrder:     (q: any)              => get('/sale/exec/order', q),
  execOut:       (q: any)              => get('/sale/exec/out', q),
  // 明细查询报表
  reportOrder:   (q: any)              => get('/sale/report/order', q),
  reportOut:     (q: any)              => get('/sale/report/out', q),
  reportInvoice: (q: any)              => get('/sale/report/invoice', q),
  reportReturn:  (q: any)              => get('/sale/report/return', q),
  // 销售收入成本明细表(发票收入×出库成本，按月汇总)
  reportIncomeCost: (q: any)           => get('/sale/report/income-cost', q)
}

/* ============ 单据链 ============ */
export const docChainApi = {
  chain: (docNo: string) => get('/doc-chain', { docNo })
}

/* ============ 工作流 ============ */
export const wfApi = {
  // 打印单据时会顺带取审批记录；无权查看(4003)的人照常打印、只是不带审批记录，不弹红字
  records:       (bizNo: string)         => get('/workflow/records', { bizNo }, { silentCodes: [4003] } as any),
  flow:          (bizNo: string)         => get('/workflow/flow', { bizNo }),
  myDocuments:   (status?: string)       => get('/workflow/my-documents', status ? { status } : {}),
  // 待我审批：按单据真实状态查，不依赖通知是否还在（手机端「待我审」用）
  pendingApprovals: ()                   => get('/workflow/pending-approvals'),
  cancelRequest: (data: any)             => post('/workflow/cancel-request', data),
  pendingCancelRequests: (keyword: string) => get('/workflow/cancel-requests', { keyword }),
  processCancel: (id: number, data: any) => post(`/workflow/cancel-request/${id}/process`, data)
}

/* ============ 报表 ============ */
/** 网点扫码录销售单(免登录,2026-08-21):token 在 URL 里,不需要登录态 */
export const soPublicApi = {
  context:   (token: string) => get('/so-public/context', { token }),
  materials: (token: string, keyword?: string) => get('/so-public/materials', { token, keyword: keyword || '' }),
  submit:    (body: any) => post('/so-public/submit', body),
}

/** 操作助手(2026-08-21 重做):只答「怎么操作」,不答业务数据 */

export const reportApi = {
  /** 集团汇总分析(仅系统管理员)：period = month | last | ytd */
  groupOverview: (period: string) => get<any>('/report/group-overview', { period }),
  poTracking:   (q: any) => post('/report/po-tracking', q),
  estimate:     (q: any) => post('/report/estimate', q),
  balance:      (q: any) => post('/report/balance', q),
  inOutSummary: (q: any) => post('/report/in-out-summary', q),
  inventoryLedger: (q: { orgId?: number | null; materialId: number; startDate?: string | null; endDate?: string | null; warehouseId?: number | null; docTypes?: string | null }) => get('/report/inventory-ledger', q),
  inventoryLedgerCompanies: () => get('/report/inventory-ledger/companies'),
  outSummary:   (q: any) => post('/report/out-summary', q),
  projectMaterialPlan: (q: any) => post('/report/project-material-plan', q),
  // 采购成本差异调整表(2026-08-21 建,10-16 改名):进损益部分财务据此做分录并逐月确认入账
  estDiffList:    (q: any) => post('/report/estimate-diff/list', q),
  estDiffSummary: () => get('/report/estimate-diff/summary'),
  estDiffConfirm: (b: any) => post('/report/estimate-diff/confirm', b),
  financeReconcile: (q: any) => post('/report/finance-reconcile', q),
  outCostBySubject: (q: any) => post('/report/out-cost-by-subject', q),
  lowStock:     () => post('/report/low-stock', {}),
  stockAlert:   () => post('/report/stock-alert', {})
}

/* ============ 出库类型科目映射 ============ */
export const outTypeSubjectApi = {
  list:   () => get('/master/out-type-subject'),
  save:   (data: any) => post('/master/out-type-subject', data),
  remove: (id: number) => del(`/master/out-type-subject/${id}`)
}

/* ============ 领料申请模块 ============ */
export const materialReqApi = {
  // 领料人档案（账套管理员）
  pickerList:   (params: any = {}) => get('/material-req/picker', params),
  pickerCreate: (data: any)        => post('/material-req/picker', data),
  pickerUpdate: (id: number, data: any) => put(`/material-req/picker/${id}`, data),
  pickerDelete: (id: number)       => del(`/material-req/picker/${id}`),
  // 公司级提交链接（账套管理员）
  linkList:     (params: any = {}) => get('/material-req/link', params),
  linkCreate:   (data: any)        => post('/material-req/link', data),
  linkUpdate:   (id: number, data: any) => put(`/material-req/link/${id}`, data),
  linkRegen:    (id: number)       => post(`/material-req/link/${id}/regenerate`, {}),
  linkDelete:   (id: number)       => del(`/material-req/link/${id}`),
  // 免登录提交（仅凭链接 token）
  publicContext:   (token: string)                  => get('/lr-public/context', { token }),
  publicMaterials: (token: string, keyword: string) => get('/lr-public/materials', { token, keyword }),
  publicStock:     (token: string, materialId: number) => get('/lr-public/stock', { token, materialId }),
  publicSubmit:    (data: any)                       => post('/lr-public/submit', data),
  publicUploadAttachment: (token: string, bizType: string, bizId: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return post(`/lr-public/attachment?token=${encodeURIComponent(token)}&bizType=${bizType}&bizId=${bizId}`, form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 180000 })
  },
  // 收件箱（采购/仓管/财务）
  requestList:    (params: any = {})       => get('/material-req/request', params),
  requestDetail:  (id: number)             => get(`/material-req/request/${id}`),
  requestConfirm: (id: number)             => post(`/material-req/request/${id}/confirm`, {}),
  requestReject:  (id: number, reason: string) => post(`/material-req/request/${id}/reject`, { reason }),
  requestDelete:  (id: number)             => del(`/material-req/request/${id}`),
  // 采购计划（免登录提交 + 收件箱）
  publicPpSubmit: (data: any)              => post('/lr-public/pp-submit', data),
  ppList:    (params: any = {})            => get('/material-req/pp', params),
  ppSummary: (params: any = {})            => get('/material-req/pp/summary', params),
  ppDetail:  (id: number)                  => get(`/material-req/pp/${id}`),
  ppConfirm: (id: number, lines: any[])    => post(`/material-req/pp/${id}/confirm`, { lines }),
  ppReject:  (id: number, reason: string)  => post(`/material-req/pp/${id}/reject`, { reason }),
  ppDelete:  (id: number)                  => del(`/material-req/pp/${id}`)
}

/* ============ 工具箱 ============ */
/**
 * 工具箱。所有接口都只能碰【自己公司】的任务 —— 隔离由后端强制，
 * 前端这层做不了防护，也不该做。
 */
export const toolboxApi = {
  permission: ()            => get('/toolbox/permission'),
  tools:      ()            => get('/toolbox/tools'),
  tasks:      ()            => get('/toolbox/tasks'),
  task:       (id: number)  => get(`/toolbox/tasks/${id}`),
  submit:     (data: any)   => post('/toolbox/tasks', data),
  /** 即时型工具：输入就出结果，不生成文件 */
  instant:    (data: any)   => post('/toolbox/instant', data),
  /** 带文件上传的提交 */
  upload:     (toolCode: string, title: string, params: any, file: File) => {
    const form = new FormData()
    form.append('toolCode', toolCode)
    form.append('title', title)
    form.append('params', JSON.stringify(params || {}))
    form.append('file', file)
    return post('/toolbox/tasks/upload', form, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 })
  },
  remove:     (id: number)  => del(`/toolbox/tasks/${id}`),
  /** 下载结果：带登录态取二进制，后端会再校验这个任务是不是我公司的 */
  file:       (id: number, name: string) =>
    get<Blob>(`/toolbox/tasks/${id}/file`, { name }, { responseType: 'blob' })
}

/** 个人资料里的「账号与安全」:只返回本人的最近登录与操作 */
export const profileApi = {
  security: () => get('/profile/security')
}

/**
 * 用户个人偏好（收藏的页面 / 查询常用方案）。
 * 存服务器不存浏览器：换电脑、换浏览器、清缓存都不会丢，跟着账号走。
 * 每个人只能读写自己的（后端从登录态取身份，不接受传 userId）。
 */
export const prefApi = {
  all:    ()                       => get('/user-pref'),
  put:    (key: string, value: any) => put('/user-pref', { key, value: JSON.stringify(value) }),
  remove: (key: string)            => del(`/user-pref/${key}`)
}
