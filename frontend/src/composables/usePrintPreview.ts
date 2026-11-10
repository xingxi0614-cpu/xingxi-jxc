import { reactive } from 'vue'

/** 全局打印预览状态：printDocument 写入，PrintPreviewDialog 渲染。 */
export const printPreviewState = reactive<{
  visible: boolean
  html: string
  title: string
  paper: string
}>({
  visible: false,
  html: '',
  title: '打印预览',
  paper: 'A4'
})

export function openPrintPreview(html: string, title = '打印预览', paper = 'A4') {
  printPreviewState.html = html
  printPreviewState.title = title || '打印预览'
  printPreviewState.paper = paper || 'A4'
  printPreviewState.visible = true
}
