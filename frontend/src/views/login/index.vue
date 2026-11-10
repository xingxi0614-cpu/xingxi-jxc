<template>
  <div class="login-page">
    <div class="login-particles">
      <span v-for="i in 6" :key="i" class="login-particle"></span>
    </div>
    <div class="login-card">
      <div class="card-head">
        <div class="logo-plate">
          <span class="logo-aura" aria-hidden="true"></span>
          <img class="brand-logo" src="/logo-dark.svg" alt="星嘻进销存" />
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <div class="login-options">
          <el-checkbox v-model="rememberAccount">记住账号</el-checkbox>
        </div>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="onSubmit">
          登录
        </el-button>
      </el-form>
      <div class="lock-tip">连续输错密码会暂时锁定账号，也可联系管理员解锁。</div>
      <div class="usage-note">开源版本，按 MIT 许可证提供，不附带任何担保。</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox, FormInstance, FormRules } from 'element-plus'
import { Lock, User } from '@/icons'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const loading = ref(false)
const formRef = ref<FormInstance>()
const REMEMBER_KEY = 'jxc_scm_remembered_login'

const form = reactive({
  username: '',
  password: ''
})
const rememberAccount = ref(false)

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }]
}

function decodeRememberedLogin(value: string) {
  try {
    return JSON.parse(decodeURIComponent(atob(value))) as { username?: string; password?: string }
  } catch {
    try {
      return JSON.parse(value) as { username?: string; password?: string }
    } catch {
      return null
    }
  }
}

function clearRememberedLogin() {
  localStorage.removeItem(REMEMBER_KEY)
}

onMounted(() => {
  const saved = localStorage.getItem(REMEMBER_KEY)
  if (!saved) return
  const remembered = decodeRememberedLogin(saved)
  if (!remembered?.username) {
    clearRememberedLogin()
    return
  }
  form.username = remembered.username
  form.password = ''
  rememberAccount.value = true
  localStorage.setItem(REMEMBER_KEY, JSON.stringify({ username: remembered.username }))
})

watch(rememberAccount, (checked) => {
  if (!checked) clearRememberedLogin()
})

async function performLogin(force: boolean) {
  await user.login({ username: form.username, password: form.password, force })
  if (rememberAccount.value) {
    localStorage.setItem(REMEMBER_KEY, JSON.stringify({ username: form.username }))
  } else {
    clearRememberedLogin()
  }
  ElMessage.success('登录成功')
  // 默认落地页 = ERP 首页('/' 会按本人权限落到第一个能看的菜单,比写死 /work/home 稳)。
  // 只有 redirect 指向【具体某一页】时才回去(如会话过期后原地续)。
  const raw = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  const backTo = raw && raw !== '/' && raw.startsWith('/') && !raw.startsWith('/login') ? raw : '/'
  router.push(backTo)
}

