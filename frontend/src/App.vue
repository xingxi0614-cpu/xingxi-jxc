<template>
  <RouterView v-slot="{ Component }">
    <!-- 页面切换过渡只在手机端生效（桌面端布局内部有自己的过渡） -->
    <transition :name="isMobileRoute ? 'mob-fade' : ''" mode="out-in">
      <component :is="Component" />
    </transition>
  </RouterView>
  <PrintPreviewDialog />
  <ReleaseNoteDialog />
  <LoginNoticeDialog />
</template>

<script setup lang="ts">
// 入口组件：展示路由出口 + 换版自动检测 + 全局打印预览 + 登录后系统通知 + 全局空闲自动退出
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAppUpdate } from '@/composables/useAppUpdate'
import { useIdleLogout } from '@/composables/useIdleLogout'
import PrintPreviewDialog from '@/components/PrintPreviewDialog.vue'
import ReleaseNoteDialog from '@/components/ReleaseNoteDialog.vue'
import LoginNoticeDialog from '@/components/LoginNoticeDialog.vue'

const route = useRoute()
const isMobileRoute = computed(() => route.path.startsWith('/mobile'))

useAppUpdate()
// ⚠️ 空闲自动退出必须挂在这里，不能挂在 ERP 布局里。
// 教训：手机端 /mobile 这类顶级路由不走 ERP 布局，挂在布局里会整页漏掉这道防线。
// 挂全局 = 任何已登录页面都受保护，以后再加新的顶级页面也不会漏。
useIdleLogout()
</script>
