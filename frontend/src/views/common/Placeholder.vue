<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">{{ title }}</div>
        <div class="page-sub">当前账号暂无该功能入口，请联系系统管理员确认菜单授权</div>
      </div>
    </div>
    <el-card shadow="never">
      <el-empty description="功能未授权或菜单配置异常">
        <el-button @click="$router.push('/')">返回首页</el-button>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { computed } from 'vue'
const route = useRoute()
const title = computed(() => (route.meta?.title as string) || '即将上线')
</script>
