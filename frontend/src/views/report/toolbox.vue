<template>
  <!--
    工具箱(「报表中心」下)。页面主体见 ./components/ToolboxView.vue。
    权限:菜单跟随工具箱权限(portal.tool.use,默认财务岗),接口也按它在后端校验。
  -->
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">工具箱</div>
        <div class="page-sub">金额大写、价税分离、证件号码校验、Excel 查重和合并、账龄分析、风险预警报告。生成的文件只有本公司看得到，保留 90 天。</div>
      </div>
    </div>
    <el-card shadow="never" class="toolbox-card">
      <ToolboxView />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import ToolboxView from './components/ToolboxView.vue'
</script>

<style scoped>
.toolbox-card :deep(.el-card__body) { padding: 0; }
/* 页面标题已经写了「工具箱」,左栏顶上的同名小标题不再重复 */
.toolbox-card :deep(.side-head) { display: none; }
</style>
