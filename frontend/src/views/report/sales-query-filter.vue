<template>
  <el-form :inline="true" size="default" style="margin-bottom:8px">
    <el-form-item label="日期">
      <el-date-picker v-model="model.dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width:240px" :shortcuts="shortcuts" />
    </el-form-item>
    <el-form-item label="客户">
      <el-select v-model="model.customerId" filterable clearable remote :remote-method="searchCustomers" :loading="cusLoading" placeholder="全部客户" style="width:180px">
        <el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" />
      </el-select>
    </el-form-item>
    <el-form-item label="物料"><MaterialLookup v-model="model.materialId" placeholder="全部物料" /></el-form-item>
    <el-form-item v-if="statusOptions?.length" label="状态">
      <el-select v-model="model.status" placeholder="全部" clearable style="width:120px">
        <el-option v-for="s in statusOptions" :key="s.v" :value="s.v" :label="s.l" />
      </el-select>
    </el-form-item>
    <!-- 「查询」跟关键词放同一格：单独占一格时，前面几项刚好排满一行，它就被挤到第二行孤零零一个（2026-10-13 用户反馈按钮是歪的） -->
    <el-form-item label="关键词" class="filter-with-action">
      <el-input v-model="model.keyword" placeholder="单号/客户" clearable style="width:160px" @keyup.enter="emit('search')" />
      <el-button type="primary" @click="emit('search')">查询</el-button>
    </el-form-item>
  </el-form>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { salesApi } from '@/api'
import MaterialLookup from '@/components/MaterialLookup.vue'
import type { SalesFilter } from './salesQueryShared'

const props = defineProps<{ modelValue: SalesFilter; statusOptions?: { v: string; l: string }[] }>()
const emit = defineEmits<{ 'update:modelValue': [v: SalesFilter]; search: [] }>()
const model = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })

const shortcuts = [
  { text: '本月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth(), 1), d] } },
  { text: '上月', value: () => { const d = new Date(); return [new Date(d.getFullYear(), d.getMonth() - 1, 1), new Date(d.getFullYear(), d.getMonth(), 0)] } },
  { text: '本季度', value: () => { const d = new Date(); const q = Math.floor(d.getMonth() / 3) * 3; return [new Date(d.getFullYear(), q, 1), d] } },
  { text: '本年至今', value: () => { const d = new Date(); return [new Date(d.getFullYear(), 0, 1), d] } }
]

const customers = ref<any[]>([])
const cusLoading = ref(false)
async function searchCustomers(kw: string) { cusLoading.value = true; try { customers.value = await salesApi.customers(kw) as any[] } finally { cusLoading.value = false } }
</script>
