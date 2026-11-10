/** 更新说明的一条「带看」条目（后端 sys_release_note.guide_json）。 */
export interface GuideItem {
  key: string
  /** rule=规则变化（一般必看）/ new=新功能 / improve=改进 */
  tag?: 'rule' | 'new' | 'improve' | string
  /** 必看：没点开看完，更新弹窗的「知道了」不能点 */
  must?: boolean
  title: string
  desc?: string
  /** 带去哪个页面；为空则不跳页，只在屏幕中间讲 */
  path?: string | null
  /** 页面上要高亮的那块：对应元素的 data-tour 值 */
  target?: string | null
  tip: string
}

export const GUIDE_TAG: Record<string, { label: string; type: 'danger' | 'primary' | 'info' }> = {
  rule: { label: '规则变化', type: 'danger' },
  new: { label: '新功能', type: 'primary' },
  improve: { label: '改进', type: 'info' }
}
