<template>
  <div class="hd">
    <!-- 顶栏滚动通知:占左边空白,首页自己在问候语右边放一条(那里 showTicker=false) -->
    <NoticeTicker v-if="showTicker" />

    <div class="r">
      <el-select
        v-model="searchValue"
        class="global-search"
        filterable
        clearable
        placeholder="搜索模块…"
        :reserve-keyword="false"
        @change="onSearchSelect"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <el-option v-for="m in menuOptions" :key="m.path" :value="m.path" :label="m.name">
          <span>{{ m.name }}</span>
          <span class="gs-group">{{ m.group }}</span>
        </el-option>
      </el-select>
      <el-badge :value="notif.unread" :hidden="notif.unread === 0" :max="99">
        <el-tooltip content="待办事项" placement="bottom">
          <button type="button" class="tool-btn" aria-label="待办事项" title="待办事项" @click="$router.push('/work/todo')">
            <el-icon><Bell /></el-icon>
          </button>
        </el-tooltip>
      </el-badge>
      <el-tooltip :content="`当前在线 ${online.online} 人，最多 ${online.maxOnlineUsers || '-'} 人`" placement="bottom">
        <div class="online-pill" aria-label="实时在线人数" title="实时在线人数">
          <span class="online-dot"></span>
          <span class="online-number">{{ online.online }}</span>
          <span class="online-label">在线</span>
        </div>
      </el-tooltip>
      <el-tooltip :content="isDark ? '切换浅色模式' : '切换深色模式'" placement="bottom">
        <button
          type="button"
          class="tool-btn theme-btn"
          :aria-label="isDark ? '切换浅色模式' : '切换深色模式'"
          :title="isDark ? '切换浅色模式' : '切换深色模式'"
          @click="toggleTheme"
        >
          <el-icon><MoonNight v-if="!isDark" /><Sunny v-else /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip :content="isFullscreen ? '退出全屏' : '全屏显示'" placement="bottom">
        <button
          type="button"
          class="tool-btn"
          :aria-label="isFullscreen ? '退出全屏' : '全屏显示'"
          :title="isFullscreen ? '退出全屏' : '全屏显示'"
          @click="toggleFullscreenView"
        >
          <el-icon><Aim v-if="isFullscreen" /><FullScreen v-else /></el-icon>
        </button>
      </el-tooltip>
      <span class="version-pill" title="当前系统版本">v{{ appVersion }}</span>
      <!-- 能看多家公司的账号(系统管理员)：公司标签可点，选一家后全站只看那家；其他账号仍是只读标签 -->
      <el-dropdown v-if="viewCompany.switchable" trigger="click" @command="onViewCompany">
        <button type="button" class="org-pill is-switch" :class="{ 'is-narrowed': !!viewCompany.selectedId }"
          :title="viewCompany.selectedId ? `当前只看：${viewCompany.selectedName}（点击切换）` : '当前查看全部公司（点击选择某一家）'">
          <span class="org-pill-dot"></span>
          {{ viewCompany.selectedName || '全部公司' }}
          <el-icon class="org-pill-caret"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu class="view-company-menu">
            <el-dropdown-item command="" :class="{ 'is-current': !viewCompany.selectedId }">全部公司</el-dropdown-item>
            <el-dropdown-item v-for="c in viewCompany.companies" :key="c.id" :command="c.id" :divided="c.id === viewCompany.companies[0]?.id"
              :class="{ 'is-current': String(c.id) === viewCompany.selectedId }">{{ c.name }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span v-else class="org-pill" :title="`当前公司：${user.userInfo?.companyName || user.userInfo?.orgName || '-'}`">
        <span class="org-pill-dot"></span>
        {{ user.userInfo?.companyName || user.userInfo?.orgName || '-' }}
      </span>
      <div class="user-menu-wrap" @click.stop>
        <button
          ref="usrBtn"
          type="button"
          class="usr"
          aria-label="用户菜单"
          aria-haspopup="menu"
          :aria-expanded="userMenuVisible"
          title="用户菜单"
          @click="toggleUserMenu"
        >
          <el-avatar :size="30" class="avatar" :style="avatarStyle(user.userInfo?.avatar)">
            {{ avatarContent(user.userInfo?.avatar, avatarText) }}
          </el-avatar>
          <div class="m">
            <div class="name">{{ user.userInfo?.name }}</div>
            <div class="role">{{ roleLabel(user.userInfo?.roles?.[0]) }}</div>
          </div>
          <el-icon class="arrow"><ArrowDown /></el-icon>
        </button>
        <Teleport to="body">
          <div v-if="userMenuVisible" class="user-menu" role="menu" aria-label="用户菜单" :style="menuPos">
            <div class="user-menu-summary">
              <el-avatar :size="34" class="avatar" :style="avatarStyle(user.userInfo?.avatar)">
                {{ avatarContent(user.userInfo?.avatar, avatarText) }}
              </el-avatar>
              <div class="menu-name">{{ user.userInfo?.name || user.userInfo?.username }}</div>
            </div>
            <button type="button" class="user-menu-item" role="menuitem" @click="onCmd('profile')">
              <el-icon><User /></el-icon>
              <span>个人信息</span>
            </button>
            <button type="button" class="user-menu-item danger" role="menuitem" @click="onCmd('logout')">
              <el-icon><SwitchButton /></el-icon>
              <span>退出登录</span>
            </button>
          </div>
        </Teleport>
      </div>
    </div>

    <UserProfileDialog v-model="profileVisible" />
  </div>
</template>

<script setup lang="ts">
import UserProfileDialog from '@/components/UserProfileDialog.vue'
import NoticeTicker from '@/components/NoticeTicker.vue'
import { avatarStyle, avatarContent } from '@/utils/avatar'
import { roleLabel } from '@/utils/roleLabel'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useNotifStore } from '@/stores/notif'
import { useViewCompanyStore } from '@/stores/viewCompany'
import { Aim, ArrowDown, Bell, FullScreen, MoonNight, Sunny, SwitchButton, User, Search } from '@/icons'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUiPreferences } from '@/utils/uiPreferences'
import { sysApi } from '@/api'

