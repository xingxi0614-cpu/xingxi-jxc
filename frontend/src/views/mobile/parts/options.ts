import { masterApi } from '@/api'
import type { PickOption } from './MobPicker.vue'

/**
 * 手机建单页「底部弹出选择」用的选项来源。各建单页共用，免得每页各写一遍、写法还不一样。
 */

/** 物料：全站统一显示成「编码 名称(规格)」，副标题带单位 */
export function materialLabel(m: any): string {
  return `${m.code || ''} ${m.name || ''}${m.spec ? `(${m.spec})` : ''}`.trim()
}
export async function searchMaterialOptions(keyword: string): Promise<PickOption[]> {
  const rows = await masterApi.allMaterials({ keyword: keyword || undefined }) as any[]
  return (rows || []).map(m => ({ value: m.id, label: materialLabel(m), sub: m.unit ? `单位：${m.unit}` : undefined }))
}

export async function searchSupplierOptions(keyword: string): Promise<PickOption[]> {
  const rows = await masterApi.allSuppliers({ keyword: keyword || undefined }) as any[]
  return (rows || []).map(s => ({ value: s.id, label: s.name }))
}

/** 仓库、工程项目这类「id + name」的档案，直接转成选项 */
export function nameOptions(rows: any[]): PickOption[] {
  return (rows || []).map(r => ({ value: r.id, label: r.name }))
}
