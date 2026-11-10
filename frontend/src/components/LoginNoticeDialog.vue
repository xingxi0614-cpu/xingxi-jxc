<template>
  <NoticeDialog v-model="visible" v-model:index="idx" :items="items" force-read />
</template>

<script setup lang="ts">
/**
 * 登录后自动弹出当前有效的系统通知(2026-10-22 用户要求:网站下线通知要让每个人每次登录都看见)。
 *
 * 为什么用「登录时做记号」而不是「本机看过就不弹」:用户要的是每次登录都看见,不是看一次就算。
 * 记号由 stores/user.ts 的 login() 写在本标签页的 sessionStorage 里,这里读到就弹,点「知道了」关掉才删——
 * 所以看过以后刷新页面不会反复弹,退出再登录、空闲超时被踢再登录都会再弹。
 * 为什么关掉才删、不是弹出就删:刚登录那一下页面可能整页刷新(发现新版本自动刷新,10-22 本地实测开发服务器也会),
 * 弹出就删的话刷新后就再也看不到了。电脑端和手机端走同一个 login(),本组件挂在 App.vue 全局,两端都有。
 * 顶栏滚动条的「×今天不再显示」只管滚动条,不影响这里。没有通知就什么都不弹。
 */
import { computed, ref, watch } from 'vue'
import { tickerApi } from '@/api'
import { LOGIN_NOTICE_KEY, useUserStore } from '@/stores/user'
import NoticeDialog, { type Ticker } from './NoticeDialog.vue'

const user = useUserStore()
const visible = ref(false)
const idx = ref(0)
const items = ref<Ticker[]>([])
const accountKey = computed(() => user.sessionActive && user.userInfo?.id != null ? String(user.userInfo.id) : null)

function hasMark(): boolean {
  try { return sessionStorage.getItem(LOGIN_NOTICE_KEY) === '1' } catch { return false }   // 浏览器禁了存储:不弹,不影响登录
}
function clearMark() {
  try { sessionStorage.removeItem(LOGIN_NOTICE_KEY) } catch { /* 同上 */ }
}

// 用户点「知道了」/右上角关掉 = 看过了,这次登录不再弹
watch(visible, (now, before) => { if (before && !now && accountKey.value) clearMark() })

watch(accountKey, async (acc) => {
  if (!acc) { visible.value = false; return }
  if (!hasMark()) return
  try {
    const list = ((await tickerApi.live()) as any as Ticker[]) || []
    if (accountKey.value !== acc) return   // 等结果期间换了人/退出了
    if (!list.length) { clearMark(); return }
    items.value = list
    idx.value = 0
    visible.value = true
  } catch { /* 读不到通知不影响用系统,静默;记号留着,刷新时再试 */ }
}, { immediate: true })
</script>
