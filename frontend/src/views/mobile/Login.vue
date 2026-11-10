<template>
  <div class="mlogin">
    <div class="mlogin-mask"></div>
    <div class="mlogin-card">
      <img src="/logo-dark.svg" alt="星嘻进销存" class="mlogin-logo" />

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="login" class="mlogin-form">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password @keyup.enter="login" />
        </el-form-item>
        <el-button type="primary" size="large" class="mlogin-btn" :loading="loading" @click="login">登 录</el-button>
      </el-form>

      <p class="mlogin-tip">连续输错密码会暂时锁定账号</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const loading = ref(false)
const formRef = ref()
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }]
}

async function performLogin(force: boolean) {
  await user.login({ username: form.username, password: form.password, force })
  ElMessage.success('登录成功')
  const redirect = (route.query.redirect as string) || '/mobile'
  router.replace(redirect.startsWith('/') ? redirect : '/mobile')
}

async function login() {
  if (loading.value || !formRef.value) return
  if (!await formRef.value.validate().catch(() => false)) return
  loading.value = true
  try {
    await performLogin(false)
  } catch (e: any) {
    if (e?.code === 4090) {
      // 账号已在别处登录：弹确认，确认则强制登录顶掉对方（与电脑端一致）
      try {
        await ElMessageBox.confirm(
          e.message || '当前账号已有人登录，强制登录会将对方挤下线，是否继续？',
          '账号已在线',
          { type: 'warning', confirmButtonText: '强制登录', cancelButtonText: '取消' }
        )
      } catch {
        return  // 用户取消
      }
      try { await performLogin(true) } catch { /* 二次失败由拦截器提示 */ }
    }
    // 其它错误（密码错误/网络等）已由响应拦截器统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.mlogin {
  height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px;
  background: linear-gradient(160deg, #dff2ee 0%, #f4f8f7 60%, #e8eef6 100%);
  position: relative;
}
.mlogin-mask { position: absolute; inset: 0; background: none; }
.mlogin-card {
  width: 100%; max-width: 360px; text-align: center; padding: 30px 24px 26px;
  background: rgba(255, 255, 255, .92); backdrop-filter: blur(14px);
  border-radius: 22px; border: 1px solid rgba(255, 255, 255, .6);
  box-shadow: 0 20px 60px rgba(16, 20, 48, .30);
  animation: mlogin-in .5s cubic-bezier(.34,1.56,.64,1);
  position: relative;
}
@keyframes mlogin-in { 0% { opacity: 0; transform: translateY(24px) scale(.95); } 100% { opacity: 1; transform: translateY(0) scale(1); } }
/* logo 呼吸：极轻量 4s 微缩放，让登录页有一点生气 */
.mlogin-logo { width: 78%; max-width: 250px; margin: 2px auto 20px; display: block; animation: mlogin-breath 4s ease-in-out infinite; }
@keyframes mlogin-breath { 0%, 100% { transform: scale(1); opacity: 1; } 50% { transform: scale(1.025); opacity: .94; } }
.mlogin-form { text-align: left; }
.mlogin-btn {
  width: 100%; height: 44px; margin-top: 4px; font-size: 15px;
  /* 手机端主按钮统一深蓝品牌色（覆盖桌面端 el-button--primary 变量） */
  --el-button-bg-color: var(--mob-brand);
  --el-button-border-color: var(--mob-brand);
  --el-button-hover-bg-color: var(--mob-brand-press);
  --el-button-hover-border-color: var(--mob-brand-press);
  --el-button-active-bg-color: var(--mob-brand-press);
  --el-button-active-border-color: var(--mob-brand-press);
}
.mlogin-tip { font-size: 11px; color: #9b9288; margin-top: 16px; }
</style>
