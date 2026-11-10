/**
 * 列表排序：把 el-table 的表头点击翻译成后端排序参数。
 *
 * 为什么不用 el-table 自带的前端排序（sortable 直接给 true）：
 * 列表是分页的，前端排序只会把「当前这 20 条」重排一遍，用户以为是全量排序，
 * 实际拿到的顺序是错的，数据越多越离谱。所以列一律用 sortable="custom"，
 * 由本函数把字段传给后端，让数据库 ORDER BY —— 翻页顺序才是连贯的。
 *
 * 后端只认白名单列（common/SortWhitelist），传了不认识的字段会自动回落到默认排序。
 *
 * 用法：
 *   const onSortChange = makeSortHandler(query, load)
 *   <el-table @sort-change="onSortChange">
 *   <el-table-column prop="recvDate" label="入库日期" sortable="custom" />
 */
export function makeSortHandler(query: any, load: () => any) {
  return ({ prop, order }: { prop?: string; order?: string | null }) => {
    // order 为 null = 用户点到第三下取消排序，此时清空、回到后端默认排序
    query.sortField = order && prop ? prop : ''
    query.sortOrder = order || ''
    query.current = 1
    return load()
  }
}
