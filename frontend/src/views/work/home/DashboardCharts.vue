<template>
  <el-row :gutter="14" class="chart-row">
    <el-col :xs="24" :md="12">
      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">{{ pieTitle }}</span></template>
        <v-chart :option="pieOption" autoresize class="chart" />
      </el-card>
    </el-col>
    <el-col :xs="24" :md="12">
      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">{{ barTitle }}</span></template>
        <v-chart :option="barOption" autoresize class="chart" />
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { PieChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([PieChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const props = defineProps<{
  role: string
  stats: Record<string, number>
}>()

interface ChartItem {
  name: string
  value: number
  color: string
}

const colors = ['#c8954a', '#5d8a4e', '#4a7ba8', '#b8734a', '#8a6d9c', '#3d8a7a', '#7a6d5d', '#4a6890']

function items(): ChartItem[] {
  const s = props.stats
  const r = props.role
  if (r === 'purchaser') {
    return [
      { name: '订单草稿', value: s.poDraft || 0, color: colors[0] },
      { name: '审批中', value: s.poPending || 0, color: colors[2] },
      { name: '已审批', value: s.poApproved || 0, color: colors[1] }
    ]
  }
  if (r === 'warehouse') {
    return [
      { name: '待入库', value: s.poApproved || 0, color: colors[0] },
      { name: '待审入库', value: s.recvPending || 0, color: colors[1] },
      { name: '待出库', value: s.oreqApproved || 0, color: colors[3] }
    ]
  }
  if (r === 'material_accountant' || r === 'finance') {
    return [
      { name: '待审订单', value: s.poPending || 0, color: colors[2] },
      { name: '待审入库', value: s.recvPending || 0, color: colors[1] },
      { name: '待审发票', value: s.invoicePending || 0, color: colors[4] },
      { name: '待审出库', value: (s.oreqPending || 0) + (s.outPending || 0), color: colors[3] }
    ]
  }
  // admin / account_admin / fallback
  return [
    { name: '用户', value: s.userCount || 0, color: colors[0] },
    { name: '角色', value: s.roleCount || 0, color: colors[2] },
    { name: '组织', value: s.orgCount || 0, color: colors[6] },
    { name: '资料', value: s.masterDataCount || 0, color: colors[1] },
    { name: '项目', value: s.projectCount || 0, color: colors[3] }
  ]
}

const titleMap: Record<string, [string, string]> = {
  purchaser: ['采购订单分布', '订单状态统计'],
  warehouse: ['仓库业务分布', '业务操作统计'],
  material_accountant: ['待审单据分布', '待审类型统计'],
  finance: ['待审单据分布', '待审类型统计'],
  admin: ['系统数据分布', '各模块统计'],
  account_admin: ['账套数据分布', '各模块统计']
}

const pieTitle = computed(() => titleMap[props.role]?.[0] || '数据分布')
const barTitle = computed(() => titleMap[props.role]?.[1] || '数据统计')

const pieOption = computed(() => {
  const data = items().filter(i => i.value > 0)
  if (data.length === 0) {
    return {
      title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#9b9288', fontSize: 14, fontWeight: 400 } },
      series: [{ type: 'pie', radius: ['50%', '78%'], center: ['50%', '52%'], data: [{ value: 1, itemStyle: { color: '#f0ece5' } }], silent: true, label: { show: false } }]
    }
  }
  return {
    tooltip: { trigger: 'item' as const },
    series: [{
      type: 'pie',
      radius: ['50%', '78%'],
      center: ['50%', '52%'],
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
      data: data.map(i => ({ name: i.name, value: i.value, itemStyle: { color: i.color } }))
    }]
  }
})

const barOption = computed(() => {
  const data = items().filter(i => i.value > 0)
  return {
    tooltip: { trigger: 'axis' as const, axisPointer: { type: 'shadow' } },
    grid: { left: '3%', right: '8%', bottom: '3%', top: '6%', containLabel: true },
    xAxis: { type: 'value' as const, axisLabel: { color: '#9b9288' } },
    yAxis: {
      type: 'category' as const,
      data: data.map(i => i.name).reverse(),
      axisLabel: { color: '#6b6258', fontSize: 12 },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [{
      type: 'bar',
      data: data.map(i => ({ value: i.value, itemStyle: { color: i.color, borderRadius: [0, 4, 4, 0] } })).reverse(),
      barWidth: 18,
      label: { show: true, position: 'right' as const, color: '#6b6258', fontSize: 12 }
    }]
  }
})
</script>

<style scoped lang="scss">
.chart-row {
  margin-top: 0;
}

.chart-card {
  min-height: 310px;
}

.chart-title {
  font-weight: 650;
  color: var(--text-1);
  font-size: 14px;
}

.chart {
  height: 260px;
  width: 100%;
}
</style>
