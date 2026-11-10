<template>
  <div class="page role-guide-page">
    <section class="guide-hero">
      <div>
        <div class="eyebrow">操作手册</div>
        <h1>{{ heroTitle }}</h1>
        <p>{{ heroSub }}</p>
      </div>
      <div class="hero-actions">
        <el-button :icon="Printer" @click="printPage">打印</el-button>
        <el-button :icon="Refresh" @click="activeCode = defaultRole">查看我的角色</el-button>
      </div>
    </section>

    <section class="role-switch" v-if="visibleGuides.length > 1">
      <button
        v-for="guide in visibleGuides"
        :key="guide.code"
        type="button"
        :class="{ active: activeCode === guide.code }"
        @click="activeCode = guide.code"
      >
        <el-icon><component :is="guide.icon" /></el-icon>
        <span>{{ guide.name }}</span>
      </button>
    </section>

    <section class="guide-layout" v-if="currentGuide">
      <aside class="guide-summary">
        <div class="role-badge" :class="currentGuide.code">
          <el-icon><component :is="currentGuide.icon" /></el-icon>
        </div>
        <h2>{{ currentGuide.name }}</h2>
        <p>{{ currentGuide.position }}</p>
        <div class="summary-line">
          <span>核心职责</span>
          <strong>{{ currentGuide.responsibilities.length }}</strong>
        </div>
        <div class="summary-line">
          <span>常用入口</span>
          <strong>{{ currentGuide.entries.length }}</strong>
        </div>
      </aside>

      <main class="guide-content">
        <el-card shadow="never" class="guide-card">
          <template #header>
            <div class="card-title">
              <el-icon><Compass /></el-icon>
              <span>职责边界</span>
            </div>
          </template>
          <ul class="clean-list">
            <li v-for="item in currentGuide.responsibilities" :key="item">{{ item }}</li>
          </ul>
        </el-card>

        <el-card shadow="never" class="guide-card">
          <template #header>
            <div class="card-title">
              <el-icon><Operation /></el-icon>
              <span>日常处理流程</span>
            </div>
          </template>
          <div class="steps">
            <div v-for="(step, index) in currentGuide.steps" :key="step.title" class="step-row">
              <span class="step-no">{{ index + 1 }}</span>
              <div>
                <strong>{{ step.title }}</strong>
                <p>{{ step.text }}</p>
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="guide-card">
          <template #header>
            <div class="card-title">
              <el-icon><Menu /></el-icon>
              <span>常用功能入口</span>
            </div>
          </template>
          <div class="entry-grid">
            <div v-for="entry in currentGuide.entries" :key="entry.name" class="entry-item">
              <div class="entry-name">{{ entry.name }}</div>
              <div class="entry-path">{{ entry.path }}</div>
              <p>{{ entry.text }}</p>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="guide-card">
          <template #header>
            <div class="card-title">
              <el-icon><Warning /></el-icon>
              <span>注意事项</span>
            </div>
          </template>
          <ul class="clean-list warning-list">
            <li v-for="item in currentGuide.notes" :key="item">{{ item }}</li>
          </ul>
        </el-card>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import {
  Aim,
  Box,
  Coin,
  Compass,
  DataAnalysis,
  Document,
  Menu,
  Monitor,
  Operation,
  Printer,
  Refresh,
  ShoppingCart,
  Setting,
  Van,
  Warning
} from '@/icons'

interface GuideStep {
  title: string
  text: string
}

interface GuideEntry {
  name: string
  path: string
  text: string
}

interface RoleGuide {
  code: string
  name: string
  position: string
  icon: any
  responsibilities: string[]
  steps: GuideStep[]
  entries: GuideEntry[]
  notes: string[]
}

const user = useUserStore()

