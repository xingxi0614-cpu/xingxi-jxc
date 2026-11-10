import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'

/**
 * 顶栏「查看公司」：能看多家公司的账号(目前=系统管理员)选一家公司后，全站列表/报表/打印只看那一家。
 *
 * 分工(改这里前先看)：
 * - 这里只负责"记住选了谁 + 请求时告诉后端"(请求头 X-View-Org，见 api/request.ts)；
 * - 真正的数据过滤在后端 OrgScopeService.visibleOrgIds() 一处做，且只能在账号原有权限内收窄。
 *   所以前端这个值即使被人手工改掉，也看不到权限之外的公司——别把这里当权限控制。
 *
 * 用 sessionStorage：关掉浏览器标签/重新登录后回到「全部公司」，避免"忘了自己上次只选了一家"而看漏数据。
 */
export const VIEW_ORG_KEY = 'scm_view_org'

export function readViewOrgId(): string {
  try { return sessionStorage.getItem(VIEW_ORG_KEY) || '' } catch { return '' }
}
export function clearViewOrgId() {
  try { sessionStorage.removeItem(VIEW_ORG_KEY) } catch { /* 隐私模式等读写失败时无视 */ }
}

export const useViewCompanyStore = defineStore('viewCompany', () => {
  const switchable = ref(false)
  const companies = ref<{ id: number; name: string }[]>([])
  const orgCompany = ref<Record<string, string>>({})
  const selectedId = ref<string>(readViewOrgId())

  const selectedName = computed(() =>
    companies.value.find(c => String(c.id) === selectedId.value)?.name || '')

  async function load() {
    try {
      const r = await authApi.viewScope()
      switchable.value = !!r.switchable
      companies.value = r.companies || []
      orgCompany.value = r.orgCompany || {}
    } catch {
      // 没拿到(掉线/网络问题)：不能据此判断"所选公司已失效"，否则会在掉线瞬间无谓地整页刷新
      switchable.value = false; companies.value = []; orgCompany.value = {}
      return
    }
    // 记着的公司已不在可选范围(换了账号/公司被停用)：必须整页重来。
    // 只清掉记录不够——同一时刻已发出去的请求还带着旧公司，页面会停在一堆"无权查看"的报错上。
    if (selectedId.value && !companies.value.some(c => String(c.id) === selectedId.value)) {
      clearViewOrgId()
      location.reload()
    }
  }

  /** 切换后整页刷新：所有已打开页签里的列表、下拉缓存都是旧公司的，逐个通知不如重来一遍可靠。 */
  function select(id: number | '', goPath?: string) {
    const next = id === '' ? '' : String(id)
    if (next === selectedId.value && !goPath) return
    if (next) sessionStorage.setItem(VIEW_ORG_KEY, next); else clearViewOrgId()
    // goPath：切换的同时落到指定页面(如从集团汇总分析点公司名 → 进那家公司的首页)
    if (goPath) location.hash = '#' + goPath
    location.reload()
  }

  /** 某个组织(单据的归属 orgId)属于哪家公司；查不到返回空串。 */
  function companyOfOrg(orgId: unknown) {
    return orgId == null || orgId === '' ? '' : (orgCompany.value[String(orgId)] || '')
  }

  return { switchable, companies, orgCompany, selectedId, selectedName, load, select, companyOfOrg }
})
