<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">在线用户</div>
        <div class="page-sub">查看当前在线账号。同一个人在手机、电脑上各算一条，互不挤占；超过人数上限会优先下线最久未操作的非管理员账号。</div>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <KpiCards :items="kpis" />

    <el-card shadow="never">
      <el-table max-height="calc(100vh - 240px)" class="readable-table" border :fit="false" :data="rows" v-loading="loading" empty-text="暂无在线用户">
        <el-table-column prop="username" label="账号" width="120" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles || []" :key="r" size="small" class="role-tag">{{ roleLabel(r) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceLabel" label="终端" width="80" />
        <el-table-column prop="ip" label="登录 IP" width="150" />
        <el-table-column prop="loginAt" label="登录时间" width="170" :formatter="$fmtMinute" />
        <el-table-column prop="lastActiveAt" label="最近操作" width="170" :formatter="$fmtMinute" />
        <el-table-column label="空闲时间" width="110" align="right">
          <template #default="{ row }">{{ row.idleMinutes || 0 }} 分钟</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="isCurrent(row)" @click="kick(row)">下线</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@/icons'
import { sysApi } from '@/api'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const loading = ref(false)
const rows = ref<any[]>([])
const summary = reactive<any>({ online: 0, maxOnlineUsers: 0, idleTimeoutMinutes: 0, appIdleTimeoutMinutes: 0 })

const roleMap: Record<string, string> = {
  purchaser: '采购',
  warehouse: '仓管',
  finance: '材料会计',
  material_accountant: '材料会计',
  finance_manager: '财务部门负责人',
  account_admin: '账套管理员',
  admin: '管理员'
}

function roleLabel(code: string) {
  return roleMap[code] || code
}

function isCurrent(row: any) {
  return Number(row.userId) === Number(user.userInfo?.id)
}

async function load() {
  loading.value = true
  try {
    const data: any = await sysApi.onlineUsers()
    rows.value = data.records || []
    Object.assign(summary, data.summary || {})
  } finally {
    loading.value = false
  }
}

async function kick(row: any) {
  await ElMessageBox.confirm(`确认将 ${row.name || row.username} 下线？手机和电脑上的登录会一起退出。`, '下线确认', {
    type: 'warning',
    confirmButtonText: '下线',
    cancelButtonText: '取消'
  })
  await sysApi.kickOnlineUser(row.userId)
  ElMessage.success('已下线')
  await load()
}

onMounted(load)

// 顶部汇总卡（2026-10-17 界面改版，原型 S_Online）：同一人手机、电脑各算一条终端，人数按人头
const kpis = computed(() => {
  const people = new Set(rows.value.map((r: any) => r.userId)).size
  const phone = rows.value.filter((r: any) => String(r.deviceLabel || '').includes('手机')).length
  const idle = rows.value.filter((r: any) => Number(r.idleMinutes) >= 20).length
  return [
    { label: '在线人数', value: `${people} 人`, hint: `${rows.value.length} 个终端` },
    { label: '电脑 / 手机', value: `${rows.value.length - phone} / ${phone}`, hint: '同类终端一人只能登一个' },
    { label: '空闲超 20 分钟', value: `${idle} 个`, hint: `电脑 ${summary.idleTimeoutMinutes || 0} 分钟、手机 ${summary.appIdleTimeoutMinutes || 0} 分钟不操作自动退出`, tone: idle ? 'amber' as const : undefined },
    { label: '人数上限', value: `${summary.maxOnlineUsers || 0} 人`, hint: `当前在线 ${summary.online || 0}`, tone: Number(summary.online) >= Number(summary.maxOnlineUsers || Infinity) ? 'red' as const : undefined }
  ]
})
</script>

<style scoped>
.online-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.metric-label {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}

.metric-sub {
  color: var(--text-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.metric-value {
  font-size: 26px;
  line-height: 1.2;
  font-weight: 800;
  color: var(--text-primary);
}

.role-tag {
  margin-right: 6px;
}

@media (max-width: 900px) {
  .online-cards {
    grid-template-columns: 1fr;
  }
}
</style>
