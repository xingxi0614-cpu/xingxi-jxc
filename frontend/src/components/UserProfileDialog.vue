<template>
  <el-dialog
    v-model="visible"
    title="个人信息"
    width="min(680px, 92vw)"
    align-center
    append-to-body
    class="profile-dialog"
    @closed="resetForms"
  >
    <div class="profile-layout">
      <aside class="profile-side">
        <el-avatar :size="76" class="profile-avatar" :style="avatarStyle(profileForm.avatar)">
          {{ avatarContent(profileForm.avatar, profileInitial) }}
        </el-avatar>
        <div class="profile-name">{{ profileForm.name || user.userInfo?.name || '-' }}</div>
        <div class="profile-sub">{{ user.userInfo?.username || '-' }}</div>
        <div class="avatar-picker" aria-label="更换头像">
          <button
            v-for="item in AVATAR_OPTIONS"
            :key="item.value"
            type="button"
            class="avatar-option"
            :class="{ active: profileForm.avatar === item.value }"
            :style="avatarStyle(item.value)"
            :title="item.label"
            @click="profileForm.avatar = item.value"
          >
            {{ avatarContent(item.value, profileInitial) }}
          </button>
        </div>
      </aside>

      <el-tabs v-model="profileTab" class="profile-tabs">
        <el-tab-pane label="基本信息" name="basic">
          <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-width="82px">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="profileForm.name" maxlength="20" />
            </el-form-item>
            <el-form-item label="电话" prop="phone">
              <el-input v-model="profileForm.phone" maxlength="20" placeholder="手机号或办公电话" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="profileForm.email" maxlength="80" placeholder="工作邮箱，可选" />
            </el-form-item>
            <el-form-item label="公司">
              <el-input :model-value="user.userInfo?.companyName || user.userInfo?.orgName || '-'" disabled />
            </el-form-item>
            <el-form-item label="组织">
              <el-input :model-value="user.userInfo?.orgName || '-'" disabled />
            </el-form-item>
            <el-form-item label="角色">
              <el-input :model-value="roleNames || '-'" disabled />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="修改密码" name="password">
          <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="92px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="passwordForm.oldPassword" type="password" show-password autocomplete="current-password" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 6 位，字母+数字" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <!-- 账号与安全:让本人自查"这次登录是不是我"。只显示自己的记录,不是管理员日志页 -->
        <el-tab-pane label="账号与安全" name="security">
          <div v-if="secLoading" class="sec-hint">正在加载…</div>
          <template v-else-if="sec">
            <div class="sec-row"><span class="sec-k">登录账号</span><span class="sec-v">{{ sec.username || '—' }}</span></div>
            <div class="sec-row"><span class="sec-k">上次登录</span><span class="sec-v">{{ fmtTime(sec.lastLoginAt) }}</span></div>
            <div class="sec-row"><span class="sec-k">上次登录 IP</span><span class="sec-v">{{ sec.lastLoginIp || '—' }}</span></div>
            <div class="sec-tip">
              如果上面的时间或地点不是你本人操作，请立刻修改密码并联系管理员。
            </div>
            <div class="sec-sub">我最近的操作</div>
            <div v-if="!(sec.recent || []).length" class="sec-hint">暂无操作记录</div>
            <div v-for="(r, i) in (sec.recent || [])" :key="i" class="sec-log">
              <span class="sec-log-m">{{ r.module }} · {{ r.action }}</span>
              <span class="sec-log-t">{{ fmtTime(r.created_at) }}</span>
              <span class="sec-log-ip">{{ r.ip || '' }}</span>
            </div>
          </template>
          <div v-else class="sec-hint">安全信息加载失败，请稍后重试</div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="profileTab === 'basic'" type="primary" :loading="profileSaving" @click="saveProfile">保存资料</el-button>
      <el-button v-else-if="profileTab === 'password'" type="primary" :loading="passwordSaving" @click="savePassword">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 个人信息 / 修改密码对话框(顶栏头像菜单打开)。
 * 单独成组件:改密码规则只维护这一处,哪里需要都复用它。
 */
import { computed, reactive, ref, watch } from 'vue'
import { profileApi } from '@/api'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { AVATAR_OPTIONS, avatarContent, avatarStyle } from '@/utils/avatar'
import { roleLabel } from '@/utils/roleLabel'

const visible = defineModel<boolean>({ required: true })
/** 外部可指定打开哪个页签(用户下拉的 4 个入口分别落到不同页签) */
const props = defineProps<{ initialTab?: string }>()

/* ── 账号与安全 ── */
const sec = ref<any>(null)
const secLoading = ref(false)
async function loadSecurity() {
  if (sec.value || secLoading.value) return   // 只拉一次,反复切页签不重复请求
  secLoading.value = true
  try { sec.value = await profileApi.security() } catch { sec.value = null }
  finally { secLoading.value = false }
}
function fmtTime(v: any) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}
const user = useUserStore()

