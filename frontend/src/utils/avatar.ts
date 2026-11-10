/**
 * 头像样式与选项 —— 顶栏、个人信息弹窗共用。
 * 只用色块渐变和 emoji,不依赖任何图片资源,所以在任何环境都显示得出来。
 */
export const AVATAR_OPTIONS = [
  { value: 'blue', label: '蓝色' },
  { value: 'green', label: '绿色' },
  { value: 'slate', label: '深灰' },
  { value: 'amber', label: '金色' },
  { value: 'boy', label: '男孩' },
  { value: 'girl', label: '女孩' },
  { value: 'man', label: '男士' },
  { value: 'woman', label: '女士' },
  { value: 'cat', label: '猫' },
  { value: 'dog', label: '狗' },
  { value: 'rabbit', label: '兔子' },
  { value: 'panda', label: '熊猫' },
  { value: 'fox', label: '狐狸' },
  { value: 'bear', label: '小熊' },
  { value: 'tiger', label: '老虎' },
  { value: 'lion', label: '狮子' },
  { value: 'monkey', label: '猴子' },
  { value: 'koala', label: '考拉' },
  { value: 'penguin', label: '企鹅' },
  { value: 'chick', label: '小鸡' },
  { value: 'owl', label: '猫头鹰' },
  { value: 'unicorn', label: '独角兽' }
]

const AVATAR_GRADIENTS: Record<string, string> = {
  blue: 'linear-gradient(135deg, #2d3b4f, #4a5f7a)',
  green: 'linear-gradient(135deg, #2f7d32, #8bbd23)',
  slate: 'linear-gradient(135deg, #263244, #64748b)',
  amber: 'linear-gradient(135deg, #9a5b00, #d99a20)'
}

const AVATAR_EMOJIS: Record<string, string> = {
  boy: '👦', girl: '👧', man: '👨', woman: '👩',
  cat: '🐱', dog: '🐶', rabbit: '🐰', panda: '🐼', fox: '🦊',
  bear: '🐻', tiger: '🐯', lion: '🦁', monkey: '🐵', koala: '🐨',
  penguin: '🐧', chick: '🐤', owl: '🦉', unicorn: '🦄'
}

/** 头像显示内容:emoji 头像显示 emoji,色块头像显示姓名首字 */
export function avatarContent(value?: string, initial?: string) {
  return AVATAR_EMOJIS[value || ''] || initial || ''
}

/** 头像底色:emoji 用浅底深字,色块用品牌渐变白字 */
export function avatarStyle(value?: string) {
  const v = value || 'blue'
  if (AVATAR_EMOJIS[v]) return { background: '#eef2f7', color: '#1f2937' }
  return { background: AVATAR_GRADIENTS[v] || AVATAR_GRADIENTS.blue, color: '#fff' }
}
