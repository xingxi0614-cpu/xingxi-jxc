import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import { epIconMap } from './icons'

import App from './App.vue'
import router from './router'
import QueryDrawer from './components/QueryDrawer.vue'
import ApprovalFlowDrawer from './components/ApprovalFlowDrawer.vue'
import { initTheme } from './utils/uiPreferences'
import { fmtQty, fmtMinute } from './utils/format'
import './styles/index.scss'
import './styles/mobile.scss'
import './styles/mobile-ui.scss'

initTheme()

const app = createApp(App)

// 按原 Element Plus 图标名全局注册 Tabler 细线图标（兼容动态 <component :is="'Name'" /> 用法）
for (const [key, component] of Object.entries(epIconMap)) {
  app.component(key, component as any)
}
app.component('QueryDrawer', QueryDrawer)
app.component('ApprovalFlowDrawer', ApprovalFlowDrawer)

// 全局数量格式化：数量统一显示两位小数（录入精度不变）
// - 表格列用 :formatter="$qtyFmt"
// - 模板插值用 {{ $fmtQty(x) }}
app.config.globalProperties.$qtyFmt = (_row: any, _col: any, val: any) => fmtQty(val)
app.config.globalProperties.$fmtQty = fmtQty

// 全站时间统一为年月日时分（不含秒）
// - 表格列用 :formatter="$fmtMinute"
// - 模板插值用 {{ $fmtMin(x) }}
app.config.globalProperties.$fmtMinute = (_row: any, _col: any, val: any) => fmtMinute(val)
app.config.globalProperties.$fmtMin = fmtMinute

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn, size: 'default' })
app.mount('#app')