const profileTab = ref('basic')
const profileSaving = ref(false)
const passwordSaving = ref(false)
const profileFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()

const profileForm = reactive({ name: '', phone: '', email: '', avatar: 'blue' })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const profileRules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^[0-9+\-\s]{6,20}$|^$/, message: '电话格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}
const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: /^(?!123456$)(?!password$)(?!admin123$)(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d]{6,}$/i, message: '至少 6 位，必须包含字母和数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const roleNames = computed(() => (user.userInfo?.roles || []).map(roleLabel).join('、'))
const profileInitial = computed(() =>
  (profileForm.name || user.userInfo?.name || user.userInfo?.username || '用').charAt(0))

function fillProfileForm() {
  profileForm.name = user.userInfo?.name || ''
  profileForm.phone = user.userInfo?.phone || ''
  profileForm.email = user.userInfo?.email || ''
  profileForm.avatar = user.userInfo?.avatar || 'blue'
}
// 每次打开都用最新的用户信息回填(别人改过资料后再打开也能看到最新的)
watch(visible, v => { if (v) fillProfileForm() }, { immediate: true })

function resetForms() {
  profileTab.value = 'basic'
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  profileFormRef.value?.clearValidate()
  passwordFormRef.value?.clearValidate()
}

async function saveProfile() {
  await profileFormRef.value?.validate()
  profileSaving.value = true
  try {
    await user.updateProfile({
      name: profileForm.name,
      phone: profileForm.phone,
      email: profileForm.email,
      avatar: profileForm.avatar
    })
    ElMessage.success('个人信息已保存')
  } finally {
    profileSaving.value = false
  }
}

async function savePassword() {
  await passwordFormRef.value?.validate()
  passwordSaving.value = true
  try {
    await user.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordFormRef.value?.clearValidate()
    ElMessage.success('密码已修改')
  } finally {
    passwordSaving.value = false
  }
}

/** 打开弹窗时按外部指定跳到对应页签;切到安全页签时按需加载数据 */
watch(visible, v => {
  if (v && props.initialTab) profileTab.value = props.initialTab
  if (v && profileTab.value === 'security') loadSecurity()
})
watch(profileTab, t => { if (t === 'security') loadSecurity() })
</script>

<style scoped lang="scss">
.profile-layout {
  display: flex;
  gap: 22px;
}
.profile-side {
  flex: none;
  width: 190px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding-right: 18px;
  border-right: 1px solid var(--border-2, #eef1f5);
}
.profile-avatar { font-size: 30px; }
.profile-name { margin-top: 8px; font-size: 15px; font-weight: 650; color: var(--text-1); }
.profile-sub { font-size: 12px; color: var(--text-3); }
.avatar-picker {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 6px;
  margin-top: 12px;
}
.avatar-option {
  width: 30px; height: 30px;
  display: flex; align-items: center; justify-content: center;
  border: 2px solid transparent;
  border-radius: 50%;
  font-size: 15px;
  cursor: pointer;
  transition: border-color .16s, transform .16s;
}
.avatar-option:hover { transform: scale(1.08); }
.avatar-option.active { border-color: var(--color-primary); }
.profile-tabs { flex: 1; min-width: 0; }

@media (max-width: 640px) {
  .profile-layout { flex-direction: column; }
  .profile-side { width: 100%; padding-right: 0; padding-bottom: 16px; border-right: 0; border-bottom: 1px solid var(--border-2, #eef1f5); }
  .avatar-picker { grid-template-columns: repeat(8, 1fr); }
}

/* 账号与安全 */
.sec-row { display: flex; gap: 10px; padding: 7px 0; font-size: 13px; }
.sec-k { flex: none; width: 92px; color: var(--text-3, #98a1b0); }
.sec-v { color: var(--text-1, #1a1f29); font-variant-numeric: tabular-nums; }
.sec-tip {
  margin: 10px 0 4px; padding: 9px 11px;
  border-radius: 8px; background: #fff8ec;
  font-size: 12.5px; color: #8a6414; line-height: 1.6;
}
.sec-sub { margin: 14px 0 6px; font-size: 13px; font-weight: 650; color: var(--text-1, #1a1f29); }
.sec-hint { padding: 10px 0; font-size: 12.5px; color: var(--text-3, #98a1b0); }
.sec-log {
  display: flex; gap: 10px; align-items: baseline;
  padding: 6px 0; border-top: 1px solid var(--border-2, #eef1f5);
  font-size: 12.5px;
}
.sec-log-m { flex: 1; min-width: 0; color: var(--text-1, #1a1f29); }
.sec-log-t { flex: none; color: var(--text-3, #98a1b0); font-variant-numeric: tabular-nums; }
.sec-log-ip { flex: none; width: 110px; text-align: right; color: var(--text-3, #98a1b0); }
</style>

