<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">数据备份</div>
        <div class="page-sub">把数据下载到本地留存。</div>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="bak-tip">
      <template #title>请定期备份</template>
      系统不会自动把数据存到别处。建议定期（至少每月一次）用下面的功能把数据下载到本地另存一份；服务器磁盘出问题时，这份就是你唯一能恢复的数据。
    </el-alert>

    <!-- 上面两张卡等高并排；演示复位是另一类操作，单独一整行（2026-10-17 界面改版，原型 S_Backup） -->
    <el-row :gutter="12" class="bak-row">
      <!-- 只有管理员能看到右边的整库备份；别人只有这一张，就占满整行，不留半边空白 -->
      <el-col :xs="24" :md="user.hasRole('admin') ? 12 : 24">
        <el-card shadow="never" class="bak-card">
          <div class="bak-title"><el-icon><component :is="'Document'" /></el-icon> 导出完整数据（Excel）</div>
          <div class="bak-desc">实时导出当前数据，但只有物料、供应商、仓库档案 + 现存量（结存数量 / 单价 / 金额）+ 出入库流水，没有采购、销售、审批等单据。</div>
          <el-button type="primary" :loading="excelLoading" @click="exportExcel">导出 Excel 到本地</el-button>
        </el-card>
      </el-col>
      <el-col v-if="user.hasRole('admin')" :xs="24" :md="12">
        <el-card shadow="never" class="bak-card">
          <div class="bak-title"><el-icon><component :is="'Download'" /></el-icon> 下载整库备份（SQL）</div>
          <div class="bak-desc">下载完整数据库备份文件（.sql.gz，含全部账套）。用于服务器整体故障后的技术性整库恢复，需 IT 还原。仅系统管理员可用。</div>
          <el-button :loading="sqlLoading" @click="exportSql">下载整库备份</el-button>
        </el-card>
      </el-col>
      <el-col v-if="user.hasRole('admin')" :xs="24" :md="24">
        <el-card shadow="never" class="bak-card bak-card-wide">
          <div class="bak-title"><el-icon><component :is="'RefreshRight'" /></el-icon> 演示账套复位</div>
          <div class="bak-desc">把演示账套(DEMO)恢复成标准样例：清掉体验者产生的单据，重建样例单据、期初库存并还原 demo 账号密码。只动演示账套，不碰任何生产数据。每天 04:10 也会自动复位一次。</div>
          <el-button :loading="demoLoading" type="danger" plain @click="resetDemo">立即复位演示账套</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi, demoApi } from '@/api'
import { saveBlob } from '@/utils/download'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const excelLoading = ref(false)
const sqlLoading = ref(false)
const demoLoading = ref(false)

function today() { return new Date().toISOString().slice(0, 10) }

// blob 下载里若后端返回的是 JSON 错误（responseType=blob 时错误也是 Blob），解析出来抛错
async function ensureBlobOk(blob: Blob) {
  if (blob && blob.type && blob.type.includes('json')) {
    try { const j = JSON.parse(await blob.text()); throw new Error(j.msg || '导出失败') } catch (e: any) {
      throw new Error(e?.message || '导出失败')
    }
  }
}

async function exportExcel() {
  excelLoading.value = true
  try {
    const blob = await backupApi.exportExcel() as unknown as Blob
    await ensureBlobOk(blob)
    saveBlob(blob, `完整数据备份-${today()}.xlsx`)
    ElMessage.success('已导出完整数据 Excel，请妥善保存到本地')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    excelLoading.value = false
  }
}

async function exportSql() {
  sqlLoading.value = true
  try {
    const blob = await backupApi.exportSql() as unknown as Blob
    await ensureBlobOk(blob)
    saveBlob(blob, `整库备份-${today()}.sql.gz`)
    ElMessage.success('已下载整库备份文件，请妥善保存到本地')
  } catch (e: any) {
    ElMessage.error(e?.message || '下载失败')
  } finally {
    sqlLoading.value = false
  }
}

async function resetDemo() {
  await ElMessageBox.confirm('将把演示账套(DEMO)恢复成标准样例，体验者产生的单据会被清掉。只动演示账套，不影响任何生产数据。确定复位?', '演示账套复位', { type: 'warning' })
  demoLoading.value = true
  try {
    const msg = await demoApi.reset() as unknown as string
    ElMessage.success(msg || '演示账套已复位')
  } catch (e: any) {
    ElMessage.error(e?.message || '复位失败')
  } finally {
    demoLoading.value = false
  }
}
</script>

<style scoped>
.bak-tip { margin-bottom: 16px; }
.bak-card { min-height: 178px; }
.bak-title { font-weight: 600; font-size: 15px; display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.bak-desc { color: var(--text-3); font-size: 13px; line-height: 1.75; margin-bottom: 16px; }

.bak-row { row-gap: 12px; }
.bak-row :deep(.el-col) { display: flex; }
.bak-row :deep(.el-col > .bak-card) { flex: 1; }
.bak-row :deep(.bak-card .el-card__body) { display: flex; flex-direction: column; height: 100%; box-sizing: border-box; }
.bak-row :deep(.bak-card .el-card__body > .el-button) { margin-top: auto; align-self: flex-end; }
.bak-row :deep(.bak-card-wide .el-card__body) { display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 16px; align-items: center; }
.bak-row :deep(.bak-card-wide .el-card__body > .el-button) { grid-row: 1 / span 2; grid-column: 2; margin-top: 0; }
</style>
