<template>
  <div class="page go">
    <div class="page-head">
      <div>
        <div class="page-title">集团汇总分析</div>
        <div class="page-sub">各公司并排对比，一眼看出哪家异常、该追哪一笔 · 仅系统管理员可见</div>
      </div>
      <el-space>
        <el-radio-group v-model="period" @change="load">
          <el-radio-button value="month">本月</el-radio-button>
          <el-radio-button value="last">上月</el-radio-button>
          <el-radio-button value="ytd">本年累计</el-radio-button>
        </el-radio-group>
        <el-button :icon="Download" :disabled="!rows.length" @click="exportExcel">导出Excel</el-button>
        <el-button @click="load">刷新</el-button>
      </el-space>
    </div>

    <!-- 本页永远显示全部公司；但顶栏若只看某一家，点别家公司的单据会被拦(无权查看)，所以提醒先切回 -->
    <el-alert v-if="viewCompany.selectedId" type="warning" :closable="false" show-icon class="go-narrow-tip">
      <template #title>
        顶栏当前只看「{{ viewCompany.selectedName }}」。本页仍显示全部公司，但点开其他公司的单据或物料前，需要先切回全部公司。
        <el-button link type="primary" @click="viewCompany.select('')">切回全部公司</el-button>
      </template>
    </el-alert>

    <KpiCards v-if="rows.length" :items="kpis" />
    <el-card shadow="never" class="go-card" v-loading="loading">
      <div class="go-rule">
        <span>统计期间：<b>{{ data?.periodLabel || '-' }}</b></span>
        <span>金额不含税（应付余额除外，按含税），单位：元</span>
        <span>测试、演示公司不计入</span>
        <span>库存、未开票、应付、待审批是此刻的余额，不随期间切换</span>
      </div>
      <div class="go-tw table-scroll" tabindex="0" aria-label="集团汇总表">
        <table class="go-table">
          <thead>
            <tr>
              <th>公司</th>
              <th>库存金额<small>占集团比重</small></th>
              <th>采购额<small>期间内已审批订单</small></th>
              <th>出库额<small>期间内已审批出库,已扣退料</small></th>
              <th>已入库未开票<small>其中挂账超 {{ data?.agingDays || 90 }} 天</small></th>
              <th>应付余额<small>含税 · 已开票未付款</small></th>
              <th>待审批<small>其中压了超 {{ data?.pendingStaleDays || 7 }} 天</small></th>
              <th>结账到<small>最近已结账月份</small></th>
              <th>发票已录比例<small>入库金额里已开票的</small></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in rows" :key="c.id">
              <td>
                <button type="button" class="go-link co" :title="`只看${c.name}的数据`" @click="enterCompany(c)">{{ c.name }}</button>
                <small>{{ c.code }}</small>
              </td>
              <td>{{ money(c.stock) }}
                <small><span class="bar"><i :style="{ width: share(c) + '%' }" /></span>{{ share(c).toFixed(1) }}%</small>
              </td>
              <td>{{ money(c.buy) }}</td>
              <td>{{ money(c.out) }}</td>
              <td :class="lv.uninvoiced(c)">{{ money(c.uninvoiced) }}
                <small :class="lv.uninvoiced(c)">{{ Number(c.uninvoicedOld) > 0 ? `超 ${data.agingDays} 天 ${money(c.uninvoicedOld)}` : `无超 ${data.agingDays} 天` }}</small>
              </td>
              <td>
                <span v-if="c.payable === null" class="chip g" title="这家公司没有在系统里登记过付款，应付余额算不出来">未录付款</span>
                <template v-else>{{ money(c.payable) }}</template>
              </td>
              <td :class="lv.pending(c)">{{ c.pending }} 张
                <small :class="lv.pending(c)">{{ c.pendingStale ? `超 ${data.pendingStaleDays} 天 ${c.pendingStale} 张` : '无积压' }}</small>
              </td>
              <td :class="lv.close(c)">
                <span v-if="!c.closedTo" class="chip r">从未结账</span>
                <template v-else>{{ c.closedTo }}
                  <span v-if="lv.close(c) === ''" class="chip k">已跟上</span>
                  <small v-else class="mid">落后 {{ monthsBehind(c.closedTo) }} 个月</small>
                </template>
              </td>
              <td :class="lv.invoice(c)">
                <span v-if="c.invoiceRate === null" class="chip g">暂无入库</span>
                <span v-else-if="c.invoiceRate === 0" class="chip r">一张没录</span>
                <template v-else><span class="bar"><i :style="{ width: c.invoiceRate + '%' }" /></span>{{ c.invoiceRate }}%</template>
              </td>
            </tr>
            <tr v-if="!rows.length && !loading"><td colspan="9" class="empty">暂无数据</td></tr>
          </tbody>
          <tfoot v-if="rows.length">
            <tr>
              <td>集团合计<span class="code">{{ rows.length }} 家在用</span></td>
              <td>{{ money(total.stock) }}</td>
              <td>{{ money(total.buy) }}</td>
              <td>{{ money(total.out) }}</td>
              <td>{{ money(total.uninvoiced) }}<small>超 {{ data.agingDays }} 天 {{ money(total.uninvoicedOld) }}</small></td>
              <td>{{ total.payCos ? money(total.payable) : '-' }}<small>仅 {{ total.payCos }} 家有数</small></td>
              <td>{{ total.pending }} 张<small>超 {{ data.pendingStaleDays }} 天 {{ total.pendingStale }} 张</small></td>
              <td>{{ total.closedOk }} / {{ rows.length }} 家已跟上</td>
              <td>{{ total.invoiceCos }} / {{ rows.length }} 家在录</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <div v-if="data?.idle?.length" class="go-idle">另有 {{ data.idle.length }} 家已开账套但还没有任何单据，未列入：{{ data.idle.join('、') }}</div>
      <div class="go-legend">
        <span><i class="hot" />需要马上追问</span>
        <span><i class="mid" />值得留意</span>
        <span><span class="chip g">未录付款</span> 没登记过付款的公司不显示应付数字，以免把"没录"误读成"欠款巨大"</span>
      </div>
    </el-card>

    <div class="go-ranks">
      <el-card shadow="never" class="go-card rank">
        <div class="rank-head">未开票挂账最久 <small>货到了、票没到 · 前 10 张</small></div>
        <ol>
          <li v-for="(r, i) in data?.estimateTop || []" :key="r.docNo" @click="openDoc(r.docNo)">
            <span class="n">{{ i + 1 }}</span>
            <span class="m"><b>{{ r.docNo }}</b><span>{{ r.company }} · {{ r.supplier || '未填供应商' }}</span></span>
            <span class="v">{{ money(r.amount) }}<small>已挂 {{ r.days }} 天</small></span>
          </li>
          <li v-if="!(data?.estimateTop || []).length" class="none">没有未开票的入库单</li>
        </ol>
      </el-card>
      <el-card shadow="never" class="go-card rank">
        <div class="rank-head">压得最久的待审批 <small>前 10 张</small></div>
        <ol>
          <li v-for="(r, i) in data?.pendingTop || []" :key="r.docNo" @click="openDoc(r.docNo)">
            <span class="n">{{ i + 1 }}</span>
            <span class="m"><b>{{ r.docNo }}</b><span>{{ r.company }} · {{ BIZ_LABEL[r.bizType] || r.bizType }}{{ r.nextRole ? ' · 轮到：' + roleLabel(r.nextRole) : '' }}</span></span>
            <span class="v">{{ Number(r.amount) > 0 ? money(r.amount) : '—' }}<small>已压 {{ r.days }} 天</small></span>
          </li>
          <li v-if="!(data?.pendingTop || []).length" class="none">没有待审批的单据</li>
        </ol>
      </el-card>
      <el-card shadow="never" class="go-card rank">
        <div class="rank-head">库存金额最大的物料 <small>前 10 种</small></div>
        <ol>
          <li v-for="(r, i) in data?.stockTop || []" :key="r.code" @click="openStock(r)">
            <span class="n">{{ i + 1 }}</span>
            <span class="m"><b>{{ r.name }}{{ r.spec ? '（' + r.spec + '）' : '' }}</b><span>{{ r.code }} · {{ r.companyCount }} 家公司有库存</span></span>
            <span class="v">{{ money(r.amount) }}<small>占集团 {{ total.stock ? (Number(r.amount) / total.stock * 100).toFixed(1) : '0.0' }}%</small></span>
          </li>
          <li v-if="!(data?.stockTop || []).length" class="none">没有库存</li>
        </ol>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import KpiCards from '@/components/KpiCards.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Download } from '@/icons'
