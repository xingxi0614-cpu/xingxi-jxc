<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">系统更新</div>
        <div class="page-sub">查看系统每次发布后的功能变化、流程调整和重要修复。</div>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" class="release-panel">
      <div v-loading="loading" class="release-list">
        <el-empty v-if="!rows.length && !loading" description="暂无更新说明" />
        <article v-for="item in rows" :key="item.id" class="release-item">
          <div class="release-head">
            <div>
              <div class="release-title">{{ item.title }}</div>
              <div class="release-summary">{{ item.summary }}</div>
            </div>
            <div class="release-meta">
              <el-tag effect="plain">v{{ item.versionNo }}</el-tag>
              <span>{{ formatTime(item.publishedAt) }}</span>
              <el-button v-if="canSeeUnread" link type="primary" @click="openUnread(item)">必看未读名单</el-button>
            </div>
          </div>
          <ul class="release-content">
            <li v-for="line in contentLines(item)" :key="line">{{ line }}</li>
          </ul>
        </article>
      </div>

      <div v-if="total > size" class="pager">
        <el-pagination
          v-model:current-page="current"
          v-model:page-size="size"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </el-card>

    <!-- 谁还没看完这一版的「必看」条目（规则变化）。只列你能看到的账套里启用中的账号 -->
    <el-dialog v-model="unread.visible" :title="`v${unread.version} 必看未读名单`" width="min(560px, calc(100vw - 40px))" align-center append-to-body>
      <div v-loading="unread.loading">
        <el-empty v-if="!unread.loading && unread.mustCount === 0" description="这一版没有必看条目" />
        <template v-else-if="!unread.loading">
          <div class="unread-sum">共 {{ unread.totalUsers }} 个账号，还有 <b>{{ unread.users.length }}</b> 个没看完必看条目（共 {{ unread.mustCount }} 条）。</div>
          <el-table :data="unread.users" max-height="420" border size="small" empty-text="都看过了">
            <el-table-column prop="orgName" label="所属组织" min-width="140" show-overflow-tooltip />
            <el-table-column prop="name" label="姓名" width="100" />
            <el-table-column prop="username" label="账号" width="120" />
            <el-table-column label="已看" width="80"><template #default="{ row }">{{ row.readCount }}/{{ unread.mustCount }}</template></el-table-column>
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { Refresh } from '@/icons'
import { sysApi } from '@/api'
import { useUserStore } from '@/stores/user'

const loading = ref(false)
const rows = ref<any[]>([])
const current = ref(1)
const size = ref(10)
const total = ref(0)

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : ''
}

function contentLines(item: any) {
  return String(item?.content || '')
    .split(/\r?\n/)
    .map(line => line.trim().replace(/^[-*]\s*/, ''))
    .filter(Boolean)
}

const user = useUserStore()
// 后端同样校验（管理员/账套管理员/财务），这里只是不给别人摆一个点了也打不开的按钮
const canSeeUnread = user.hasFinanceAccessOrAdmin() || user.hasRole('account_admin')
const unread = reactive({ visible: false, loading: false, version: '', mustCount: 0, totalUsers: 0, users: [] as any[] })
async function openUnread(item: any) {
  Object.assign(unread, { visible: true, loading: true, version: item.versionNo, mustCount: 0, totalUsers: 0, users: [] })
  try {
    const r: any = await sysApi.releaseGuideUnread(item.versionNo)
    Object.assign(unread, { mustCount: r?.mustCount || 0, totalUsers: r?.totalUsers || 0, users: r?.users || [] })
  } catch {
    unread.visible = false
  } finally { unread.loading = false }
}

async function load() {
  loading.value = true
  try {
    const page: any = await sysApi.releaseNotes({ current: current.value, size: size.value })
    rows.value = page?.records || []
    total.value = Number(page?.total || 0)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.unread-sum { margin-bottom: 10px; color: var(--text-2); }
.unread-sum b { color: var(--el-color-danger); }

.release-panel {
  min-height: 320px;
}

.release-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.release-item {
  padding: 18px 4px;
  border-bottom: 1px solid var(--border-2);
}

.release-item:first-child {
  padding-top: 2px;
}

.release-item:last-child {
  border-bottom: 0;
}

.release-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.release-title {
  font-size: 16px;
  font-weight: 750;
  color: var(--text-1);
  line-height: 1.4;
}

.release-summary {
  margin-top: 4px;
  color: var(--text-2);
  line-height: 1.7;
}

.release-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
  color: var(--text-3);
  font-size: 12px;
}

.release-content {
  margin: 12px 0 0;
  padding-left: 22px;
  color: var(--text-1);
  line-height: 1.85;
}

.release-content li + li {
  margin-top: 4px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
  border-top: 1px solid var(--border-2);
}

@media (max-width: 720px) {
  .release-head {
    flex-direction: column;
  }

  .release-meta {
    white-space: normal;
  }
}
</style>