withDefaults(defineProps<{ showTicker?: boolean }>(), { showTicker: true })
const router = useRouter()
const route = useRoute()
const user = useUserStore()
const viewCompany = useViewCompanyStore()
function onViewCompany(id: number | '') { viewCompany.select(id) }
const notif = useNotifStore()
const { isFullscreen, toggleFullscreen, isDark, toggleTheme } = useUiPreferences()
const appVersion = __APP_VERSION__

// 全局搜索：展平菜单树，按名称快速跳转模块
const searchValue = ref('')
function flattenMenus(items: any[], group = '', out: any[] = []): any[] {
  for (const m of items || []) {
    if (m.children && m.children.length) flattenMenus(m.children, m.name || group, out)
    else if (m.path) out.push({ path: m.path, name: m.name, group })
  }
  return out
}
const menuOptions = computed(() => flattenMenus(user.menus as any))
function onSearchSelect(path: string) {
  if (path) router.push(path)
  searchValue.value = ''
}

const profileVisible = ref(false)
const usrBtn = ref<HTMLElement>()
const userMenuVisible = ref(false)
const menuPos = ref<Record<string, string>>({})
let notifTimer: number | undefined
let onlineTimer: number | undefined

const online = reactive({
  online: 0,
  maxOnlineUsers: 0,
  idleTimeoutMinutes: 0
})

const avatarText = computed(() => (user.userInfo?.name || user.userInfo?.username || '用').charAt(0))
onMounted(() => {
  viewCompany.load()
  notif.refreshCount()
  refreshOnlineCount()
  notifTimer = window.setInterval(() => notif.refreshCount(), 30000)
  onlineTimer = window.setInterval(refreshOnlineCount, 15000)
  document.addEventListener('click', closeUserMenu)
})

onUnmounted(() => {
  if (notifTimer) window.clearInterval(notifTimer)
  if (onlineTimer) window.clearInterval(onlineTimer)
  document.removeEventListener('click', closeUserMenu)
})

watch(() => route.fullPath, () => {
  closeUserMenu()
})

function closeUserMenu() {
  userMenuVisible.value = false
}