import { reportApi } from '@/api'
import { roleLabel } from '@/utils/roleLabel'
import { exportRowsToExcel } from '@/utils/tableExport'
import { showError } from '@/utils/notify'
import { useViewCompanyStore } from '@/stores/viewCompany'

const router = useRouter()
const viewCompany = useViewCompanyStore()
const period = ref<'month' | 'last' | 'ytd'>('month')
const loading = ref(false)
const data = ref<any>(null)
const rows = computed<any[]>(() => data.value?.companies || [])

const BIZ_LABEL: Record<string, string> = {
  PO: '采购订单', RECV: '采购入库', INV: '采购发票', OREQ: '出库申请', OUT: '材料出库', RETURN: '退料单',
  LOAN: '借料单', TRANSFER: '库存转库', PRET: '采购退货', STOCKTAKE: '盘点单',
  SO: '销售订单', SALE_OUT: '销售出库', SALE_INV: '销售发票', SALE_RETURN: '销售退货'
}

const money = (v: unknown) => Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
const sumOf = (k: string) => rows.value.reduce((s, c) => s + Number(c[k] || 0), 0)

const total = computed(() => ({
  stock: sumOf('stock'), buy: sumOf('buy'), out: sumOf('out'),
  uninvoiced: sumOf('uninvoiced'), uninvoicedOld: sumOf('uninvoicedOld'),
  payable: sumOf('payable'), payCos: rows.value.filter(c => c.payable !== null).length,
  pending: sumOf('pending'), pendingStale: sumOf('pendingStale'),
  closedOk: rows.value.filter(c => lv.close(c) === '').length,
  invoiceCos: rows.value.filter(c => Number(c.invoiceRate) > 0).length
}))
const share = (c: any) => total.value.stock ? Number(c.stock) / total.value.stock * 100 : 0

