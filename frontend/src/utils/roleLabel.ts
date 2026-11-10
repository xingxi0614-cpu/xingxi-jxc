/**
 * 角色编码 → 显示名。顶栏等处共用。
 *
 * ⚠️ 这里只管"怎么显示",不管"能干什么"——权限一律走后端的权限点
 * (见「系统管理 → 角色与权限」),前端不许拿角色名判断能不能做某事。
 * 表里没有的编码原样显示,所以新增角色不改这里也不会显示成空白。
 */
const ROLE_LABELS: Record<string, string> = {
  purchaser: '采购',
  warehouse: '仓管',
  finance: '材料会计',
  account_admin: '账套管理员',
  material_accountant: '材料会计',
  finance_manager: '财务部门负责人',
  deputy_general_manager: '分管副总',
  general_manager: '总经理',
  dept_manager: '技术部经理',
  field_manager: '现场管理员',
  tech_manager: '技术部经理',
  service_staff: '网点客服',
  operation_repair: '运维人员',
  branch_manager: '网点负责人',
  area_manager: '片区经理',
  business_leader: '分管副总',
  operation_leader: '运营副总',
  engineering_leader: '工程副总',
  market_leader: '市场副总',
  accountant: '材料会计',
  cashier: '材料会计',
  admin: '管理员'
}

export function roleLabel(code?: string) {
  return code ? (ROLE_LABELS[code] || code) : ''
}