function toggleUserMenu() {
  userMenuVisible.value = !userMenuVisible.value
  if (userMenuVisible.value && usrBtn.value) {
    const rect = usrBtn.value.getBoundingClientRect()
    menuPos.value = {
      position: 'fixed',
      top: (rect.bottom + 8) + 'px',
      right: (window.innerWidth - rect.right) + 'px',
    }
  }
}

async function onCmd(cmd: string) {
  userMenuVisible.value = false
  if (cmd === 'profile') {
    profileVisible.value = true   // 表单回填由 UserProfileDialog 自己在打开时做
    return
  }
  if (cmd === 'logout') {
    const ok = await ElMessageBox.confirm('确定退出当前账号？', '提示', { type: 'warning' }).catch(() => null)
    if (!ok) return
    await user.logout()
    router.replace('/login')
  }
}

async function toggleFullscreenView() {
  try {
    await toggleFullscreen()
  } catch (_err) {
    ElMessage.warning('当前浏览器不允许进入全屏，可使用 F11 作为替代')
  }
}

async function refreshOnlineCount() {
  try {
    const data: any = await sysApi.onlineSummary()
    online.online = Number(data?.online || 0)
    online.maxOnlineUsers = Number(data?.maxOnlineUsers || 0)
    online.idleTimeoutMinutes = Number(data?.idleTimeoutMinutes || 0)
  } catch (_err) {
    // Keep the toolbar quiet; auth errors are handled by the global interceptor.
  }
}

</script>

<style scoped lang="scss">

.hd {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  width: 100%;
  gap: 14px;
  min-width: 0;
}

.r {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px;
  border: 1px solid var(--glass-border, var(--border-1));
  border-radius: 16px;
  background:
    linear-gradient(135deg, var(--surface-soft), var(--surface-soft)),
    var(--toolbar-bg, #ffffff);
  box-shadow: var(--glass-shadow-soft);
}

.tool-btn {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  transition: background-color .16s ease, color .16s ease, box-shadow .16s ease, transform .16s ease;

  &:hover {
    background: linear-gradient(135deg, var(--surface-soft), rgba(29, 99, 176, .10));
    color: var(--color-primary);
    box-shadow: inset 0 0 0 1px rgba(29, 99, 176, .12);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0) scale(.96);
    box-shadow: var(--glass-shadow-press);
  }
}

.global-search {
  width: 190px;
  margin-right: 8px;
}
.global-search :deep(.el-select__wrapper) {
  border-radius: 10px;
  min-height: 32px;
}
.gs-group {
  float: right;
  color: var(--text-3, #9ca3af);
  font-size: 12px;
  margin-left: 14px;
}

.version-pill {
  padding: 0 7px;
  height: 24px;
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--text-3);
  box-shadow: inset 0 0 0 1px var(--border-1);
  font-size: 11px;
  line-height: 24px;
  white-space: nowrap;
}

.org-pill {
  padding: 0 8px;
  height: 24px;
  border-radius: 999px;
  background: rgba(29, 99, 176, .10);
  color: var(--text-1);
  box-shadow: inset 0 0 0 1px rgba(29, 99, 176, .22);
  font-size: 11px;
  font-weight: 550;
  line-height: 24px;
  white-space: nowrap;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  user-select: none;
}

.org-pill.is-switch {
  border: 0;
  cursor: pointer;
  font-family: inherit;
}

.org-pill.is-switch:hover {
  background: rgba(29, 99, 176, .16);
}

/* 只看某一家时换成醒目的橙色：提醒"现在看到的不是全部"，免得把一家的数当成集团的数 */
.org-pill.is-narrowed {
  background: rgba(217, 119, 6, .14);
  box-shadow: inset 0 0 0 1px rgba(217, 119, 6, .45);
}

.org-pill.is-narrowed .org-pill-dot {
  background: #d97706;
}

.org-pill-caret {
  font-size: 11px;
  opacity: .7;
}

.org-pill-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-accent);
  flex: 0 0 auto;
}

.online-pill {
  height: 28px;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--surface-soft);
  color: var(--text-2);
  box-shadow: inset 0 0 0 1px var(--border-1);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 28px;
  white-space: nowrap;
  user-select: none;
}