function monthsBehind(closedTo: string) {
  const [y1, m1] = String(data.value?.lastFullMonth || '').split('-').map(Number)
  const [y2, m2] = closedTo.split('-').map(Number)
  return Math.max(0, (y1 - y2) * 12 + (m1 - m2))
}

/**
 * 标色门槛(2026-10-09 定)：返回 'hot'=需要马上追问 / 'mid'=值得留意 / ''=正常。
 * 改门槛只改这里。
 */
const lv = {
  // 未开票里挂账超 90 天的占比：过半标红，过四分之一标橙
  uninvoiced: (c: any) => { const p = Number(c.uninvoiced) > 0 ? Number(c.uninvoicedOld) / Number(c.uninvoiced) : 0; return p > .5 ? 'hot' : p > .25 ? 'mid' : '' },
  // 压了超 7 天的待审批：10 张以上标红，5 张以上标橙
  pending: (c: any) => c.pendingStale >= 10 ? 'hot' : c.pendingStale >= 5 ? 'mid' : '',
  // 结账：从未结过标红；没结到上一个完整月份标橙
  close: (c: any) => !c.closedTo ? 'hot' : c.closedTo < (data.value?.lastFullMonth || '') ? 'mid' : '',
  // 发票：一张没录标红；录了不到一半标橙；还没有入库的不评
  invoice: (c: any) => c.invoiceRate === null ? '' : c.invoiceRate === 0 ? 'hot' : c.invoiceRate < 50 ? 'mid' : ''
}

