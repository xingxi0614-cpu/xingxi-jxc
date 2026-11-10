<template>
  <div class="flow-guide">
    <section class="fg-hero">
      <h1>操作流程指引</h1>
      <p>按正确顺序操作，避免报错。每条业务“先做什么、再做什么”一看就懂；下方还有常见“顺序不对”提示的对策。</p>
    </section>

    <section class="fg-grid">
      <div v-for="flow in flows" :key="flow.title" class="fg-card">
        <div class="fg-card-head">
          <span class="fg-badge" :class="flow.tone">{{ flow.badge }}</span>
          <h2>{{ flow.title }}</h2>
        </div>
        <div class="fg-steps">
          <template v-for="(s, i) in flow.steps" :key="i">
            <div class="fg-step">
              <span class="fg-no">{{ i + 1 }}</span>
              <div>
                <div class="fg-step-t">{{ s.t }}</div>
                <div class="fg-step-d">{{ s.d }}</div>
              </div>
            </div>
            <div v-if="i < flow.steps.length - 1" class="fg-arrow">↓</div>
          </template>
        </div>
        <div v-if="flow.tip" class="fg-tip">💡 {{ flow.tip }}</div>
      </div>
    </section>

    <section class="fg-tips">
      <h2>常见“顺序不对”提示 → 该先做什么</h2>
      <div class="fg-tip-row" v-for="t in wrongTips" :key="t.msg">
        <div class="fg-tip-msg">⚠ {{ t.msg }}</div>
        <div class="fg-tip-fix">→ {{ t.fix }}</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
const flows = [
  {
    badge: '采购', tone: 'blue', title: '采购到入库',
    steps: [
      { t: '录入采购订单', d: '采购员填供应商、仓库、下单日期、物料明细（录含税单价，系统算出含税金额并拆不含税/税额；单价偏离历史价过大会提示核对）。' },
      { t: '提交并审批', d: '提交后由材料会计等岗位审批；审批通过订单才能入库。' },
      { t: '下推入库（仓管）', d: '仓管在采购入库单点“下推入库”，选行、填本次入库数量（支持分批）。' },
      { t: '入库审批', d: '入库单审批通过，库存实时增加、结存单价按加权平均更新。' },
      { t: '发票核销（可选）', d: '暂估入库到票后，财务做发票核销：认证进项税、暂估转正。' }
    ],
    tip: '订单没审批通过，不能入库；一定先把采购订单审批了再下推。'
  },
  {
    badge: '出库', tone: 'rose', title: '材料出库（领用/发料）',
    steps: [
      { t: '出库申请', d: '填工程项目、出库类型、业务分类、材料申请人、领用明细，提交审批。' },
      { t: '出库申请审批', d: '审批通过后，该申请才能被仓管关联制单发料。' },
      { t: '关联制单（仓管）', d: '仓管在材料出库点“关联出库申请制单”，选已审批的申请、填本次出库数量。' },
      { t: '出库审批（发料）', d: '出库单审批通过，库存实时减少——这一步才真正扣库存。' }
    ],
    tip: '出库申请没审批，不能制单发料；库存不足时先入库或减少出库数量。'
  },
  {
    badge: '新', tone: 'amber', title: '领料申请（在线提报，免登录链接）',
    steps: [
      { t: '一线提交', d: '打开公司链接选“领料申请”，选领料人/工程项目/出库类型/业务分类，搜物料填申领数量。' },
      { t: '采购确认', d: '进采购的“领料申请”收件箱，采购确认。' },
      { t: '仓管确认 → 财务确认', d: '依次确认；三方通过后自动生成“已审批”的出库申请。' },
      { t: '打印+发料', d: '打印《工程材料计划表》；仓管在材料出库对生成的申请发料（扣库存）。' }
    ],
    tip: '提交只是计划、不动库存；只有最后仓管实际发料才扣库存。'
  },
  {
    badge: '新', tone: 'green', title: '采购计划（在线提报，免登录链接）',
    steps: [
      { t: '业务提报', d: '打开公司链接选“采购计划”，填提报人/部门，搜物料填需求数量（可看当前/最低库存）。' },
      { t: '采购确认', d: '进采购的“采购计划”，看建议采购量（=需求+最低库存−当前库存），逐行定最终采购量后确认。' },
      { t: '打印计划表', d: '打印《采购计划表》（默认横版），采购据此去录采购订单下单。' }
    ],
    tip: '采购计划只是参考，不生成订单、不动库存；结合库存定量、避免盲目采购。'
  },
  {
    badge: '库存', tone: 'gray', title: '盘点与月末结账',
    steps: [
      { t: '日常单据先处理完', d: '把当月采购入库、出库、退料、转库等单据都审批到位。' },
      { t: '对账检查', d: '月末结账前先做对账检查，确认没有异常（负库存、未审批遗留等）。' },
      { t: '结账并锁定', d: '零异常才能结账；结账后该期间锁定，不能再改历史单据（需反结账才能改）。' }
    ],
    tip: '结账前务必处理完所有在途单据，否则会被拦下。'
  }
]

