import { get, post, put } from './request'

export interface LoginParams { username: string; password: string; force?: boolean }
export interface LoginResult { token: string; tokenName: string; userId: number; username: string; name: string; expiresIn: number }
export interface ProfileUpdateParams { name: string; phone?: string; email?: string; avatar?: string }
export interface ChangePasswordParams { oldPassword: string; newPassword: string }
export interface MenuItem {
  id: number; parentId: number; name: string; path: string; component: string;
  icon: string; permCode: string; type: string; sort: number;
  children: MenuItem[]
}
export interface UserInfo {
  id: number; username: string; name: string; avatar: string;
  phone?: string; email?: string;
  orgId: number; orgName: string;
  /** 向上追溯到公司层级的名称 */
  companyName: string;
  roles: string[]; perms: string[]; menus: MenuItem[]
}

export const authApi = {
  login: (data: LoginParams) => post<LoginResult>('/auth/login', data),
  logout: () => post('/auth/logout'),
  me: () => get<UserInfo>('/auth/me'),
  /** 顶栏查看公司：可选公司 + 组织→所属公司名(范围限于本人权限内) */
  viewScope: () => get<{ switchable: boolean; companies: { id: number; name: string }[]; orgCompany: Record<string, string> }>('/auth/view-scope'),
  updateProfile: (data: ProfileUpdateParams) => put<UserInfo>('/auth/me/profile', data),
  changePassword: (data: ChangePasswordParams) => post('/auth/me/change-password', data),
  ping: () => get('/auth/ping')
}
