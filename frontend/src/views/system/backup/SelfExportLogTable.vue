<!-- 数据包下载记录(账套管理员看本公司、系统管理员点某家公司时共用) -->
<template>
  <el-table :data="rows" border class="readable-table" empty-text="还没有人下载过">
    <el-table-column prop="time" label="时间" width="160" :formatter="$fmtMinute" />
    <el-table-column prop="userName" label="下载人" width="120" />
    <el-table-column label="内容" min-width="220">
      <template #default="{ row }">{{ row.fileKey === 'all' ? '一键下载全部' : row.fileName }}</template>
    </el-table-column>
    <el-table-column label="版本" width="90" align="center"><template #default="{ row }">第 {{ row.version }} 版</template></el-table-column>
  </el-table>
</template>

<script setup lang="ts">
defineProps<{ rows: any[] }>()
</script>