async function onSubmit() {
  if (loading.value || !formRef.value) return
  if (!await formRef.value.validate().catch(() => false)) return
  loading.value = true
  try {
    await performLogin(false)
  } catch (e: any) {
    if (e?.code === 4090) {
      // 账号已有人在线：弹确认，确认则强制登录顶掉对方
      try {
        await ElMessageBox.confirm(
          e.message || '当前账号已有人登录，强制登录会将对方挤下线，是否继续？',
          '账号已在线',
          { type: 'warning', confirmButtonText: '强制登录', cancelButtonText: '取消' }
        )
      } catch {
        return  // 用户取消，放弃登录
      }
      try {
        await performLogin(true)
      } catch { /* 强制登录失败：错误由请求拦截器统一提示 */ }
    }
    // 其它错误由请求拦截器统一处理。
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  position: fixed;
  inset: 0;
  width: 100vw;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: linear-gradient(135deg, #dff2ee 0%, #f4f8f7 55%, #e8eef6 100%);
  overflow: hidden;

  &::before {
    content: "";
    position: absolute;
    inset: -50%;
    background:
      radial-gradient(ellipse at 30% 20%, rgba(200, 149, 74, .12), transparent 50%),
      radial-gradient(ellipse at 70% 60%, rgba(45, 59, 79, .10), transparent 50%),
      radial-gradient(ellipse at 50% 80%, rgba(93, 138, 78, .08), transparent 40%);
    animation: bg-drift 12s ease-in-out infinite alternate;
    pointer-events: none;
  }

  &::after {
    content: "";
    position: absolute;
    inset: 0;
    background: linear-gradient(180deg, rgba(11, 29, 69, .06), rgba(11, 29, 69, .10));
    pointer-events: none;
  }
}

/* 浮动粒子 */
.login-particles {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.login-particle {
  position: absolute;
  border-radius: 50%;
  background: rgba(47, 143, 208, .20);
  animation: float-up linear infinite;

  &:nth-child(1) { width: 8px; height: 8px; left: 15%; animation-duration: 12s; animation-delay: 0s; }
  &:nth-child(2) { width: 6px; height: 6px; left: 35%; animation-duration: 15s; animation-delay: -3s; }
  &:nth-child(3) { width: 10px; height: 10px; left: 55%; animation-duration: 14s; animation-delay: -6s; background: rgba(45, 59, 79, .20); }
  &:nth-child(4) { width: 7px; height: 7px; left: 72%; animation-duration: 17s; animation-delay: -1s; }
  &:nth-child(5) { width: 5px; height: 5px; left: 88%; animation-duration: 13s; animation-delay: -5s; background: rgba(93, 138, 78, .22); }
  &:nth-child(6) { width: 9px; height: 9px; left: 25%; animation-duration: 18s; animation-delay: -8s; }
}

@keyframes bg-drift {
  0% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(-3%, -1.5%) scale(1.05); }
  66% { transform: translate(2%, 1%) scale(1.03); }
  100% { transform: translate(0, 0) scale(1); }
}

@keyframes float-up {
  0% { bottom: -20px; opacity: 0; transform: translateX(0); }
  10% { opacity: 1; }
  90% { opacity: .6; }
  100% { bottom: 105%; opacity: 0; transform: translateX(40px); }
}

.login-card {
  position: relative;
  z-index: 1;
  width: min(392px, 100%);
  padding: 34px 34px 28px;
  border: 1px solid rgba(255, 255, 255, .50);
  border-radius: 22px;
  background:
    radial-gradient(circle at 50% 0%, rgba(255, 255, 255, .54), rgba(255, 255, 255, 0) 46%),
    linear-gradient(180deg, rgba(247, 251, 249, .50), rgba(236, 245, 241, .36)),
    linear-gradient(135deg, rgba(25, 52, 153, .055), rgba(183, 213, 11, .075));
  box-shadow:
    0 26px 68px rgba(18, 37, 70, .14),
    0 0 0 1px rgba(255, 255, 255, .28) inset,
    0 1px 0 rgba(255, 255, 255, .70) inset;
  backdrop-filter: blur(34px) saturate(1.06);
  animation: card-in .5s cubic-bezier(.34, 1.56, .64, 1);
  transition: transform .3s ease, box-shadow .3s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow:
      0 30px 72px rgba(18, 37, 70, .18),
      0 0 0 1px rgba(255, 255, 255, .32) inset,
      0 1px 0 rgba(255, 255, 255, .74) inset;
  }
}

/* 登录卡片是固定的浅色玻璃（配亮色乡村背景图），输入框统一用浅色友好样式，
   不跟随深/浅主题，避免深色模式下出现“浅字浅底”看不清的问题 */
.login-card :deep(.el-input__wrapper) {
  background-color: rgba(255, 255, 255, .88) !important;
  box-shadow: 0 0 0 1px rgba(150, 174, 194, .55) inset !important;
  border-radius: 12px;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  background-color: #ffffff !important;
  box-shadow: 0 0 0 1px #1d63b0 inset, 0 0 0 3px rgba(29, 99, 176, .14) !important;
}
.login-card :deep(.el-input__inner) {
  color: #1a1f29 !important;
  -webkit-text-fill-color: #1a1f29;
}
.login-card :deep(.el-input__inner::placeholder) {
  color: rgba(36, 55, 86, .50) !important;
  -webkit-text-fill-color: rgba(36, 55, 86, .50);
}
.login-card :deep(.el-input__prefix),
.login-card :deep(.el-input__suffix),
.login-card :deep(.el-input .el-icon) {
  color: rgba(36, 55, 86, .60) !important;
}

.login-card::before {
  content: "";
  position: absolute;
  inset: 10px;
  z-index: -1;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(183, 213, 11, .10), rgba(25, 52, 153, .08), transparent 66%);
  opacity: .5;
}

.card-head {
  display: flex;
  justify-content: center;
  margin-bottom: 24px;
}

.logo-plate {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 298px;
  height: 76px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
  overflow: visible;

  &::before {
    content: none;
  }

  &::after {
    content: none;
  }

  .brand-logo {
    position: relative;
    z-index: 1;
    width: 282px;
    height: auto;
    display: block;
    object-fit: contain;
    filter:
      saturate(1.08)
      contrast(1.05)
      drop-shadow(0 8px 18px rgba(17, 35, 70, .12));
  }
}

.logo-aura {
  position: absolute;
  inset: 8px 28px 10px;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(183, 213, 11, .08), rgba(25, 52, 153, .07));
  filter: blur(20px);
  opacity: .46;
  pointer-events: none;
}

