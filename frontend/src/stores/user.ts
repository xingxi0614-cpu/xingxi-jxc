import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, type UserInfo, type LoginParams, type MenuItem, type ProfileUpdateParams, type ChangePasswordParams } from '@/api/auth'
import { useTabsStore } from './tabs'
import { clearViewOrgId } from './viewCompany'

/** 登录时做的记号:LoginNoticeDialog 读到就弹一次系统通知(每次登录都弹,刷新不弹)。存本标签页 sessionStorage */
export const LOGIN_NOTICE_KEY = 'yt.loginNotice'

export const useUserStore = defineStore('user', () => {
  // 不存储实际 token（防 XSS）；仅用 session flag 标识已登录状态
  // 实际鉴权由后端 HttpOnly Cookie（Sa-Token）完成
  const sessionActive = ref<boolean>(!!localStorage.getItem('scm_session'))
  const userInfo = ref<UserInfo | null>(null)
  const loaded = ref(false)

  const isLogin = computed(() => sessionActive.value)
  const roles = computed(() => userInfo.value?.roles || [])
  const perms = computed(() => userInfo.value?.perms || [])
  const menus = computed<MenuItem[]>(() => filterRetiredMenus(userInfo.value?.menus || []))

  /** 登录 */
  async function login(params: LoginParams) {
    clearViewOrgId()   // 换人登录：上一个人选的「查看公司」不能带过来
    await authApi.login(params)
    // 后端已通过 Set-Cookie 写入 HttpOnly token，前端只记录 session flag
    sessionActive.value = true
    localStorage.setItem('scm_session', '1')
    try { sessionStorage.setItem(LOGIN_NOTICE_KEY, '1') } catch { /* 存不了就不弹通知,不影响登录 */ }
    useTabsStore().reset()
    await loadUserInfo()
  }

  /** 拉取当前用户信息（含菜单与权限），登录后或刷新页面恢复时调用 */
  async function loadUserInfo() {
    if (!sessionActive.value) return
    try {
      userInfo.value = await authApi.me()
      loaded.value = true
    } catch (e) {
      // Cookie 已过期但 session flag 残留，清理后抛出让 router guard 跳转登录
      sessionActive.value = false
      localStorage.removeItem('scm_session')
      throw e
    }
  }

  async function updateProfile(params: ProfileUpdateParams) {
    userInfo.value = await authApi.updateProfile(params)
    loaded.value = true
  }

  async function changePassword(params: ChangePasswordParams) {
    await authApi.changePassword(params)
  }

  /** 登出 */
  async function logout() {
    try { await authApi.logout() } catch {}
    clearSession()
  }

  /** 清理本地会话状态(不调服务端 logout)——会话已失效(4001/401)时由请求拦截器调用,
   *  必须同步重置内存里的 sessionActive,否则路由守卫仍认为已登录、把 /login 又重定向回 /,形成登录死循环 */
  function clearSession() {
    sessionActive.value = false
    userInfo.value = null
    loaded.value = false
    localStorage.removeItem('scm_session')
    clearViewOrgId()
    try { useTabsStore().reset() } catch {}
  }

  /** 权限检查 */
  function hasPerm(code: string): boolean {
    if (roles.value.includes('admin')) return true
    return perms.value.includes(code)
  }
  function hasRole(code: string): boolean {
    return roles.value.includes(code)
  }
  function hasFinanceAccess(): boolean {
    return roles.value.includes('material_accountant') || roles.value.includes('finance')
  }
  function hasFinanceAccessOrAdmin(): boolean {
    return roles.value.includes('admin') || hasFinanceAccess()
  }
  // “谁提交谁删除”：单据的创建人(原始提交人)本人，或管理员。
  // createdBy（多数单据）/ applicantId（出库申请等）任一命中即为本人。
  function ownsDoc(row: any): boolean {
    const me = userInfo.value?.id
    if (!me || !row) return false
    return row.createdBy === me || row.applicantId === me || roles.value.includes('admin')
  }
  // 单据删除可见性：草稿/已驳回由创建人本人(或管理员)删；已作废(终态)仅管理员可清理(走系统软删除，留痕)。
  function canDeleteDoc(row: any): boolean {
    if (!row) return false
    if (['DRAFT', 'REJECTED'].includes(row.status)) {
      if (ownsDoc(row)) return true
      // 领料申请生成的出库申请：经办人=采购(收件箱受理人)。历史单 applicant 可能是财务确认者，
      // 故对领料生成单兜底放开给采购角色显示删除按钮；不给财务(审批人不删发起人的单)。
      const lrGenerated = typeof row.remark === 'string' && row.remark.startsWith('由领料申请')
      if (lrGenerated && roles.value.includes('purchaser')) return true
      return false
    }
    if (row.status === 'CANCELLED') return roles.value.includes('admin') || roles.value.includes('account_admin')
    return false
  }

  return { sessionActive, userInfo, loaded, isLogin, roles, perms, menus,
           login, loadUserInfo, updateProfile, changePassword, logout, clearSession,
           hasPerm, hasRole, hasFinanceAccess, hasFinanceAccessOrAdmin, ownsDoc, canDeleteDoc }
})

function filterRetiredMenus(items: MenuItem[]): MenuItem[] {
  return (items || [])
    .filter(item => !isRetiredPurchaseRequestMenu(item))
    .map(item => ({
      ...item,
      children: filterRetiredMenus(item.children || [])
    }))
}

function isRetiredPurchaseRequestMenu(item: MenuItem): boolean {
  return item.id === 201
    || item.path === '/purchase/pr'
    || item.component === 'purchase/pr/list'
    || item.permCode === 'pr.view'
}