.online-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, .16);
}

.online-number {
  color: var(--text-1);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.online-label {
  color: var(--text-3);
}

.usr {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  min-height: 36px;
  padding: 3px 8px 3px 4px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  transition: background-color .16s ease, transform .16s ease, box-shadow .16s ease;

  &:hover {
    background: linear-gradient(135deg, var(--surface-soft), rgba(29, 99, 176, .10));
    box-shadow: inset 0 0 0 1px rgba(29, 99, 176, .12);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0) scale(.98);
  }
}

.user-menu-wrap {
  position: relative;
}

.user-menu {
  width: 210px;
  padding: 8px;
  border: 1px solid var(--border-2);
  border-radius: 16px;
  background: var(--bg-panel);
  box-shadow:
    0 0 0 1px rgba(44, 36, 22, .04),
    0 16px 40px rgba(44, 36, 22, .16),
    0 4px 12px rgba(44, 36, 22, .08);
  z-index: 9999;
  animation: menu-in .18s ease;
}

@keyframes menu-in {
  from {
    opacity: 0;
    transform: translateY(-6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.user-menu-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 10px;
  margin-bottom: 6px;
  border-bottom: 1px solid var(--border-2);
}

.menu-name {
  max-width: 132px;
  color: var(--text-1);
  font-size: 13px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-sub {
  margin-top: 3px;
  color: var(--text-3);
  font-size: 12px;
}

.menu-version {
  margin-top: 4px;
  color: var(--text-3);
  font-size: 11px;
}

.user-menu-item {
  width: 100%;
  height: 36px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-2);
  text-align: left;
  padding: 0 12px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;

  &:hover {
    background: linear-gradient(135deg, var(--surface-soft), rgba(29, 99, 176, .10));
    color: var(--color-primary);
  }

  &.danger {
    color: #b42318;
  }
}

@media (max-width: 900px) {
  .user-menu {
    width: 224px;
    padding: 8px;
  }
}

.avatar {
  font-weight: 600;
}

.m {
  min-width: 0;
  font-size: 13px;
  line-height: 1.18;
}

.name {
  color: var(--text-1);
  font-weight: 600;
}

.role {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 3px;
}

.arrow {
  color: var(--text-3);
  font-size: 13px;
}

@media (max-width: 720px) {
  .m {
    display: none;
  }

  .online-label,
  .version-pill,
  .org-pill {
    display: none;
  }

  /* 可切换的公司标签窄屏也要留着：否则看不出当前是不是只在看某一家 */
  .org-pill.is-switch {
    display: inline-flex;
  }

}

:global(html[data-theme='dark']) {
  .r,
  .user-menu {
    background:
      radial-gradient(circle at 0 0, rgba(255, 255, 255, .10), transparent 38%),
      linear-gradient(145deg, var(--glass-bg-strong), var(--glass-bg));
    border-color: var(--glass-border);
  }

  .tool-btn:hover,
  .usr:hover,
  .user-menu-item:hover {
    background: linear-gradient(135deg, rgba(255, 255, 255, .08), rgba(29, 99, 176, .14));
  }

  .version-pill,
  .online-pill {
    background: rgba(29, 99, 176, .12);
    box-shadow: inset 0 0 0 1px rgba(29, 99, 176, .14);
  }
}

:global(html[data-theme='dark']) .r {
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .08), rgba(29, 99, 176, .07)),
    rgba(18, 28, 42, .70);
  box-shadow: 0 16px 34px rgba(0, 0, 0, .22), inset 0 1px 0 rgba(255, 255, 255, .10);
}

:global(html[data-theme='dark']) .tool-btn:hover,
:global(html[data-theme='dark']) .usr:hover,
:global(html[data-theme='dark']) .user-menu-item:hover {
  background: linear-gradient(135deg, rgba(255, 255, 255, .08), rgba(29, 99, 176, .12));
}

:global(html[data-theme='dark']) .version-pill,
:global(html[data-theme='dark']) .online-pill {
  background: rgba(29, 99, 176, .12);
  color: var(--text-2);
}
</style>
