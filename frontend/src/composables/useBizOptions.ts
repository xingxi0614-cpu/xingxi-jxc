import { ref } from 'vue'
import { sysApi } from '@/api'

/**
 * 业务下拉选项（出库类型 / 业务分类）统一从后端参数（sys_param）读取，
 * 管理员在「系统 → 参数设置」改一次，全站(出库申请/材料出库/退料/工程材料计划报表)同步生效。
 * 参数键：out.type.options / business.category.options（逗号分隔）。
 * 拉取失败或为空时回退到内置默认值，保证页面始终可用。
 */
const OUT_TYPE_FALLBACK = ['销售材料', '工程领料', '生活用料', '运营维修领料', '其他出库']
const BIZ_CAT_FALLBACK = ['工程类', '运营类', '增值销售类', '其他']

export function useBizOptions() {
  const outTypes = ref<string[]>(OUT_TYPE_FALLBACK)
  const businessCategories = ref<string[]>(BIZ_CAT_FALLBACK)

  async function loadBizOptions() {
    try {
      const [ot, bc] = await Promise.all([
        sysApi.optionList('out.type.options').catch(() => []),
        sysApi.optionList('business.category.options').catch(() => [])
      ])
      if (Array.isArray(ot) && ot.length) outTypes.value = ot as string[]
      if (Array.isArray(bc) && bc.length) businessCategories.value = bc as string[]
    } catch {
      /* 保持默认值 */
    }
  }

  return { outTypes, businessCategories, loadBizOptions }
}