async function load() {
  loading.value = true
  try { data.value = await reportApi.groupOverview(period.value) }
  catch (e) { showError(e, '加载失败') }
  finally { loading.value = false }
}

/** 点公司名：把顶栏「查看公司」切到这家并进它的首页(整页刷新，之后全站只看这家)。 */
function enterCompany(c: any) { viewCompany.select(c.id, '/work/home') }
function openDoc(docNo: string) { router.push({ path: '/trace/chain', query: { docNo } }) }
function openStock(r: any) { router.push({ path: '/inventory/balance', query: { keyword: r.code } }) }

function exportExcel() {
  exportRowsToExcel(`集团汇总分析_${data.value?.periodLabel || ''}`, [
    { label: '公司', key: 'name' }, { label: '代号', key: 'code' },
    { label: '库存金额', money: true, key: 'stock' }, { label: '采购额', money: true, key: 'buy' }, { label: '出库额', money: true, key: 'out' },
    { label: '已入库未开票', money: true, key: 'uninvoiced' }, { label: `其中超${data.value?.agingDays}天`, money: true, key: 'uninvoicedOld' },
    { label: '应付余额(含税)', money: true, value: (c: any) => c.payable === null ? '未录付款' : c.payable },
    { label: '待审批(张)', numeric: true, key: 'pending' }, { label: `其中超${data.value?.pendingStaleDays}天(张)`, numeric: true, key: 'pendingStale' },
    { label: '结账到', value: (c: any) => c.closedTo || '从未结账' },
    { label: '发票已录比例(%)', numeric: true, value: (c: any) => c.invoiceRate === null ? '暂无入库' : c.invoiceRate }
  ], rows.value, '集团汇总')
}

onMounted(load)

// 顶部汇总卡（2026-10-17 界面改版，原型 R_Group）：与下表合计行同一组数
const kpis = computed(() => [
  { label: '在用公司', value: `${rows.value.length} 家`, hint: '测试、演示公司不计入' },
  { label: '库存合计', value: money(total.value.stock), hint: '此刻余额' },
  { label: '暂估未开票', value: money(total.value.uninvoiced), hint: Number(total.value.uninvoicedOld) > 0 ? `其中超 ${data.value?.agingDays} 天 ${money(total.value.uninvoicedOld)}` : '没有超期', tone: Number(total.value.uninvoicedOld) > 0 ? 'amber' as const : undefined },
  { label: '待审批', value: `${total.value.pending} 张`, hint: total.value.pendingStale ? `积压 ${total.value.pendingStale} 张` : '无积压', tone: total.value.pendingStale ? 'amber' as const : undefined },
  { label: '结账正常', value: `${total.value.closedOk} / ${rows.value.length} 家`, hint: '其余公司结账落后或从未结账', tone: total.value.closedOk < rows.value.length ? 'red' as const : 'green' as const }
])
</script>

<style scoped lang="scss">
.go-card { :deep(.el-card__body) { padding: 0; } }
.go-narrow-tip { margin-bottom: 12px; }
.go-rule { display: flex; gap: 18px; flex-wrap: wrap; padding: 10px 16px; color: var(--text-2); font-size: 12.5px; border-bottom: 1px solid var(--border-2);
  span::before { content: ""; display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--text-3); margin-right: 6px; vertical-align: middle; }
  b { color: var(--text-1); } }
