<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="page-title">库存预警设置</div>
        <div class="page-sub">为每个物料设置最低 / 最高库存。现存低于最低=缺货预警，高于最高=超储预警，首页与「待处理事项」会实时提示，给采购留出周转期。</div>
      </div>
      <el-space>
        <el-button v-if="canEdit" type="primary" :disabled="!dirtyCount" @click="save">
          保存修改<template v-if="dirtyCount"> ({{ dirtyCount }})</template>
        </el-button>
        <el-button @click="load">刷新</el-button>
      </el-space>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索编码 / 名称 / 规格" style="width:260px" @keyup.enter="load" @clear="load">
          <template #append><el-button :icon="Search" @click="load" /></template>
        </el-input>
        <el-radio-group v-model="scope" @change="load">
          <el-radio-button label="active">有货 / 已设阈值</el-radio-button>
          <el-radio-button label="all">全部物料</el-radio-button>
        </el-radio-group>
        <el-checkbox v-model="onlyAlert">只看预警中</el-checkbox>
        <span class="hint" v-if="!canEdit">（你无编辑权限，仅可查看）</span>
      </div>

      <el-alert v-if="dirtyCount" type="warning" :closable="false" show-icon
        :title="`已修改 ${dirtyCount} 个物料的上下限，记得点右上角「保存修改」。`" style="margin-bottom:10px" />

      <el-table max-height="calc(100vh - 300px)" class="readable-table" border :data="viewRows" v-loading="loading" empty-text="暂无数据">
        <el-table-column prop="code" label="物料编码" width="130" :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="name" label="物料名称" min-width="200" show-overflow-tooltip :fixed="freezeLeft ? 'left' : undefined" />
        <el-table-column prop="spec" label="规格型号" min-width="140" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" align="center" />
        <el-table-column label="现存量" width="120" align="right">
          <template #default="{ row }"><b>{{ fmtQty(row.qty) }}</b></template>
        </el-table-column>
        <el-table-column label="最低库存" width="150" align="center">
          <template #default="{ row }">
            <el-input-number v-if="canEdit" v-model="row.safetyQty" :min="0" :precision="2" :controls="false"
              size="small" style="width:110px" placeholder="不设" @change="markDirty(row)" />
            <span v-else>{{ row.safetyQty > 0 ? fmtQty(row.safetyQty) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最高库存" width="150" align="center">
          <template #default="{ row }">
            <el-input-number v-if="canEdit" v-model="row.maxQty" :min="0" :precision="2" :controls="false"
              size="small" style="width:110px" placeholder="不设" @change="markDirty(row)" />
            <span v-else>{{ row.maxQty > 0 ? fmtQty(row.maxQty) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="statusOf(row).type" effect="light" round>{{ statusOf(row).text }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip">
        共 {{ viewRows.length }} 条。上下限留空或填 0 表示「不设该方向的预警」。最高库存不能小于最低库存。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Search } from '@/icons'
import { ElMessage } from 'element-plus'
import { inventoryApi } from '@/api'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const canEdit = computed(() =>
  user.hasRole('purchaser') || user.hasRole('warehouse') || user.hasFinanceAccessOrAdmin() || user.hasRole('account_admin'))

const loading = ref(false)
const rows = ref<any[]>([])
const keyword = ref('')
const scope = ref<'active' | 'all'>('active')
const onlyAlert = ref(false)
const freezeLeft = ref(true)
const dirty = ref<Set<number>>(new Set())

const dirtyCount = computed(() => dirty.value.size)

function fmtQty(v: any) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
}

// 缺货 / 超储 / 正常 / 未设阈值
type TagType = 'danger' | 'warning' | 'success' | 'info'
function statusOf(row: any): { type: TagType; text: string } {
  const qty = Number(row.qty || 0)
  const min = Number(row.safetyQty || 0)
  const max = Number(row.maxQty || 0)
  if (min > 0 && qty < min) return { type: 'danger', text: '缺货' }
  if (max > 0 && qty > max) return { type: 'warning', text: '超储' }
  if (min > 0 || max > 0) return { type: 'success', text: '正常' }
  return { type: 'info', text: '未设阈值' }
}

const viewRows = computed(() => {
  if (!onlyAlert.value) return rows.value
  return rows.value.filter(r => ['缺货', '超储'].includes(statusOf(r).text))
})

function markDirty(row: any) {
  dirty.value.add(row.materialId)
  dirty.value = new Set(dirty.value)
}

async function load() {
  loading.value = true
  try {
    const list = await inventoryApi.stockLimitList({ keyword: keyword.value || undefined, scope: scope.value }) as any[]
    rows.value = (list || []).map(r => ({
      ...r,
      safetyQty: r.safetyQty == null ? undefined : Number(r.safetyQty),
      maxQty: r.maxQty == null ? undefined : Number(r.maxQty)
    }))
    dirty.value = new Set()
  } finally {
    loading.value = false
  }
}

async function save() {
  const items = rows.value
    .filter(r => dirty.value.has(r.materialId))
    .map(r => ({
      materialId: r.materialId,
      safetyQty: r.safetyQty == null || r.safetyQty === '' ? 0 : r.safetyQty,
      maxQty: r.maxQty == null || r.maxQty === '' ? 0 : r.maxQty
    }))
  // 前端先挡一次：最高 < 最低
  const bad = items.find(i => Number(i.maxQty) > 0 && Number(i.safetyQty) > 0 && Number(i.maxQty) < Number(i.safetyQty))
  if (bad) { ElMessage.error('有物料的最高库存小于最低库存，请修正后再保存'); return }
  if (!items.length) return
  loading.value = true
  try {
    const res = await inventoryApi.stockLimitSave(items) as any
    ElMessage.success(`已保存 ${res?.updated ?? items.length} 个物料的上下限`)
    await load()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.hint { color: var(--el-color-warning); font-size: 13px; }
.tip { margin-top: 10px; color: var(--el-text-color-secondary); font-size: 12.5px; }
</style>