const guides: RoleGuide[] = [
  {
    code: 'purchaser',
    name: '采购员',
    position: '负责采购订单与出库申请的发起和跟踪。',
    icon: ShoppingCart,
    responsibilities: [
      '根据业务需求直接创建采购订单，填写供应商、仓库、下单日期和订单明细。',
      '采购订单保存后可以提交审核，审核通过后进入入库和发票匹配流程。',
      '发起出库申请，明确仓库、工程项目、出库类型、业务分类、材料申请人和领用明细。',
      '跟踪订单订货、入库和未入库数量，不直接处理仓库数量和库存金额。'
    ],
    steps: [
      { title: '创建采购订单', text: '进入采购订单，点击新建订单；通过放大镜选择供应商和物料，系统会自动带出物料名称、单位等信息。' },
      { title: '提交财务审核', text: '确认必填项完整、数量和单价均已填写后保存并提交，财务审核通过后才能办理入库。' },
      { title: '跟踪订单执行', text: '进入订单跟踪或采购订单查看订货、已入库、未入库数量，发现异常及时与仓管或财务核对。' },
      { title: '发起出库申请', text: '出库前填写工程项目和领用明细，审批通过后由仓管关联申请制单，出库数量不能超过审批数量。' },
      { title: '录申请时能看到库存', text: '选完物料就地显示「当前库存」。申请是用料计划，零库存或申请数量超过库存时只提醒，仍可保存草稿、提交审批；实际发料时按实时库存校验，库存不足不能超量过账。' }
    ],
    entries: [
      { name: '采购订单', path: '采购管理 / 采购订单', text: '采购业务的起点，可新建、修改、提交、取消审批或删除允许范围内的订单。' },
      { name: '订单跟踪', path: '采购管理 / 订单跟踪', text: '按订单查看订货、已入库和未入库进度。' },
      { name: '出库申请', path: '出库管理 / 出库申请', text: '工程项目或材料领用前的申请单，由财务审批后交仓管执行。' },
      { name: '网点录入的销售单', path: '工作台 / 我的待办', text: '【按公司开通】网点扫码录入销售单后会推一条待办过来，点开核对客户、型号、金额无误后再提交审批。此时单据还是草稿，不动库存。' }
    ],
    notes: [
      '采购员不维护库存金额。',
      '点击任意列表里的单据号（蓝色链接）可查看该单据的完整单据链（采购→入库→发票），追溯已融入单据，无需单独菜单。',
      '供应商、物料、仓库、工程项目等基础资料来自管理员维护，选择不到时联系管理员补充。',
      '采购订单和出库申请提交前必须检查数量、单价、日期和明细完整性。'
    ]
  },
  {
    code: 'warehouse',
    name: '仓管员',
    position: '负责入库、材料出库和库存数量核对，不维护金额成本。',
    icon: Box,
    responsibilities: [
      '根据已审批采购订单办理采购入库，确认实收数量和仓库。',
      '根据已审批出库申请关联生成材料出库单，控制出库数量不超过申请数量。',
      '查看现存量表和出入库流水，核对库存实时数量。',
      '仓管只负责数量准确，不处理单价、金额和库存参考。'
    ],
    steps: [
      { title: '办理采购入库', text: '进入采购入库单，选择已审批订单，按实际到货数量录入并提交。' },
      { title: '办理材料出库', text: '进入材料出库，先筛选已审批出库申请，再关联生成出库单并填写本次出库数量。' },
      { title: '核对库存数量', text: '在“库存 / 现存量”查看实时数量（不分结账期、录单即变，可作盘点底表）；在“报表中心 / 出入库流水”查看每次数量变动来源。' },
      { title: '处理异常', text: '数量不足、物料不一致或申请信息不清楚时，不要强行出库，先退回业务发起人核对。' }
    ],
    entries: [
      { name: '采购入库单', path: '入库管理 / 采购入库单', text: '处理采购到货入库，入库后影响库存数量。' },
      { name: '材料出库', path: '出库管理 / 材料出库', text: '关联已审批出库申请制单，控制出库数量。' },
      { name: '现存量', path: '库存 / 现存量', text: '查看仓库实时现存量（数量），可作盘点底表（原“库存余额”已改名）。' },
      { name: '出入库流水', path: '报表中心 / 出入库流水', text: '按物料查每次出入库变动，仅展示已审批正式单据（原在“库存”分组，已挪到报表中心）。' }
    ],
    notes: [
      '材料出库必须来源于已审批出库申请，不应绕过申请直接出库。',
      '出库数量不能超过出库申请单审批数量。',
      '审批已改为“一键通过”，点“审批”即通过、无需填意见；驳回仍需填原因。',
      '仓管界面如出现金额相关字段，应以数量核对为准并反馈管理员排查权限。'
    ]
  },
  {
    code: 'material_accountant',
    name: '材料会计',
    position: '负责最终审核、发票核销和库存期间检查。',
    icon: Coin,
    responsibilities: [
      '审核采购订单、入库单、出库申请、材料出库单、退料单、转库单和采购发票。',
      '处理待办事项中的审批任务，审核完成后待办会自动移出。',
      '核对发票与入库、订单信息，确保数量、供应商和金额一致。',
      '按需要刷新库存参考数据，结果由后台按库存流水自动计算。'
    ],
    steps: [
      { title: '处理待办审批', text: '进入我的待办，打开待审批单据，核对来源单据、明细、数量、金额和备注后审批或驳回。' },
      { title: '审核采购链路', text: '采购订单是采购链路的源头单据；材料会计从采购订单开始审核，订单、入库和发票应保持供应商、物料和数量一致。' },
      { title: '核销发票', text: '进入采购发票和发票匹配核销，按供应商、日期、单据号筛选并完成匹配。' },
      { title: '录发票金额照实填', text: '录采购发票时「价税合计」可直接照发票填，系统以你填的为准倒算不含税金额与税额，三者永远精确相等。改过的行会标出来并显示系统原本算的是多少；改动超过 1% 会被拦下（几分钱的精度差远达不到 1%）。改了数量或单价后金额会跟着重算，手工填过的会红字提醒复核。' },
      { title: '每月确认采购成本差异已入账', text: '发票与入库的差额（暂估开票、货票同到同一规则）：公司还有库存的部分自动调库存单价，已领用的部分计入当期损益；采购退货按原入库价冲库存。进「报表中心 / 采购成本差异调整表」，上半部「进损益合计」就是做分录要的数（小额进其他业务成本，大额调对应工程成本）；在外部账务系统做完分录后，回来点「本月差异已入账」。⚠️ 这一步不做，月末结账会被拦住。若该月之后又补录了迟到的发票，会标成「有新增待补」并显示新增了多少，需要补做分录。' },
      { title: '月末结账对账', text: '进入“结账 / 月末结账”，一键对账检查；异常项点“去处理”跳到对应单据修正，零异常后才能结账锁定（结账后当月单据不可改，如需调整可“反结账”）。结存单价如需重算，到“库存 / 现存量”页点“重算结存单价”。' }
    ],
    entries: [
      { name: '我的待办', path: '工作台 / 我的待办', text: '集中处理所有待审批、待核销任务。' },
      { name: '发票匹配核销', path: '发票管理 / 发票匹配核销', text: '将采购发票与入库、订单进行匹配。' },
      { name: '月末结账', path: '结账 / 月末结账', text: '月末对账检查（单据是否都已审批、有无未处理差异），无异常才能结账锁定；可反结账。' },
      { name: '报表中心', path: '报表中心', text: '查看采购跟踪、暂估、收发存、出库汇总、出入库流水和工程材料计划等报表。' },
      { name: '采购成本差异调整表', path: '报表中心 / 采购成本差异调整表', text: '发票与入库差额、采购退货兜底差额的明细与按月汇总（总差额 / 进库存 / 进损益），做分录取数用；每月做完分录要在这里点「已入账」（仅财务可见）。' }
    ],
    notes: [
      '材料会计审核是业务链条的最后控制点，审批前必须核对关键字段和明细。',
      '审批已改为“一键通过”，点“审批”即通过、无需填意见；驳回时仍需填写明确原因，方便采购或仓管修改后再提交。',
      '取消审批（反审）会把单据“退回草稿”由发起人修改或删除，并自动冲回库存/成本；冲回单据不再计入现存量、收发存汇总和出入库流水。',
      '结账前应确认入库、出库、退料和发票数据已处理完成、当期无未审批单据。',
      '【2026-10-16 起】采购成本差异按集团手册口径：在库部分调库存单价、已领用部分计入当期损益；本月进损益的差异未在「采购成本差异调整表」点过「已入账」，月末结账会被拦住。',
      '点「本月差异已入账」的含义是「我已经在账务系统做完这笔分录」，不是「我看过了」——两者差之毫厘，谬以千里。'
    ]
  },
  {
    code: 'admin',
    name: '系统管理员',
    position: '负责账套、组织、用户、权限、基础资料和系统参数维护。',
    icon: Setting,
    responsibilities: [
      '维护组织账套、用户账号、角色权限和菜单授权。',
      '维护供应商、物料、仓库与库位、工程项目、审批流和单据编码规则。',
      '按组织分配基础资料，确保不同组织只能使用本组织范围内的数据。',
      '处理账号锁定、密码重置、正式启用初始化和系统参数配置。'
    ],
    steps: [
      { title: '初始化账套', text: '正式启用前进入正式启用初始化，仅保留 admin 管理员，再由管理员重新配置组织、角色和人员。' },
      { title: '配置组织和用户', text: '先建立组织账套，再创建用户并分配采购、仓管、材料会计或管理员角色。' },
      { title: '维护基础资料', text: '按组织维护供应商、物料、仓库、工程项目等资料，业务人员只能在授权范围内选择。' },
      { title: '维护权限和参数', text: '通过角色与权限分配菜单；通过系统参数维护出库类型、业务分类等全局选项。' }
    ],
    entries: [
      { name: '销售录单链接', path: '销售管理 / 销售录单链接', text: '【按公司开通】给每个网点生成一个二维码，贴店里或发微信群。网点扫码即可录销售单，录完自动推待办给公司确认。「重置链接」会让旧二维码立即失效。' },
      { name: '用户管理', path: '系统管理 / 用户管理', text: '新增用户、重置密码、解锁账号并分配角色。' },
      { name: '角色与权限', path: '系统管理 / 角色与权限', text: '控制每个角色可见菜单和可操作功能。' },
      { name: '组织架构', path: '系统管理 / 组织架构', text: '维护账套和组织层级；公司节点上可切换“审批模式”（简化/完整审批链）。' },
      { name: '基础资料', path: '基础资料', text: '维护供应商、物料、仓库、审批流、编码规则和工程项目。' },
      { name: '数据备份', path: '系统管理 / 数据备份', text: '把完整数据导出到本地：完整数据 Excel（可对接财务软件）+ 整库 SQL 备份（仅系统管理员）。建议定期导出留底。' }
    ],
    notes: [
      '系统管理员可查看全部模块与所有账套的单据、并能管理（删除）；新建/审批仍由采购、仓管、材料会计各司其职。',
      '基础资料应按组织维护，避免供应商、物料、工程项目跨组织误用。',
      '服务器每天自动整库备份（保留 90 天），但都在同一台机器；建议定期用“数据备份”导出到本地另存，防服务器整体故障。',
      '删除或初始化前必须确认账套状态，正式数据启用后优先使用停用或权限调整。'
    ]
  }
]