const wrongTips = [
  { msg: '“订单未审批或已关闭，不能入库”', fix: '先到【采购订单】提交并完成审批，再回采购入库单“下推入库”。' },
  { msg: '“出库申请未审批 / 无可用申请”', fix: '先到【出库申请】提交并审批；已审批的申请才能在材料出库里关联制单。' },
  { msg: '“库存不足”', fix: '先完成对应物料的入库，或把出库数量改到不超过当前库存。' },
  { msg: '“只有草稿或已驳回的单据可以修改/删除”', fix: '已提交/已审批的单据不能直接改；先“取消提交/取消审批”退回草稿，或新建调整单。' },
  { msg: '“工程项目/供应商 下拉是空的”', fix: '先由账套管理员在【物料/供应商/工程项目档案】里维护并分配到本公司。' },
  { msg: '“当前期间已结账/已锁定”', fix: '该月已结账，历史单不能改；如确需调整，先做“反结账”再处理。' }
]
</script>

<style scoped>
.flow-guide { max-width: 1100px; margin: 0 auto; padding: 8px 4px 40px; }
.fg-hero { text-align: center; padding: 18px 0 10px; }
.fg-hero h1 { font-size: 24px; font-weight: 800; color: #1d63b0; margin: 0 0 8px; }
.fg-hero p { color: #6b7280; font-size: 14px; max-width: 720px; margin: 0 auto; line-height: 1.7; }
.fg-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; margin-top: 16px; }
.fg-card { background: #fff; border: 1px solid #eef1f5; border-radius: 16px; padding: 16px 18px; box-shadow: 0 6px 18px rgba(15,23,42,.05); }
.fg-card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.fg-card-head h2 { font-size: 16px; font-weight: 700; color: #1f2937; margin: 0; }
.fg-badge { font-size: 12px; font-weight: 700; color: #fff; border-radius: 999px; padding: 2px 10px; }
.fg-badge.blue { background: #1d63b0; } .fg-badge.rose { background: #c0392b; } .fg-badge.amber { background: #b7791f; } .fg-badge.green { background: #2f9e6f; } .fg-badge.gray { background: #6b7280; }
.fg-steps { display: flex; flex-direction: column; }
.fg-step { display: flex; gap: 10px; align-items: flex-start; }
.fg-no { flex: 0 0 24px; height: 24px; border-radius: 50%; background: #eef4fc; color: #1d63b0; font-weight: 700; font-size: 13px; display: flex; align-items: center; justify-content: center; }
.fg-step-t { font-weight: 600; color: #1f2937; font-size: 14px; }
.fg-step-d { color: #6b7280; font-size: 12.5px; line-height: 1.6; margin-top: 2px; }
.fg-arrow { color: #c3ccd8; margin: 2px 0 2px 11px; font-size: 13px; }
.fg-tip { margin-top: 12px; background: #f6f9fe; border-radius: 10px; padding: 9px 12px; color: #4b5563; font-size: 12.5px; line-height: 1.6; }
.fg-tips { margin-top: 24px; background: #fff; border: 1px solid #eef1f5; border-radius: 16px; padding: 18px; }
.fg-tips h2 { font-size: 16px; font-weight: 700; color: #1f2937; margin: 0 0 12px; }
.fg-tip-row { display: flex; flex-wrap: wrap; gap: 6px 14px; padding: 9px 0; border-bottom: 1px dashed #eef1f5; }
.fg-tip-row:last-child { border-bottom: 0; }
.fg-tip-msg { color: #c0392b; font-size: 13px; font-weight: 600; flex: 1 1 320px; }
.fg-tip-fix { color: #374151; font-size: 13px; flex: 2 1 380px; }
</style>