.submit {
  width: 100%;
  height: 46px;
  font-weight: 650;
  border-radius: 14px;
  background: linear-gradient(180deg, #2479c9, #1d63b0);
  border-color: #1d63b0;
  box-shadow: 0 12px 22px rgba(29, 99, 176, .26);
  transition: transform .18s ease, box-shadow .18s ease, background .18s ease;
}

.submit:hover,
.submit:focus {
  background: linear-gradient(180deg, #3a8ad8, #2479c9);
  border-color: #1a5697;
  transform: translateY(-1px);
  box-shadow: 0 16px 28px rgba(25, 52, 153, .27);
}

.submit:active {
  transform: translateY(0);
  box-shadow: 0 8px 16px rgba(25, 52, 153, .20);
}

.lock-tip {
  margin-top: 18px;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.55;
  text-align: center;
}

.usage-note {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-2, #eef1f5);
  color: var(--text-3);
  font-size: 12px;
  text-align: center;
  letter-spacing: .2px;
}

:deep(.el-form-item) {
  margin-bottom: 16px;
}

.login-options {
  display: flex;
  justify-content: flex-start;
  margin: -2px 0 16px;
  color: rgba(36, 55, 86, .72);
  font-size: 13px;
}

.login-options :deep(.el-checkbox) {
  height: 22px;
  color: rgba(36, 55, 86, .72);
}

.login-options :deep(.el-checkbox__label) {
  padding-left: 8px;
  font-size: 13px;
  color: inherit;
}

.login-options :deep(.el-checkbox__inner) {
  width: 15px;
  height: 15px;
  border-radius: 5px;
  background: rgba(255, 255, 255, .36);
  border-color: rgba(150, 174, 194, .70);
  transition: border-color .18s ease, background-color .18s ease, box-shadow .18s ease;
}

.login-options :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background: #1f3fa8;
  border-color: #1f3fa8;
  box-shadow: 0 6px 14px rgba(25, 52, 153, .18);
}

:deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 13px;
  background: rgba(255, 255, 255, .28);
  box-shadow:
    0 0 0 1px rgba(181, 200, 214, .46) inset,
    0 8px 18px rgba(20, 38, 66, .035);
  transition: box-shadow .2s ease, background-color .2s ease, transform .2s ease;
}

:deep(.el-input__wrapper:hover) {
  background: rgba(255, 255, 255, .40);
  box-shadow: 0 0 0 1px rgba(150, 174, 194, .58) inset;
}

:deep(.el-input__wrapper.is-focus) {
  background: rgba(255, 255, 255, .56);
  transform: translateY(-1px);
  box-shadow:
    0 0 0 1px rgba(25, 52, 153, .76) inset,
    0 0 0 4px rgba(25, 52, 153, .07),
    0 10px 18px rgba(23, 32, 51, .05);
}

@keyframes card-in {
  0% { opacity: 0; transform: translateY(20px) scale(.94); }
  100% { opacity: 1; transform: translateY(0) scale(1); }
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 860px) {
  .login-page {
    padding: 24px;
    background-position: center bottom;
  }
}

@media (max-width: 460px) {
  .login-page {
    padding: 18px;
  }

  .login-card {
    padding: 34px 22px 28px;
    border-radius: 20px;
  }

  .logo-plate {
    width: 254px;
    height: 72px;
    padding-inline: 14px;
  }

  .logo-plate .brand-logo {
    width: 226px;
  }
}

/* 登录页保持独立视觉，不受系统内工作台风格回退影响 */
.login-page {
  :deep(.brand-logo) {
    background: transparent !important;
    border: 0 !important;
    box-shadow: none !important;
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
  }

  :deep(.el-input__wrapper) {
    min-height: 44px !important;
    border-radius: 13px !important;
    background: rgba(255, 255, 255, .28) !important;
    box-shadow:
      0 0 0 1px rgba(181, 200, 214, .46) inset,
      0 8px 18px rgba(20, 38, 66, .035) !important;
    backdrop-filter: none !important;
    -webkit-backdrop-filter: none !important;
  }

  :deep(.el-input__wrapper:hover) {
    background: rgba(255, 255, 255, .40) !important;
    box-shadow: 0 0 0 1px rgba(150, 174, 194, .58) inset !important;
  }

  :deep(.el-input__wrapper.is-focus) {
    background: rgba(255, 255, 255, .56) !important;
    box-shadow:
      0 0 0 1px rgba(25, 52, 153, .76) inset,
      0 0 0 4px rgba(25, 52, 153, .07),
      0 10px 18px rgba(23, 32, 51, .05) !important;
  }

  :deep(.el-button.submit.el-button--primary) {
    background: linear-gradient(180deg, #2479c9, #1d63b0) !important;
    border-color: #193499 !important;
    border-radius: 14px !important;
    box-shadow: 0 12px 22px rgba(25, 52, 153, .22) !important;
    color: #fff !important;
  }

  :deep(.el-button.submit.el-button--primary:hover),
  :deep(.el-button.submit.el-button--primary:focus) {
    background: linear-gradient(180deg, #3a8ad8, #2479c9) !important;
    border-color: #203fa5 !important;
    box-shadow: 0 16px 28px rgba(25, 52, 153, .27) !important;
  }
}
</style>
