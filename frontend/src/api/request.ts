import axios, { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import { readViewOrgId } from '@/stores/viewCompany'

// 会话失效时清理内存会话状态(仅运行时调用,不在模块加载期执行,避免 pinia 未就绪/循环依赖)
function dropSession() {
  try { useUserStore().clearSession() } catch { localStorage.removeItem('scm_session') }
}

const baseURL = (import.meta.env.VITE_API_BASE || '') + '/api'

const service: AxiosInstance = axios.create({
  baseURL,
  timeout: 30000,
  withCredentials: true  // Cookie（HttpOnly）由浏览器自动附带
})

// 请求拦截：无需手动注入 token，Sa-Token HttpOnly Cookie 自动发送
service.interceptors.request.use(config => {
  // 顶栏选了「查看公司」时告诉后端只看那一家(后端只会在账号权限内收窄，见 stores/viewCompany.ts)
  const viewOrg = readViewOrgId()
  if (viewOrg) config.headers.set('X-View-Org', viewOrg)
  return config
}, err => Promise.reject(err))

/**
 * 会话失效时统一跳登录页,并把【当前这一页】记进 redirect。
 * 这样重新登录后能原地续上,而不是一律被丢回首页——天天填单的人体感差别很大。
 * 登录页自己不带 redirect(免得套娃)。
 */
function gotoLogin() {
  const cur = router.currentRoute.value
  const p = cur.path
  if (p === '/login' || p === '/mobile/login') return
  const target = p.startsWith('/mobile') ? '/mobile/login' : '/login'
  router.push({ path: target, query: { redirect: cur.fullPath } })
}

// 响应拦截：统一处理 code
service.interceptors.response.use((res: AxiosResponse) => {
  const data = res.data
  // 后端列表碰到条数上限(见后端 ResultCap):多出来的行没返回,页面合计也只按这些行算——必须让人知道,不能悄悄截掉
  const cap = res.headers?.['x-result-truncated']
  if (cap) ElMessage({ type: 'warning', grouping: true, duration: 8000, message: `结果太多，只显示了前 ${cap} 条，合计也只按这些行算。请缩小查询条件（如日期范围）后再查。` })
  if (data && typeof data.code !== 'undefined') {
    if (data.code === 0) return data.data
    if (data.code === 4001) {
      // 未登录 / 会话失效（被顶下线、超时等），跳登录并提示后端给出的具体原因
      ElMessage.warning(data.msg || '登录已失效，请重新登录')
      dropSession()
      gotoLogin()
      const err: any = new Error(data.msg || '未登录')
      err.code = 4001
      return Promise.reject(err)
    }
    // 其它业务错误码：把 code 附在 error 上，便于调用方按需处理
    // 4090（账号已在线，需确认强制登录）交由登录页弹确认框，不在此处弹错误提示
    // 4220=采购单价偏离需填理由，由调用方弹框处理（utils/poPriceCheck.ts），这里不重复报错
    // silentCodes：调用方声明自己处理的错误码（如附件列表 4003=无权查看，直接隐藏附件区），不弹红字
    const silent: number[] = (res.config as any)?.silentCodes || []
    const toast = data.code !== 4090 && data.code !== 4220 && !silent.includes(data.code)
    // 长提示（如提交时库存不足会列出物料、占用的单号）默认 3 秒读不完：按字数延长，最长 12 秒，可手动关掉
    const msg = data.msg || '请求失败'
    if (toast) ElMessage.error({ message: msg, duration: Math.min(12000, Math.max(3000, msg.length * 120)), showClose: msg.length > 40 })
    const err: any = new Error(data.msg || '请求失败')
    err.code = data.code
    err.toasted = toast   // 已弹过红字：页面 catch 用 utils/notify.showError 就不会再弹第二条
    return Promise.reject(err)
  }
  return res.data
}, err => {
  if (axios.isCancel(err)) return Promise.reject(err)
  console.error('[axios]', err)
  if (err.response?.status === 401) {
    dropSession()
    gotoLogin()
  } else {
    ElMessage.error(err.response?.data?.msg || err.message || '网络异常')
    err.toasted = true
  }
  return Promise.reject(err)
})

export default service

export function get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
  return service.get(url, { params, ...config }) as any
}
export function post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
  return service.post(url, data, config) as any
}
export function put<T = any>(url: string, data?: any): Promise<T> {
  return service.put(url, data) as any
}
export function del<T = any>(url: string): Promise<T> {
  return service.delete(url) as any
}
