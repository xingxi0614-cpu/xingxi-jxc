<template>
  <el-popover v-if="rows.length > 1" placement="top-start" :width="460" trigger="hover">
    <template #reference>
      <span class="more-mat">+{{ rows.length - 1 }}</span>
    </template>
    <div class="more-mat-title">本单共 {{ rows.length }} 种物料</div>
    <el-table :data="rows" size="small" border max-height="280">
      <el-table-column type="index" label="#" width="44" />
      <el-table-column prop="code" label="物料编码" width="120" />
      <el-table-column prop="name" label="物料名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="spec" label="规格型号" min-width="110" show-overflow-tooltip />
    </el-table>
  </el-popover>
</template>

<script setup lang="ts">
import { computed } from 'vue'

// 列表富集返回的是顿号串(编码/名称/规格按同一顺序对齐),这里拆回逐行展示
const props = defineProps<{ codes?: string | null; names?: string | null; specs?: string | null }>()

const rows = computed(() => {
  const c = (props.codes || '').split('、').filter(Boolean)
  const n = (props.names || '').split('、')
  const s = (props.specs || '').split('、')
  return c.map((code, i) => ({ code, name: n[i] || '-', spec: s[i] || '-' }))
})
</script>

<style scoped>
.more-mat {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: #2465a8;
  background: #e8f1fb;
  border-radius: 9px;
  cursor: default;
  white-space: nowrap;
}
.more-mat-title { font-weight: 600; margin-bottom: 8px; color: #374151; }
</style>