.go-tw { overflow-x: auto; }
/* 列宽按 1440 宽屏(去掉侧栏后约 1120px)刚好放下 9 列来定；再窄才在卡片内横向滚动 */
.go-table { border-collapse: collapse; width: 100%; min-width: 1040px; color: var(--table-text);
  th, td { padding: 8px 10px; border: 1px solid var(--table-grid); white-space: nowrap; text-align: right; font-variant-numeric: tabular-nums; }
  th { background: var(--table-header-bg); color: var(--table-header-text); font-weight: 600; font-size: 13px; border-bottom: 1px solid var(--table-grid-strong); }
  th small { display: block; color: var(--text-2); font-weight: 400; font-size: 11.5px; }
  tbody tr { background: var(--bg-panel); }
  th:first-child, td:first-child { padding-left: 16px; text-align: left; position: sticky; left: 0; background: inherit; z-index: 1; }
  th:first-child { background: var(--table-header-bg); }
  td small { display: block; color: var(--text-3); font-size: 12px; line-height: 1.35; }
  td small.hot { color: var(--color-danger); }
  td small.mid { color: var(--color-warning); }
  td.hot { background: rgba(220, 77, 77, .10); }
  td.mid { background: rgba(214, 136, 31, .12); }
  tfoot td { background: var(--table-summary-bg); font-weight: 600; border-top: 1px solid var(--table-grid-strong); }
  tfoot td:first-child { background: var(--table-summary-bg); }
  .empty { text-align: center; color: var(--text-3); padding: 36px 0; }
  .code { color: var(--text-3); font-size: 12px; margin-left: 6px; font-weight: 400; } }
.go-link { border: 0; background: none; padding: 0; font: inherit; color: var(--color-primary); cursor: pointer; font-weight: 600;
  &:hover { text-decoration: underline; } }
.chip { display: inline-block; font-size: 12px; line-height: 20px; padding: 0 8px; border-radius: 999px;
  &.g { background: var(--surface-soft); color: var(--text-2); box-shadow: inset 0 0 0 1px var(--border-1); }
  &.r { background: rgba(220, 77, 77, .12); color: var(--color-danger); }
  &.k { background: var(--color-success-soft); color: var(--color-success); margin-left: 4px; } }
.bar { display: inline-block; width: 44px; height: 6px; border-radius: 3px; background: var(--border-1); vertical-align: middle; margin-right: 8px; overflow: hidden;
  i { display: block; height: 100%; background: var(--color-primary); border-radius: 3px; } }
.go-idle { padding: 10px 16px; color: var(--text-3); font-size: 12.5px; border-top: 1px solid var(--border-2); }
.go-legend { display: flex; gap: 16px; flex-wrap: wrap; padding: 10px 16px; color: var(--text-3); font-size: 12px; border-top: 1px solid var(--border-2);
  i { display: inline-block; width: 12px; height: 12px; border-radius: 3px; vertical-align: -2px; margin-right: 5px; }
  i.hot { background: rgba(220, 77, 77, .22); }
  i.mid { background: rgba(214, 136, 31, .26); } }
.go-ranks { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-top: 14px; }
.rank-head { padding: 13px 16px; font-size: 14.5px; font-weight: 600; border-bottom: 1px solid var(--border-2); display: flex; justify-content: space-between; align-items: baseline;
  small { color: var(--text-3); font-weight: 400; font-size: 12px; } }
.rank ol { list-style: none; margin: 0; padding: 4px 0; }
.rank li { display: grid; grid-template-columns: 22px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 8px 16px; cursor: pointer;
  &:hover { background: var(--bg-panel-hover); }
  &.none { display: block; color: var(--text-3); text-align: center; padding: 28px 0; cursor: default; }
  &:nth-child(-n+3) .n { color: var(--color-danger); font-weight: 600; }
  .n { color: var(--text-3); font-size: 12.5px; text-align: center; }
  .m { min-width: 0; }
  .m b, .m span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .m b { font-weight: 500; color: var(--color-primary); }
  .m span { color: var(--text-3); font-size: 12px; }
  .v { text-align: right; font-variant-numeric: tabular-nums; }
  .v small { display: block; color: var(--text-3); font-size: 12px; } }
@media (max-width: 1100px) { .go-ranks { grid-template-columns: 1fr; } }
</style>