const rolePriority = ['admin', 'material_accountant', 'finance', 'warehouse', 'purchaser']
const currentRoles = computed(() => user.userInfo?.roles || [])
const isAdmin = computed(() => currentRoles.value.includes('admin'))
const defaultRole = computed(() => rolePriority.find(code => currentRoles.value.includes(code)) || 'purchaser')
const visibleGuides = computed(() => isAdmin.value ? guides : guides.filter(g => currentRoles.value.includes(g.code) || (g.code === 'material_accountant' && currentRoles.value.includes('finance'))))
const activeCode = ref(defaultRole.value)
const currentGuide = computed(() => visibleGuides.value.find(g => g.code === activeCode.value) || visibleGuides.value[0] || guides[0])
const heroTitle = computed(() => `${currentGuide.value?.name || '当前角色'}操作手册`)
const heroSub = computed(() => currentGuide.value?.position || '按当前角色展示职责和常用操作。')

watch(defaultRole, code => {
  activeCode.value = code
})

function printPage() {
  window.print()
}
</script>

<style scoped lang="scss">
.role-guide-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.guide-hero {
  min-height: 154px;
  border: 1px solid var(--border-soft);
  border-radius: 18px;
  padding: 28px 30px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  background:
    radial-gradient(circle at 7% 22%, rgba(29, 99, 176, .12), transparent 28%),
    var(--bg-panel);
  box-shadow: var(--shadow-md);

  .eyebrow {
    color: var(--text-3);
    font-size: 14px;
    margin-bottom: 8px;
  }

  h1 {
    margin: 0;
    font-size: 30px;
    line-height: 1.2;
    color: var(--text-1);
  }

  p {
    margin: 10px 0 0;
    color: var(--text-2);
    max-width: 680px;
  }
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.role-switch {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;

  button {
    border: 1px solid var(--border-soft);
    border-radius: 999px;
    height: 42px;
    padding: 0 16px;
    background: var(--bg-panel);
    color: var(--text-2);
    display: inline-flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    transition: all .18s ease;
  }

  button.active,
  button:hover {
    color: var(--color-primary);
    border-color: var(--color-primary);
    background: var(--color-primary-soft);
    box-shadow: var(--shadow-sm);
  }
}

.guide-layout {
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  gap: 18px;
}

.guide-summary,
.guide-card {
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  background: var(--bg-panel);
}

.guide-summary {
  padding: 24px;
  align-self: start;
  position: sticky;
  top: 88px;

  h2 {
    margin: 14px 0 6px;
    color: var(--text-1);
  }

  p {
    color: var(--text-2);
    line-height: 1.8;
  }
}

.role-badge {
  width: 58px;
  height: 58px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  font-size: 28px;
  color: #fff;
  background: linear-gradient(135deg, #2148b5, #29a38d);

  &.warehouse { background: linear-gradient(135deg, #178a73, #7fb13c); }
  &.finance,
  &.material_accountant { background: linear-gradient(135deg, #1e5d9d, #7a5bc8); }
  &.admin { background: linear-gradient(135deg, #5d6f10, #1f6aa5); }
}

.summary-line {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--border-soft);
  display: flex;
  justify-content: space-between;
  color: var(--text-2);

  strong {
    color: var(--text-1);
    font-size: 20px;
  }
}

.guide-content {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.guide-card {
  box-shadow: none;

  :deep(.el-card__header) {
    border-bottom-color: var(--border-soft);
    padding: 16px 18px;
  }

  :deep(.el-card__body) {
    padding: 18px;
  }
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  color: var(--text-1);
}

.clean-list {
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    position: relative;
    padding: 0 0 12px 18px;
    color: var(--text-2);
    line-height: 1.75;
  }

  li::before {
    content: "";
    position: absolute;
    left: 0;
    top: 11px;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #a4ce3d;
  }
}

.warning-list li::before {
  background: #d78a1c;
}

.steps {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.step-row {
  display: grid;
  grid-template-columns: 34px 1fr;
  gap: 12px;

  strong {
    color: var(--text-1);
  }

  p {
    margin: 5px 0 0;
    color: var(--text-2);
    line-height: 1.7;
  }
}

.step-no {
  width: 30px;
  height: 30px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #183a91;
  background: rgba(164, 206, 61, .20);
  font-weight: 700;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.entry-item {
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  padding: 14px;
  background: var(--bg-page);

  p {
    margin: 8px 0 0;
    color: var(--text-2);
    line-height: 1.6;
  }
}

.entry-name {
  font-weight: 700;
  color: var(--text-1);
}

.entry-path {
  margin-top: 4px;
  font-size: 13px;
  color: #1f58bd;
}

@media (max-width: 1180px) {
  .guide-layout,
  .guide-content {
    grid-template-columns: 1fr;
  }

  .guide-summary {
    position: static;
  }
}

@media print {
  .hero-actions,
  .role-switch {
    display: none;
  }

  .guide-layout,
  .guide-content {
    display: block;
  }

  .guide-card,
  .guide-summary {
    margin-bottom: 14px;
    break-inside: avoid;
  }
}
</style>
