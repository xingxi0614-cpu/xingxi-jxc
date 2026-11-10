<template>
  <div class="mob">
    <!-- 顶部导航:公司 logo + 公司名 -->
    <header class="mob-head">
      <div class="mob-head-bar">
        <div class="mob-head-l">
          <img src="/logo-mark.svg" alt="" class="mob-head-logo" />
          <!-- 能切换公司的账号:标题直接写"现在看的是哪家",不写账号自己挂的公司(两者并列会让人看不懂到底在看谁) -->
          <span class="mob-head-name">{{ viewCompany.switchable ? (viewCompany.selectedName || '全部公司') : (companyName || '星嘻进销存') }}</span>
          <!-- 能看多家公司的账号(系统管理员):显示现在看的是哪家,点一下切换(和电脑端顶栏同一个开关,2026-10-20) -->
          <el-dropdown v-if="viewCompany.switchable" trigger="click" @command="(id: number | '') => viewCompany.select(id)">
            <button type="button" class="mob-view-org" :class="{ 'is-narrowed': !!viewCompany.selectedId }">
              切换 ▾
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="" :class="{ 'is-current': !viewCompany.selectedId }">全部公司</el-dropdown-item>
                <el-dropdown-item v-for="c in viewCompany.companies" :key="c.id" :command="c.id"
                  :class="{ 'is-current': String(c.id) === viewCompany.selectedId }">{{ c.name }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="mob-head-avatar" @click="tab = 'me'">{{ avatarDisplay }}</div>
      </div>
    </header>
    <!-- 滚动通知:只在工作台首页(审批页签)显示一条细条,进其他页签不显示,省屏幕(用户 10-21 定) -->
    <div v-if="tab === 'todo'" class="mob-ticker"><NoticeTicker /></div>

    <main class="mob-body">
      <!-- 页签切换过渡：保持各 section 的 v-if 不变，外包一层按 tab 换 key 的容器 -->
      <transition name="mob-fade" mode="out-in">
        <div :key="tab" class="mob-tab-pane">
      <!-- ===== 审批 ===== -->
      <section v-if="tab === 'todo'" class="mob-page">
        <div class="mob-page-head">
          <div class="mob-page-title">{{ greeting }}，{{ user.userInfo?.name || '' }}</div>
          <div class="mob-page-sub">{{ reviewTodos.length ? `有 ${reviewTodos.length} 张单据等你审批` : '当前没有待审批的单据' }}</div>
        </div>
        <!-- 三个数字各占一块（2026-10-07 借鉴 OA 手机端首页）：点哪块，下面的列表就换成哪类 -->
        <div class="mx-tiles">
          <button class="mx-tile" :class="{ 'is-on': todoSeg === 'review', 'is-hot': reviewTodos.length }" @click="todoSeg = 'review'">
            <b>{{ reviewTodos.length }}</b><small>待我审</small>
          </button>
          <button class="mx-tile" :class="{ 'is-on': todoSeg === 'rejected', 'is-warn': myRejected.length }" @click="todoSeg = 'rejected'">
            <b>{{ myRejected.length }}</b><small>我的退回</small>
          </button>
          <button class="mx-tile" :class="{ 'is-on': todoSeg === 'draft' }" @click="todoSeg = 'draft'">
            <b>{{ myDrafts.length }}</b><small>我的草稿</small>
          </button>
        </div>
        <MobileAlerts ref="alertsRef" class="mob-home-alerts" @open-docs="openDocsFromAlert" @open-approval="todoSeg = 'review'; loadTodos()" />

        <!-- 快捷发起：能建的单直接摆在首页，少点一次「新增」。和「新增」页同一份权限清单 -->
        <div v-if="quickActions.length" class="mx-g mob-home-g">
          <div class="mx-g__label"><b>快捷发起</b></div>
          <div class="mx-g__box">
            <div class="mx-quick">
              <button v-for="a in quickActions" :key="a.label" @click="a.all ? (tab = 'create') : goCreate(a)">
                <span class="mob-ico-chip" :class="a.tint"><component :is="a.icon" :size="21" :stroke-width="1.7" /></span>
                {{ a.label }}
              </button>
            </div>
          </div>
        </div>

        <div class="mx-g mob-home-g">
          <div class="mx-g__label"><b>{{ todoSegTitle }}</b><template v-if="!loading && todoSegCount">{{ todoSegCount }} 项<template v-if="todoSeg === 'review'"> · 点开查看后审批</template></template></div>

          <div v-if="loading" class="mx-g__box">
            <div v-for="n in 3" :key="n" class="mx-row mx-row--app">
              <span class="mob-skel mob-skel-ico"></span>
              <div class="mob-skel-lines">
                <span class="mob-skel mob-skel-line w60"></span>
                <span class="mob-skel mob-skel-line w40"></span>
              </div>
            </div>
          </div>

          <div v-else-if="todoSeg === 'review'" class="mx-g__box">
            <div v-if="reviewTodos.length === 0" class="mx-tip"><b>全部处理完了</b>没有等你审批的单据</div>
            <!-- 2026-10-09 去掉了列表上的「通过 / 驳回」：不点开就能批，等于没看单据就签字。
                 现在必须点开这一行，在审批面板里看过流程（可再点「查看单据明细」）才能通过或驳回 -->
            <button v-for="t in reviewTodos" class="mx-row mx-row--app" :key="t.id" @click="openReview(t)">
              <span class="mob-ico-chip" :class="tintOf(t.bizType)"><component :is="iconOf(t.bizType)" :size="20" :stroke-width="1.7" /></span>
              <span class="mx-row__tt">
                <b>{{ typeLabel(t.bizType) }}</b>
                <span>{{ t.title }}<template v-if="t.orgName"> · {{ t.orgName }}</template></span>
                <span>{{ t.bizNo }}</span>
              </span>
              <span class="mx-row__r"><b v-if="t.amtText">{{ t.amtText }}</b>{{ t.time }}</span>
              <ArrowRight class="mx-row__chev" :size="16" />
            </button>
          </div>

          <div v-else class="mx-g__box">
            <div v-if="currentMyDocs.length === 0" class="mx-tip">{{ todoSeg === 'rejected' ? '没有被退回的单据' : '没有草稿单据' }}</div>
            <button v-for="d in currentMyDocs" :key="d.bizType + d.bizId" class="mx-row mx-row--app" @click="openMyDoc(d)">
              <span class="mob-ico-chip" :class="d.status === 'REJECTED' ? 'red' : 'gray'"><component :is="iconOf(d.bizType)" :size="20" :stroke-width="1.7" /></span>
              <span class="mx-row__tt">
                <b>{{ typeLabel(d.bizType) }}</b>
                <span>{{ d.bizNo }}</span>
              </span>
              <!-- 日期单独靠右：和单号挤一行会被省略号吃掉 -->
              <span v-if="d.bizDate" class="mx-row__r">{{ (d.bizDate || '').slice(5, 10) }}</span>
              <ArrowRight class="mx-row__chev" :size="16" />
            </button>
          </div>
        </div>
      </section>

      <!-- ===== 新增(宫格) ===== -->
      <section v-if="tab === 'create'" class="mob-page">
        <div class="mob-page-head">
          <div class="mob-page-title">新增单据</div>
          <div class="mob-page-sub">选择要办理的业务</div>
        </div>
        <div class="mob-grid2">
          <button v-for="(a, i) in createActions" :key="a.label" class="mob-grid-card mob-rise" :style="{ animationDelay: `min(${i * 40}ms, 320ms)` }" @click="goCreate(a)">
            <span class="mob-ico-big" :class="a.tint"><component :is="a.icon" :size="26" :stroke-width="1.6" /></span>
            <span class="mob-grid-label">{{ a.label }}</span>
            <span class="mob-grid-desc">{{ a.desc }}</span>
          </button>
        </div>
      </section>

      <!-- ===== 单据(两级:宫格 → 列表) ===== -->
      <section v-if="tab === 'docs'" class="mob-page">
        <!-- 一级:类型宫格 -->
        <template v-if="docView === 'grid'">
          <div class="mob-page-head">
            <div class="mob-page-title">单据查询</div>
            <div class="mob-page-sub">选择单据类型查看</div>
          </div>
          <div class="mob-grid2">
            <button v-for="(d, i) in docTypes" :key="d.key" class="mob-grid-card mob-rise" :style="{ animationDelay: `min(${i * 40}ms, 320ms)` }" @click="enterDocType(d.key)">
              <span class="mob-ico-big" :class="tintOf(d.key)"><component :is="iconOf(d.key)" :size="26" :stroke-width="1.6" /></span>
              <span class="mob-grid-label">{{ d.label }}</span>
            </button>
          </div>
        </template>

        <!-- 二级:该类型列表 -->
        <template v-else>
          <div class="mob-list-head">
            <button class="mob-back" @click="docView = 'grid'">‹ 返回</button>
            <span class="mob-list-title">{{ typeLabel(docType) }}</span>
          </div>
          <div class="mob-search">
            <component :is="Search" :size="18" class="mob-search-ico" />
            <input v-model="docKeyword" class="mob-input" placeholder="搜索单号" @keyup.enter="loadDocs(docType)" />
            <button v-if="docKeyword" class="mob-search-clear" @click="docKeyword = ''; loadDocs(docType)">✕</button>
          </div>
          <!-- 状态 + 日期筛选 -->
          <div class="mob-filter-row">
            <button v-for="s in statusOptions" :key="s.value" class="mob-chip" :class="{ active: docStatus === s.value }"
              @click="docStatus = s.value; loadDocs(docType)">{{ s.label }}</button>
          </div>
          <div class="mob-date-row">
            <input v-model="docStart" type="date" class="mob-date" @change="loadDocs(docType)" />
            <span class="mob-date-sep">至</span>
            <input v-model="docEnd" type="date" class="mob-date" @change="loadDocs(docType)" />
            <button v-if="docStart || docEnd" class="mob-search-clear static" @click="docStart = ''; docEnd = ''; loadDocs(docType)">✕</button>
          </div>
          <div v-if="docLoading" class="mob-skel-list">
            <div v-for="n in 4" :key="n" class="mob-skel-card slim">
              <div class="mob-skel-lines">
                <span class="mob-skel mob-skel-line w50"></span>
                <span class="mob-skel mob-skel-line w30"></span>
              </div>
            </div>
          </div>
          <div v-else-if="docList.length === 0" class="mob-empty">
            <span class="mob-empty-ico">🗂</span>
            <div class="mob-empty-title">{{ docKeyword ? '没有匹配的单据' : '暂无单据' }}</div>
          </div>
          <!-- 一张单一张卡：状态 + 单号 / 跟谁有关 / 日期、经手人、数量、金额。字段取舍见 parts/docCard.ts -->
          <button v-for="row in docList" :key="row.id" type="button" class="mx-card mob-doc-card" @click="openDoc(row)">
            <div class="mx-card__top">
              <span class="mob-doc-status" :class="'st-' + (row.status || '').toLowerCase()">{{ statusText(row.status) }}</span>
              <span class="mx-card__no">{{ row.docNo || row.id }}</span>
              <span v-if="docCardAmount(row)" class="mob-doc-amt">{{ docCardAmount(row) }}</span>
            </div>
            <div v-if="docCardTitle(docType, row, docLookups)" class="mx-card__title">{{ docCardTitle(docType, row, docLookups) }}</div>
            <div v-if="docCardMaterials(row)" class="mob-doc-mat">{{ docCardMaterials(row) }}</div>
            <div class="mx-card__meta">
              <span>{{ String(docDate(row)).slice(0, 10) }}</span>
              <span v-for="m in docCardMetas(docType, row)" :key="m">{{ m }}</span>
            </div>
          </button>
        </template>
      </section>

      <!-- ===== 库存(搜索优先) ===== -->
      <section v-if="tab === 'stock'" class="mob-page">
        <div class="mob-page-head">
          <div class="mob-page-title">库存查询</div>
          <div class="mob-page-sub">{{ stockSeg === 'balance' ? '输入物料名称或编码查询现存量' : '看某个料这段时间进了多少、出了多少' }}</div>
        </div>

        <!-- 现存量 / 进出记录：各自按权限显示，只有一种权限时不摆分段条 -->
        <div v-if="canViewBalance && canViewTxn" class="mob-seg">
          <button class="mob-seg-btn" :class="{ active: stockSeg === 'balance' }" @click="switchStockSeg('balance')">现存量</button>
          <button class="mob-seg-btn" :class="{ active: stockSeg === 'txn' }" @click="switchStockSeg('txn')">进出记录</button>
        </div>

        <!-- 仓库下拉：两段共用 -->
        <div class="mob-filter">
          <el-select v-model="whFilter" placeholder="全部仓库" clearable filterable size="large" style="width:100%"
            @change="stockSeg === 'txn' && loadTxn()">
            <el-option v-for="w in warehouseOptions" :key="w.id" :value="w.id" :label="w.name" />
          </el-select>
        </div>

        <!-- ── 进出记录 ── -->
        <template v-if="canViewTxn && stockSeg === 'txn'">
          <div class="mob-filter-row">
            <el-date-picker v-model="txnStart" type="date" placeholder="开始日期" value-format="YYYY-MM-DD"
              size="large" style="flex:1" @change="loadTxn" />
            <span class="mob-filter-sep">至</span>
            <el-date-picker v-model="txnEnd" type="date" placeholder="结束日期" value-format="YYYY-MM-DD"
              size="large" style="flex:1" @change="loadTxn" />
          </div>
          <div v-if="txnMatLabel" class="mob-picked-mat">
            只看：<b>{{ txnMatLabel }}</b>
            <button class="mob-picked-clear" @click="clearTxnMat">看全部 ✕</button>
          </div>

          <div v-if="txnLoading" class="mob-skel-list">
            <div v-for="n in 4" :key="n" class="mob-skel-card slim">
              <div class="mob-skel-lines">
                <span class="mob-skel mob-skel-line w70"></span>
                <span class="mob-skel mob-skel-line w40"></span>
              </div>
            </div>
          </div>
          <div v-else-if="txnRows.length === 0" class="mob-empty">
            <span class="mob-empty-ico">📋</span>
            <div class="mob-empty-title">这段时间没有进出记录</div>
            <div class="mob-empty-sub">换个日期范围或仓库再看看</div>
          </div>
          <div v-else v-for="(r, i) in txnRows" :key="i" class="mob-txn-card mob-rise" :style="{ animationDelay: `min(${i * 40}ms, 320ms)` }">
            <div class="mob-txn-top">
              <span class="mob-txn-mat">{{ r.matCode }} {{ r.matName }}<span v-if="r.matSpec">({{ r.matSpec }})</span></span>
              <span class="mob-txn-qty" :class="txnDir(r)">{{ Number(r.qty) >= 0 ? '+' : '' }}{{ r.qty }}</span>
            </div>
            <div class="mob-txn-sub">
              <span class="mob-tag">{{ txnTypeLabel(r) }}</span>
              <span class="mob-txn-no">{{ r.bizDocNo || '' }}</span>
              <span class="mob-txn-wh">{{ r.whName || '' }}</span>
            </div>
            <div class="mob-txn-sub dim">
              {{ r.bizDate || '' }}
              <span v-if="r.projectName"> · {{ r.projectName }}</span>
              <span v-if="r.supplierName"> · {{ r.supplierName }}</span>
              <span v-if="r.balQty != null"> · 结存 {{ r.balQty }}</span>
            </div>
          </div>
        </template>

        <!-- ── 现存量 ── -->
        <template v-else-if="canViewBalance">
        <div class="mob-search">
          <component :is="Search" :size="18" class="mob-search-ico" />
          <input v-model="keyword" class="mob-input" placeholder="物料名称 / 编码 / 规格" @input="onStockInput" />
          <button v-if="keyword" class="mob-search-clear" @click="keyword = ''; onStockInput()">✕</button>
        </div>

        <!-- 未输入:引导 + 最近搜索 -->
        <template v-if="!keyword.trim()">
          <div v-if="recentSearches.length" class="mob-recent">
            <div class="mob-recent-title">最近搜索</div>
            <div class="mob-recent-chips">
              <button v-for="k in recentSearches" :key="k" class="mob-chip" @click="keyword = k; onStockInput()">{{ k }}</button>
            </div>
          </div>
          <div class="mob-empty airy">
            <span class="mob-empty-ico">🔍</span>
            <div class="mob-empty-title">搜索物料查库存</div>
            <div class="mob-empty-sub">按名称、编码或规格搜索,结果实时显示</div>
          </div>
        </template>

        <template v-else>
          <div v-if="stockLoading" class="mob-skel-list">
            <div v-for="n in 4" :key="n" class="mob-skel-card slim">
              <div class="mob-skel-lines">
                <span class="mob-skel mob-skel-line w60"></span>
                <span class="mob-skel mob-skel-line w40"></span>
              </div>
            </div>
          </div>
          <div v-else-if="shownStock.length === 0" class="mob-empty">
            <span class="mob-empty-ico">🤔</span>
            <div class="mob-empty-title">没有找到匹配的物料</div>
          </div>
          <template v-else>
            <div v-for="(row, i) in shownStockFinal" :key="row.matCode + row.whName" class="mob-stock-card tap mob-rise" :style="{ animationDelay: `min(${i * 40}ms, 320ms)` }" @click="openTxnOf(row)">
              <div class="mob-stock-left">
                <div class="mob-stock-name">{{ row.matName }}</div>
                <div class="mob-stock-sub">{{ row.matCode }}<span v-if="row.spec">({{ row.spec }})</span> · {{ row.whName }}</div>
              </div>
              <div class="mob-stock-right">
                <span class="mob-stock-qty">{{ row.qty }}</span>
                <span class="mob-stock-unit">{{ row.unit }}</span>
              </div>
              <span class="mob-card-arrow">›</span>
            </div>
            <div v-if="stockOverflow" class="mob-more-hint">仅显示前 50 条,请输入更精确的关键词</div>
          </template>
        </template>
        </template>
      </section>

      <!-- ===== 我的 ===== -->
      <section v-if="tab === 'me'" class="mob-page">
        <div class="mob-me-card">
          <div class="mob-me-avatar">{{ avatarDisplay }}</div>
          <div class="mob-me-meta">
            <div class="mob-me-name">{{ user.userInfo?.name }}</div>
            <div class="mob-me-badges">
              <span class="mob-me-role">{{ roleLabel }}</span>
            </div>
            <div class="mob-me-org">{{ user.userInfo?.orgName }}</div>
          </div>
        </div>

        <div class="mob-group-title">账户</div>
        <div class="mob-me-list">
          <div class="mob-me-item" @click="showProfile">
            <span class="mob-me-ico blue"><component :is="User" :size="17" /></span><span>个人信息</span><span class="mob-me-arrow">›</span>
          </div>
          <div class="mob-me-item" @click="showPassword">
            <span class="mob-me-ico green"><component :is="Lock" :size="17" /></span><span>修改密码</span><span class="mob-me-arrow">›</span>
          </div>
        </div>

        <div class="mob-group-title">通用</div>
        <div class="mob-me-list">
          <button type="button" class="mob-me-item mob-manual-entry" @click="openReleaseNotes">
            <span class="mob-me-ico blue"><component :is="DocumentCopy" :size="17" /></span><span>系统更新</span><span class="mob-me-arrow">›</span>
          </button>
          <div class="mob-me-item" @click="switchToDesktop">
            <span class="mob-me-ico purple"><component :is="Monitor" :size="17" /></span><span>切换到电脑版</span><span class="mob-me-arrow">›</span>
          </div>
          <div class="mob-me-item static">
            <span class="mob-me-ico gray"><component :is="Setting" :size="17" /></span><span>当前版本</span><span class="mob-me-val">v{{ appVersion }}</span>
          </div>
        </div>

        <button class="mob-logout-btn" @click="logout"><component :is="SwitchButton" :size="17" /> 退出登录</button>
      </section>
        </div>
      </transition>
    </main>

    <!-- 底部导航(中央凸起新增钮) -->
    <nav class="mob-nav">
      <template v-for="t in visibleTabs" :key="t.key">
        <button v-if="t.key === 'create'" class="mob-nav-fab" @click="tab = 'create'" :class="{ active: tab === 'create' }">
          <component :is="Plus" :size="26" :stroke-width="2.2" />
        </button>
        <button v-else class="mob-nav-btn" :class="{ active: tab === t.key }" @click="tab = t.key">
          <span class="mob-nav-ico"><component :is="t.icon" :size="21" :stroke-width="tab === t.key ? 2 : 1.6" /></span>
          <span class="mob-nav-label">{{ t.label }}</span>
          <span v-if="t.key === 'todo' && reviewTodos.length" class="mob-nav-badge">{{ reviewTodos.length > 99 ? '99+' : reviewTodos.length }}</span>
        </button>
      </template>
    </nav>

    <!-- 审批弹窗 -->
    <Teleport to="body">
      <div v-if="reviewVisible" class="mob-modal" @click.self="reviewVisible = false">
        <div class="mob-modal-card">
          <div class="mob-modal-grip"></div>
          <div class="mob-modal-title">{{ reviewItem?.title }}</div>
          <div class="mob-review-meta">
            <span class="mob-tag">{{ typeLabel(reviewItem?.bizType) }}</span>{{ reviewItem?.bizNo }}
          </div>

          <div v-if="reviewItem?.bizType !== 'CANCEL_REQ'" class="mob-review-flow">
            <div v-if="reviewFlowLoading" class="mob-flow-loading">审批流加载中…</div>
            <template v-else-if="reviewFlow && reviewFlow.steps && reviewFlow.steps.length">
              <div v-if="reviewFlow.applicant" class="mob-flow-appl">发起：{{ reviewFlow.applicant }}<span v-if="reviewFlow.submittedAt"> · {{ fmtFlowTime(reviewFlow.submittedAt) }}</span></div>
              <div v-for="(s, i) in reviewFlow.steps" :key="i" class="mob-flow-step" :class="String(s.status || '').toLowerCase()">
                <span class="mob-flow-dot"></span>
                <div class="mob-flow-body">
                  <div class="mob-flow-name">{{ s.name || s.role }}<span class="mob-flow-badge">{{ stepLabel(s.status) }}</span></div>
                  <div v-if="s.approver || s.comment" class="mob-flow-sub">{{ s.approver }}<span v-if="s.comment">：{{ s.comment }}</span><span v-if="s.time" class="mob-flow-time"> · {{ fmtFlowTime(s.time) }}</span></div>
                </div>
              </div>
            </template>
            <div v-else class="mob-flow-loading">单节点审核，无多级审批流</div>
            <button class="mob-btn ghost mob-review-detail" @click="openDocDetail(reviewItem)">查看单据明细 →</button>
          </div>

          <textarea v-model="reviewComment" class="mob-textarea" placeholder="审批意见（通过可留空，驳回必须写原因）" rows="3"></textarea>
          <div class="mob-modal-actions">
            <button class="mob-btn no" @click="reviewVisible = false">取消</button>
            <button class="mob-btn ko" @click="doReview(false)">驳回</button>
            <button class="mob-btn ok solid" @click="doReview(true)">通过</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 个人信息弹窗 -->
    <el-dialog v-model="profileVisible" title="个人信息" width="92%" append-to-body>
      <el-form :model="profileForm" label-width="72px" size="large">
        <el-form-item label="姓名"><el-input v-model="profileForm.name" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="profileForm.phone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="profileForm.email" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码弹窗 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="92%" append-to-body>
      <el-form :model="pwdForm" label-width="72px" size="large">
        <el-form-item label="原密码"><el-input v-model="pwdForm.oldPassword" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少6位，字母+数字" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" @click="savePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import NoticeTicker from '@/components/NoticeTicker.vue'
import MobileAlerts from './MobileAlerts.vue'
import { docCardAmount, docCardMaterials, docCardMetas, docCardTitle, type DocLookups } from './parts/docCard'
import { computed, markRaw, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { notifApi, poApi, recvApi, outApi, invoiceApi, wfApi, inventoryApi, salesApi, masterApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useViewCompanyStore } from '@/stores/viewCompany'
import { setForceDesktop } from '@/utils/device'
import { fmtInventoryTransType } from '@/utils/format'
import { DocumentChecked, Plus, Tickets, Box, User, ShoppingCart, Promotion, Van, DocumentCopy, Search, Lock, Monitor, Setting, SwitchButton, Money, RefreshRight, Connection, ArrowRight, Grid } from '@/icons'

declare const __APP_VERSION__: string
const appVersion = __APP_VERSION__

const router = useRouter()
const user = useUserStore()
const tab = ref('todo')
function openReleaseNotes() { window.dispatchEvent(new Event('jxc-release-notes-open')) }
const alertsRef = ref<InstanceType<typeof MobileAlerts> | null>(null)
const loading = ref(false)
const todos = ref<any[]>([])
const reviewVisible = ref(false)
const reviewItem = ref<any>(null)
const reviewComment = ref('')
const reviewFlow = ref<any>(null)
const reviewFlowLoading = ref(false)
const myDocs = ref<any[]>([])
const todoSeg = ref<'review' | 'rejected' | 'draft'>('review')
// 待我审 = 真正等我审的单据(查单据状态) + 取消审批申请(它不走 wf_instance,只能从通知来)
//
// 🔴 2026-09-15 改：原先整个列表是从**通知**里筛出来的。通知是一次性消息、单据待审是持续状态——
// 消息被读掉、被挤出前 50 条、或某类单据提交时压根没发通知,手机上就再也看不到那张单,
// 可电脑上进单据列表筛「审批中」它还在,这就是用户反馈的"手机和电脑对不上"。
// 现在主列表改查 /workflow/pending-approvals(后端逐条用 canApprove 复核,与详情页按钮同一把尺子)。
const pendingDocs = ref<any[]>([])
const cancelReqTodos = computed(() => todos.value.filter((t: any) => t.bizType === 'CANCEL_REQ'))
const reviewTodos = computed(() => [
  ...cancelReqTodos.value,
  ...pendingDocs.value.map(normalizePending)
])

/** 把「待我审」接口的单据整成卡片要的字段(标题按结构化字段拼,不从通知标题里猜) */
function normalizePending(d: any) {
  const amt = d.totalAmt
  return {
    id: `${d.bizType}-${d.bizId}`,
    bizType: d.bizType,
    bizId: d.bizId,
    bizNo: d.bizNo,
    title: d.applicantName ? `${d.applicantName} 提交` : '待你审批',
    // 金额单独放，右侧醒目显示 —— 审批人第一眼要看的就是这个，
    // 塞进标题里会被挤成省略号（第一版就是这么翻车的）
    amtText: amt != null && amt !== '' ? `¥${fmtAmt(amt)}` : '',
    orgName: d.orgName || '',
    time: waitedFor(d.startedAt),
    _doc: true
  }
}
function fmtAmt(v: any) {
  const n = Number(v)
  if (!isFinite(n)) return String(v)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
/** 压了多久没人批——比显示提交时刻更有用 */
function waitedFor(t: string) {
  if (!t) return ''
  const d = new Date(String(t).replace(' ', 'T'))
  if (isNaN(d.getTime())) return ''
  const mins = Math.floor((Date.now() - d.getTime()) / 60000)
  if (mins < 60) return `${Math.max(mins, 1)} 分钟前`
  if (mins < 1440) return `${Math.floor(mins / 60)} 小时前`
  return `${Math.floor(mins / 1440)} 天前`
}
const myRejected = computed(() => myDocs.value.filter((d: any) => d.status === 'REJECTED'))
const myDrafts = computed(() => myDocs.value.filter((d: any) => d.status === 'DRAFT'))
const currentMyDocs = computed(() => (todoSeg.value === 'rejected' ? myRejected.value : myDrafts.value))
const todoSegTitle = computed(() => ({ review: '待我审批', rejected: '我的退回', draft: '我的草稿' }[todoSeg.value]))
const todoSegCount = computed(() => (todoSeg.value === 'review' ? reviewTodos.value.length : currentMyDocs.value.length))

const profileVisible = ref(false)
const pwdVisible = ref(false)
const profileForm = reactive({ name: '', phone: '', email: '' })
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

const companyName = computed(() => user.userInfo?.companyName || '')
const ROLE_LABELS: Record<string, string> = { admin: '管理员', account_admin: '账套管理员', material_accountant: '材料会计', finance: '材料会计', warehouse: '仓管员', purchaser: '采购员', field_manager: '现场管理员', service_staff: '网点客服' }
const ROLE_PRIORITY = ['admin', 'account_admin', 'material_accountant', 'finance', 'warehouse', 'purchaser', 'field_manager', 'service_staff']
const roleLabel = computed(() => { for (const r of ROLE_PRIORITY) if (user.roles.includes(r)) return ROLE_LABELS[r]; return '用户' })
// 头像：与桌面端同一套 emoji 映射
const avatarEmojis: Record<string, string> = {
  boy: '👦', girl: '👧', man: '👨', woman: '👩',
  cat: '🐱', dog: '🐶', rabbit: '🐰', panda: '🐼', fox: '🦊',
  bear: '🐻', tiger: '🐯', lion: '🦁', monkey: '🐵', koala: '🐨',
  penguin: '🐧', chick: '🐤', owl: '🦉', unicorn: '🦄'
}
const avatarDisplay = computed(() => avatarEmojis[user.userInfo?.avatar || ''] || (user.userInfo?.name || '星').charAt(0))
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const typeLabels: Record<string, string> = { PO: '采购订单', RECV: '采购入库', INV: '采购发票', OREQ: '出库申请', OUT: '材料出库', RETURN: '退料单', LOAN: '借料', TRANSFER: '转库', CANCEL_REQ: '取消申请',
  // 下面几类手机上不能建，但会出现在「我的草稿/退回」和待审批里，不补中文名就会直接显示英文代码
  PRET: '采购退货', SO: '销售订单', SALE_OUT: '销售出库', SALE_INV: '销售发票', SALE_RETURN: '销售退货' }
function typeLabel(t: string) { return typeLabels[t] || t }
// 单据类型 → 图标/色系（宫格与卡片统一用，色系对应 mobile.scss 业务色板）
const DOC_META: Record<string, { icon: any; tint: string }> = {
  PO: { icon: markRaw(ShoppingCart), tint: 'blue' },
  RECV: { icon: markRaw(Van), tint: 'green' },
  INV: { icon: markRaw(Money), tint: 'amber' },
  OREQ: { icon: markRaw(Promotion), tint: 'orange' },
  OUT: { icon: markRaw(Box), tint: 'rust' },
  RETURN: { icon: markRaw(RefreshRight), tint: 'teal' },
  LOAN: { icon: markRaw(DocumentCopy), tint: 'purple' },
  TRANSFER: { icon: markRaw(Connection), tint: 'gray' },
  CANCEL_REQ: { icon: markRaw(Tickets), tint: 'red' }
}
function iconOf(t: string) { return DOC_META[t]?.icon || markRaw(Tickets) }
function tintOf(t: string) { return DOC_META[t]?.tint || 'blue' }

// ===== 底部 Tab：按权限显隐（与网页端一致），新增钮居中凸起 =====
const allTabs = [
  { key: 'todo', label: '审批', icon: markRaw(DocumentChecked) },
  { key: 'docs', label: '单据', icon: markRaw(Tickets) },
  { key: 'create', label: '新增', icon: markRaw(Plus) },
  { key: 'stock', label: '库存', icon: markRaw(Box) },
  { key: 'me', label: '我的', icon: markRaw(User) }
]
const visibleTabs = computed(() => allTabs.filter(t => {
  if (t.key === 'create') return createActions.value.length > 0
  if (t.key === 'docs') return docTypes.value.length > 0
  if (t.key === 'stock') return canViewBalance.value || canViewTxn.value
  return true
}))

// ===== 新增单据：与网页端同一套角色门控（查全部角色，不只第一个） =====
const canCreateRequest = computed(() => user.hasRole('field_manager') || user.hasRole('service_staff') || user.hasRole('purchaser'))
const createActions = computed(() => {
  const all: any[] = []
  if (user.hasRole('purchaser')) all.push({ label: '采购订单', desc: '提交采购订单', icon: markRaw(ShoppingCart), tint: 'blue', mobilePath: '/mobile/po' })
  if (canCreateRequest.value) all.push({ label: '出库申请', desc: '现场领料申请', icon: markRaw(Promotion), tint: 'orange', mobilePath: '/mobile/oreq' })
  if (user.hasRole('warehouse')) {
    all.push({ label: '采购入库', desc: '从采购订单入库', icon: markRaw(Van), tint: 'green', mobilePath: '/mobile/recv' })
    // 实际用得最多的单:仓管在库房发料,最需要手机上办
    all.push({ label: '材料出库', desc: '按已审批申请发料', icon: markRaw(Promotion), tint: 'rust', mobilePath: '/mobile/out' })
    all.push({ label: '借料登记', desc: '登记临时借料', icon: markRaw(DocumentCopy), tint: 'purple', mobilePath: '/mobile/loan' })
  }
  return all
})

// 首页「快捷发起」：一排最多 4 个。能建的单不超过 4 种就全摆出来；超过就摆前 3 个 + 「全部」
const quickActions = computed(() => {
  const all = createActions.value
  if (all.length <= 4) return all
  return [...all.slice(0, 3), { label: '全部', icon: markRaw(Grid), tint: 'gray', all: true }]
})

// ===== 单据浏览：两级(宫格→列表)，按"查看"权限显隐（与网页端菜单一致） =====
const docView = ref<'grid' | 'list'>('grid')
const docType = ref('')
const docKeyword = ref('')
const docStatus = ref('')
const docStart = ref('')
const docEnd = ref('')
const docList = ref<any[]>([])
const docLoading = ref(false)
// 各类型可筛的状态(借料/发票状态集不同)
const DEFAULT_STATUS = [
  { label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' },
  { label: '已审批', value: 'APPROVED' }, { label: '已驳回', value: 'REJECTED' }
]
const STATUS_OPTIONS: Record<string, { label: string; value: string }[]> = {
  LOAN: [
    { label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' },
    { label: '已登记', value: 'REGISTERED' }, { label: '已归还', value: 'CLOSED' }, { label: '已驳回', value: 'REJECTED' }
  ],
  INV: [
    { label: '全部', value: '' }, { label: '草稿', value: 'DRAFT' }, { label: '待审批', value: 'PENDING' },
    { label: '已审批', value: 'APPROVED' }, { label: '已核销', value: 'MATCHED' }, { label: '已驳回', value: 'REJECTED' }
  ]
}
const statusOptions = computed(() => STATUS_OPTIONS[docType.value] || DEFAULT_STATUS)
const allDocTypes = [
  { key: 'PO', label: '采购订单', perm: 'po.view' }, { key: 'RECV', label: '采购入库', perm: 'recv.view' },
  { key: 'INV', label: '发票', perm: 'inv.view' }, { key: 'OREQ', label: '出库申请', perm: 'oreq.view' },
  { key: 'OUT', label: '材料出库', perm: 'out.view' }, { key: 'RETURN', label: '退料', perm: 'return.view' },
  { key: 'LOAN', label: '借料', perm: 'loan.view' }, { key: 'TRANSFER', label: '转库', perm: 'inv.transfer' }
]
// 以"用户菜单树里的 permCode"为准（与网页端侧边栏同源），比 perms 数组更可靠
const menuPermSet = computed(() => {
  const set = new Set<string>()
  const walk = (items: any[]) => { for (const m of items || []) { if (m.permCode) set.add(m.permCode); walk(m.children) } }
  walk(user.menus)
  return set
})
const docTypes = computed(() => allDocTypes.filter(d => user.roles.includes('admin') || menuPermSet.value.has(d.perm)))
// 库存两块各有各的权限码，后端分别是 requirePerm("inv.bal") / requirePerm("inv.trans")。
// 🔴 2026-09-16 补：原先「库存」页签对所有人都显示，而采购员两个接口都是 4003——
// 点进去只会看到一片红色报错，等于摆了个用不了的入口。按权限显隐后各角色看到的才是能用的。
const canViewBalance = computed(() => user.roles.includes('admin') || menuPermSet.value.has('inv.bal'))
const canViewTxn = computed(() => user.roles.includes('admin') || menuPermSet.value.has('inv.trans'))
const docSources: Record<string, (p: any) => Promise<any>> = {
  PO: poApi.page, RECV: recvApi.page, INV: invoiceApi.page, OREQ: outApi.oreqPage,
  OUT: outApi.outPage, RETURN: outApi.returnPage, LOAN: outApi.loanPage, TRANSFER: inventoryApi.transferPage
}
const statusLabels: Record<string, string> = { DRAFT: '草稿', PENDING: '待审批', APPROVED: '已审批', REJECTED: '已驳回', CANCELLED: '已作废', PARTIAL_RECEIVED: '部分入库', RECEIVED: '已入库', PARTIAL_OUT: '部分出库', OUT: '已出库', RETURNED: '已归还', MATCHED: '已核销', CONVERTED: '已转单', REGISTERED: '已登记', CLOSED: '已归还' }
function statusText(s: string) { return statusLabels[s] || s || '-' }
function docDate(row: any) { return row.orderDate || row.recvDate || row.invoiceDate || row.applyDate || row.outDate || row.returnDate || row.borrowDate || row.transferDate || row.createdAt || '' }
/** 首页提醒卡「去处理」：切到单据页签，按提醒带来的条件（单号/状态）列出 */
function openDocsFromAlert(type: string, q: Record<string, string>) {
  tab.value = 'docs'
  docView.value = 'list'
  docKeyword.value = q.keyword || ''
  docStatus.value = q.status || ''
  docStart.value = ''
  docEnd.value = ''
  loadDocs(type)
}
function enterDocType(type: string) {
  docView.value = 'list'
  docKeyword.value = ''
  docStatus.value = ''
  docStart.value = ''
  docEnd.value = ''
  loadDocs(type)
}
// 列表接口对供应商、仓库只给编号。名字在第一次进到用得着的单据类型时查一次档案，查不到就不显示，不挡列表
const docLookups = reactive<DocLookups>({ suppliers: {}, warehouses: {} })
let supplierNamesLoaded = false, warehouseNamesLoaded = false
async function loadDocLookups(type: string) {
  if (['PO', 'RECV', 'INV'].includes(type) && !supplierNamesLoaded) {
    try {
      const rows = await masterApi.allSuppliers({ includeDisabled: true }) as any[]
      docLookups.suppliers = Object.fromEntries((rows || []).map(r => [r.id, r.name]))
      supplierNamesLoaded = true
    } catch { /* 没权限或网络问题：卡片上不显示供应商名 */ }
  }
  if (type === 'TRANSFER' && !warehouseNamesLoaded) {
    try {
      const rows = await masterApi.warehouses(true) as any[]
      docLookups.warehouses = Object.fromEntries((rows || []).map(r => [r.id, r.name]))
      warehouseNamesLoaded = true
    } catch { /* 同上 */ }
  }
}
async function loadDocs(type: string) {
  docType.value = type
  docLoading.value = true
  loadDocLookups(type)
  try {
    const p: any = { size: 30, current: 1 }
    if (docKeyword.value.trim()) p.keyword = docKeyword.value.trim()
    if (docStatus.value) p.status = docStatus.value
    if (docStart.value) p.startDate = docStart.value
    if (docEnd.value) p.endDate = docEnd.value
    const r: any = await docSources[type](p)
    docList.value = r?.records || []
  } catch { docList.value = [] } finally { docLoading.value = false }
}
function openDoc(row: any) { router.push(`/mobile/doc/${docType.value}/${row.id}`) }

/**
 * 手机上能审批哪些单据。
 *
 * 🔴 2026-08-02 补齐:原来只有前 6 类,转库/借料/销售 4 类点「通过」会弹「不支持手机审批」,
 *    人在现场却批不了,只能回电脑 —— 而这几类恰恰是最需要在现场处理的。
 *    新增单据类型时【这张表必须一起加】,否则手机端就是个死胡同。
 */
const reviewActions: Record<string, any> = {
  PO: { approve: poApi.approve, reject: poApi.reject },
  RECV: { approve: recvApi.approve, reject: recvApi.reject },
  INV: { approve: invoiceApi.approve, reject: invoiceApi.reject },
  OREQ: { approve: outApi.oreqApprove, reject: outApi.oreqReject },
  OUT: { approve: outApi.outApprove, reject: outApi.outReject },
  RETURN: { approve: outApi.returnApprove, reject: outApi.returnReject },
  TRANSFER: { approve: inventoryApi.transferApprove, reject: inventoryApi.transferReject },
  LOAN: { approve: outApi.loanApprove, reject: outApi.loanReject },
  SO: { approve: salesApi.orderApprove, reject: salesApi.orderReject },
  SALE_OUT: { approve: salesApi.outApprove, reject: salesApi.outReject },
  SALE_INV: { approve: salesApi.invoiceApprove, reject: salesApi.invoiceReject },
  SALE_RETURN: { approve: salesApi.returnApprove, reject: salesApi.returnReject }
}

// ===== 库存：搜索优先（不输关键词不显示数据），首搜拉取后缓存 =====
const keyword = ref('')
const stockLoading = ref(false)
const stockRows = ref<any[]>([])
const stockFetched = ref(false)
const recentSearches = ref<string[]>(JSON.parse(localStorage.getItem('jxc_stock_recent') || '[]'))
let stockTimer: any = null

const filteredStock = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return []
  return stockRows.value.filter((r: any) => `${r.matCode} ${r.matName} ${r.spec} ${r.whName}`.toLowerCase().includes(q))
})
const shownStock = computed(() => filteredStock.value.slice(0, 50))
const stockOverflow = computed(() => filteredStock.value.length > 50)

function onStockInput() {
  clearTimeout(stockTimer)
  const q = keyword.value.trim()
  if (!q) return
  stockTimer = setTimeout(async () => {
    if (!stockFetched.value) await fetchStock()
    rememberSearch(q)
  }, 350)
}
async function fetchStock() {
  stockLoading.value = true
  try {
    const rows = await inventoryApi.balance({ includeZero: 0 }) as any[]
    stockRows.value = (rows || []).map((r: any) => ({
      matCode: r.matCode, matName: r.matName, spec: r.matSpec || '', whName: r.whName, qty: r.qty, unit: r.matUnit || '-',
      // 保留 id：点卡片查这个物料的进出记录要用（各接口字段名不一，挨个兜底）
      matId: r.matId ?? r.materialId ?? r.material_id,
      whId: r.whId ?? r.warehouseId ?? r.warehouse_id
    }))
    stockFetched.value = true
  } catch { stockRows.value = [] }
  finally { stockLoading.value = false }
}
// ===== 库存：仓库筛选 + 进出记录（2026-09-15 加）=====
// 用户反馈:只能敲关键词,不能挑仓库、不能组合查,也看不到某个料这段时间进出了多少。
const stockSeg = ref<'balance' | 'txn'>('balance')
const whFilter = ref<number | ''>('')
const warehouseOptions = ref<any[]>([])
const txnRows = ref<any[]>([])
const txnLoading = ref(false)
const txnMatId = ref<number | ''>('')      // 锁定某个物料（从现存量点进来时带上）
const txnMatLabel = ref('')
const txnStart = ref('')
const txnEnd = ref('')

/** 现存量按仓库再筛一道（关键词过滤仍在 filteredStock 里） */
const shownStockFinal = computed(() =>
  whFilter.value === '' ? shownStock.value
    : shownStock.value.filter((r: any) => Number(r.whId) === Number(whFilter.value)))

async function loadWarehouseOptions() {
  if (warehouseOptions.value.length) return
  try { warehouseOptions.value = (await masterApi.warehouses(true)) as any[] || [] }
  catch { warehouseOptions.value = [] }
}

/** 默认查近 30 天：手机上不该一进来就让人填日期 */
function defaultRange() {
  const end = new Date()
  const start = new Date(Date.now() - 29 * 86400000)
  const f = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return [f(start), f(end)]
}

async function loadTxn() {
  txnLoading.value = true
  try {
    const params: any = { limit: 200 }
    if (txnMatId.value !== '') params.matId = txnMatId.value
    if (whFilter.value !== '') params.whId = whFilter.value
    if (txnStart.value) params.startDate = txnStart.value
    if (txnEnd.value) params.endDate = txnEnd.value
    txnRows.value = (await inventoryApi.transactions(params)) as any[] || []
  } catch { txnRows.value = [] }
  finally { txnLoading.value = false }
}

/** 从现存量卡片点进某个物料的进出记录 */
function openTxnOf(row: any) {
  txnMatId.value = row.matId ?? row.materialId ?? ''
  txnMatLabel.value = `${row.matCode || ''} ${row.matName || ''}`.trim()
  if (!txnStart.value) { const [a, b] = defaultRange(); txnStart.value = a; txnEnd.value = b }
  stockSeg.value = 'txn'
  loadWarehouseOptions()
  loadTxn()
}

function switchStockSeg(seg: 'balance' | 'txn') {
  stockSeg.value = seg
  loadWarehouseOptions()
  if (seg === 'txn') {
    if (!txnStart.value) { const [a, b] = defaultRange(); txnStart.value = a; txnEnd.value = b }
    loadTxn()
  }
}

function clearTxnMat() { txnMatId.value = ''; txnMatLabel.value = ''; loadTxn() }

// 只有一种权限的人不该停在他看不了的那一段：
// 采购员没有 inv.bal 就直接落在「进出记录」，反之亦然。
watch([canViewBalance, canViewTxn], ([bal, txn]) => {
  if (!bal && txn) stockSeg.value = 'txn'
  else if (bal && !txn) stockSeg.value = 'balance'
}, { immediate: true })

/** 进出方向：正数进、负数出 */
function txnDir(r: any) { return Number(r.qty || 0) >= 0 ? 'in' : 'out' }
// 类型中文名直接用全站公共表(utils/format)，别在手机端另维护一份——
// 第一版就是自己抄了一份、漏了一半类型，界面上直接显示出英文 REVERSE
function txnTypeLabel(r: any) { return fmtInventoryTransType(r.transType) }

function rememberSearch(q: string) {
  if (q.length < 2) return
  const list = [q, ...recentSearches.value.filter(k => k !== q)].slice(0, 6)
  recentSearches.value = list
  localStorage.setItem('jxc_stock_recent', JSON.stringify(list))
}

function openReview(item: any) {
  reviewItem.value = item
  reviewComment.value = ''
  reviewVisible.value = true
  loadReviewFlow(item)
}

/** 审批面板加载该单据的审批流时间线（取消申请类无审批流，跳过） */
async function loadReviewFlow(item: any) {
  reviewFlow.value = null
  if (!item?.bizNo || item.bizType === 'CANCEL_REQ') return
  reviewFlowLoading.value = true
  try { reviewFlow.value = await wfApi.flow(item.bizNo) } catch { reviewFlow.value = null }
  finally { reviewFlowLoading.value = false }
}
const STEP_LABELS: Record<string, string> = { DONE: '已通过', REJECTED: '已驳回', CURRENT: '审批中', PENDING: '待审' }
function stepLabel(s: string) { return STEP_LABELS[s] || s || '' }
function fmtFlowTime(t: string) {
  if (!t) return ''
  const d = new Date(String(t).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(t).slice(5, 16)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}
/** 从审批面板跳到该单据完整明细 */
function openDocDetail(item: any) {
  if (!item?.bizType || !item?.bizId || item.bizType === 'CANCEL_REQ') return
  reviewVisible.value = false
  router.push(`/mobile/doc/${item.bizType}/${item.bizId}`)
}

async function doReview(pass: boolean) {
  const item = reviewItem.value
  if (!item?.bizId) { ElMessage.warning('无法处理'); return }
  // 驳回必须写原因：只留一句"驳回"，制单人不知道要改哪里，审计也看不出为什么退
  if (!pass && !reviewComment.value.trim()) { ElMessage.warning('请在审批意见里写明驳回原因'); return }
  const comment = reviewComment.value.trim() || '同意'
  try {
    if (item.bizType === 'CANCEL_REQ') {
      await wfApi.processCancel(item.bizId, { approve: pass, comment })
    } else {
      const a = reviewActions[item.bizType]
      if (!a) { ElMessage.warning('不支持手机审批'); reviewVisible.value = false; return }
      await (pass ? a.approve : a.reject)(item.bizId, comment)
    }
    ElMessage.success(pass ? '已通过' : '已驳回')
    reviewVisible.value = false
    loadTodos()
  } catch { reviewVisible.value = false }
}

function goCreate(a: any) {
  if (a.mobilePath) { router.push(a.mobilePath); return }
  router.push(a.path)
}

async function loadTodos() {
  loading.value = true
  try {
    const [notifs, pending] = await Promise.all([
      notifApi.my(50).catch(() => []),
      wfApi.pendingApprovals().catch(() => [])
    ])
    todos.value = (notifs as any[]) || []
    pendingDocs.value = (pending as any[]) || []
  } finally { loading.value = false }
  loadMyDocs()
  // 审批完一张单，提醒卡里对应的那条可能就该消失了
  alertsRef.value?.load()
}

/** 我发起的草稿/退回单（跨类型聚合），供「我的退回 / 我的草稿」两段 */
async function loadMyDocs() {
  try { myDocs.value = (await wfApi.myDocuments('DRAFT,REJECTED')) as any[] || [] }
  catch { myDocs.value = [] }
}

/** 打开我发起的单据（退回/草稿） */
function openMyDoc(d: any) {
  if (!d?.bizType || !d?.bizId) return
  router.push(`/mobile/doc/${d.bizType}/${d.bizId}`)
}

function showProfile() {
  profileForm.name = user.userInfo?.name || ''
  profileForm.phone = user.userInfo?.phone || ''
  profileForm.email = user.userInfo?.email || ''
  profileVisible.value = true
}
async function saveProfile() {
  await user.updateProfile({ name: profileForm.name, phone: profileForm.phone, email: profileForm.email })
  ElMessage.success('已保存'); profileVisible.value = false
}
function showPassword() { pwdForm.oldPassword = ''; pwdForm.newPassword = ''; pwdVisible.value = true }
async function savePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) { ElMessage.warning('请填写完整'); return }
  if (pwdForm.newPassword.length < 6) { ElMessage.warning('密码至少6位'); return }
  await user.changePassword(pwdForm)
  ElMessage.success('密码已修改'); pwdVisible.value = false
}
async function logout() {
  await user.logout()
  router.replace('/mobile/login')
}
function switchToDesktop() {
  setForceDesktop(true)
  window.location.hash = '/'
  window.location.reload()
}

watch(tab, (t) => {
  if (t === 'todo') loadTodos()
  if (t === 'docs') { docView.value = 'grid'; docList.value = [] }
  if (t === 'stock') {
    // 进「库存」就把仓库下拉备好，否则只有点过分段条的人才有得选
    loadWarehouseOptions()
    if (stockSeg.value === 'txn') {
      if (!txnStart.value) { const [a, b] = defaultRange(); txnStart.value = a; txnEnd.value = b }
      if (!txnRows.value.length) loadTxn()
    }
  }
})

onMounted(loadTodos)
// 手机端没有电脑端顶栏,在这里读一次"能看哪些公司、现在选的是哪家"
const viewCompany = useViewCompanyStore()
onMounted(() => viewCompany.load())
</script>

<style scoped lang="scss">
/* 滚动通知细条:贴在顶栏下面 */
/* 要和 .mob-head/.mob-body 一样浮在页面装饰背景层上面,否则底色和图标被背景盖住、只剩滚动文字(10-21 实测) */
.mob-ticker { padding: 0 12px; display: flex; position: relative; z-index: 2; }
.mob-ticker :deep(.nt) { margin-top: 6px; }   /* 没有通知时只剩空壳,不留空隙 */

.mob-manual-entry { width: 100%; border: 0; background: transparent; color: inherit; text-align: left; font: inherit; min-height: 48px; }

.mob { height: 100vh; display: flex; flex-direction: column; background: var(--mob-bg); color: var(--mob-ink); font-size: 14px; position: relative;
  /* 顶部暖纸渐变洗染:与壳闪屏同色系,有氛围、无割裂,自然淡出到底色 */
  &::before { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 300px; pointer-events: none;
    background: linear-gradient(180deg, #f2ead8 0%, #f8f2e6 42%, rgba(250, 247, 242, 0) 100%); }
}

/* ===== 顶部导航(透明,融入顶部渐变洗染) ===== */
.mob-head {
  /* 上下留白拉匀：原来上 16px(10+6) / 下 6px，顶栏视觉上贴着状态栏、下面又空一块。
     App 壳已经把 WebView 推到状态栏下方，这里的 safe-area 通常为 0，靠自身 padding 撑开。 */
  padding: 0 16px 12px; padding-top: calc(14px + env(safe-area-inset-top));
  background: transparent;
  color: var(--mob-ink); flex-shrink: 0; position: relative; z-index: 2;
}
.mob-head-bar { display: flex; align-items: center; justify-content: space-between; }
.mob-head-l { display: flex; align-items: center; gap: 9px; min-width: 0; }
/* logo 与右侧头像取同一尺寸，两端视觉重量才平衡（原来 30 vs 34 看着一头沉） */
.mob-head-logo { width: 34px; height: 34px; flex-shrink: 0; object-fit: contain; }
.mob-view-org {
  flex: 0 0 auto; border: 1px solid var(--border-1); background: var(--bg-panel); color: var(--text-2);
  font-size: 12px; line-height: 22px; padding: 0 9px; border-radius: 11px; white-space: nowrap; cursor: pointer;
}
.mob-view-org.is-narrowed { color: #174a88; border-color: #9cc0e8; background: #eef5fd; font-weight: 600; }
.mob-head-name { font-weight: 750; font-size: 16px; letter-spacing: .3px; color: var(--mob-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mob-head-avatar { width: 34px; height: 34px; border-radius: 50%; background: linear-gradient(135deg, #4a5aa8, var(--mob-brand)); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 16px; flex-shrink: 0; }

.mob-body { flex: 1; overflow-y: auto; -webkit-overflow-scrolling: touch; position: relative; z-index: 1; }
/* 页签切换过渡容器：撑起与 .mob-body 同高，让 .mob-page 的纵向布局不变 */
.mob-tab-pane { display: flex; flex-direction: column; min-height: 100%; }
.mob-page { padding: 14px 16px 118px; display: flex; flex-direction: column; gap: 12px; flex: 1; }
.mob-page-head { margin: 2px 0 4px; }
.mob-page-title { font-size: 20px; font-weight: 800; color: var(--mob-ink); letter-spacing: .3px; }
.mob-page-sub { font-size: 12.5px; color: var(--mob-ink-2); margin-top: 3px; }

/* ===== 空状态 ===== */
.mob-empty { text-align: center; padding: 40px 20px; color: var(--mob-ink-2); display: flex; flex-direction: column; align-items: center; gap: 6px;
  &.airy { padding: 56px 20px; }
}
.mob-empty-ico { font-size: 44px; line-height: 1; margin-bottom: 6px; }
.mob-empty-title { font-size: 15px; font-weight: 650; color: var(--mob-ink); }
.mob-empty-sub { font-size: 12.5px; color: var(--mob-ink-2); }

/* ===== 库存：分段条 / 筛选 / 进出记录 ===== */
.mob-seg {
  display: flex; gap: 6px; padding: 4px;
  background: var(--mob-soft); border-radius: 10px; margin-bottom: 10px;
}
.mob-seg-btn {
  flex: 1; height: 34px; border: 0; border-radius: 8px; background: transparent;
  font-size: 14px; color: var(--mob-ink-2); cursor: pointer;
  transition: background .15s ease, color .15s ease;
  &.active { background: #fff; color: var(--mob-ink); font-weight: 650; box-shadow: 0 1px 3px rgba(66, 54, 32, .08); }
}
.mob-filter { margin-bottom: 10px; }
.mob-filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.mob-filter-sep { color: var(--mob-ink-2); font-size: 13px; }

.mob-picked-mat {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 12px; margin-bottom: 10px; border-radius: 8px;
  background: rgba(47, 62, 140, .08); font-size: 13px;
}
.mob-picked-clear { border: 0; background: none; color: var(--mob-brand); font-size: 12px; cursor: pointer; }

.mob-txn-card {
  background: #fff; border-radius: 12px; padding: 12px 14px; margin-bottom: 8px;
}
.mob-txn-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.mob-txn-mat { font-size: 14px; font-weight: 600; line-height: 1.4; }
.mob-txn-qty {
  font-size: 16px; font-weight: 700; white-space: nowrap; font-variant-numeric: tabular-nums;
  /* 进红出绿会反直觉,这里按"进=蓝、出=橙"区分,与单据状态色一致 */
  &.in { color: var(--biz-po); }
  &.out { color: var(--biz-out); }
}
.mob-txn-sub {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  font-size: 12px; color: var(--mob-ink-2); margin-top: 6px;
  &.dim { color: var(--mob-ink-2); }
}
.mob-txn-no { font-variant-numeric: tabular-nums; }

/* 待审卡片:单号是定位单据的关键 —— 不折行,也不能被右侧金额挤成省略号,
   所以字号比正文再小一档,换取完整显示 */
.mob-card-no {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  letter-spacing: -.1px;
  flex-shrink: 0;
}
/* 第三行:公司名 + 等了多久,弱化显示 */
.mob-card-meta {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--mob-ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 标题行:左边谁提交的、右边多少钱。金额放在这一行而不是整行右侧,
   下面的单号才能占满整宽完整显示（第二版就是被右侧金额挤掉的） */
.mob-card-headline {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.mob-card-headline .mob-card-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 金额:审批人第一眼要看的 */
.mob-card-amt {
  flex-shrink: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--mob-ink);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ===== 单据列表卡片（版式在 styles/mobile-ui.scss 的 .mx-card；这里抵掉 .mob-page 已有的左右留白）===== */
.mob-doc-card { width: 100%; margin: 0; }
.mob-doc-mat { font-size: 13px; line-height: 1.45; color: var(--mob-ink); }
.mob-doc-amt { flex-shrink: 0; font-size: 14px; font-weight: 700; color: var(--mob-ink); font-variant-numeric: tabular-nums; }

/* ===== 首页分组（版式在 styles/mobile-ui.scss；这里只抵掉 .mob-page 已有的左右留白和间距）===== */
.mob-home-g { margin: 0; }
.mob-page .mob-home-alerts { margin-bottom: 0; }

/* ===== 通用卡片（按压反馈统一走 mobile.scss 全局规则）===== */
.mob-card { background: #fff; border-radius: 16px; padding: 14px; box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09); position: relative; overflow: hidden;
  &.slim { padding: 12px 14px; }
}
.mob-card-row { display: flex; align-items: center; gap: 12px; }
.mob-card-main { flex: 1; min-width: 0; }
.mob-card-title { font-weight: 650; font-size: 14.5px; color: var(--mob-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  &.mono { font-family: ui-monospace, Menlo, monospace; font-size: 13.5px; letter-spacing: -.2px; }
}
.mob-card-no { font-family: ui-monospace, Menlo, monospace; font-size: 11.5px; color: var(--mob-ink-2); letter-spacing: -.2px; }
.mob-card-sub { font-size: 12px; color: var(--mob-ink-2); margin-top: 4px; display: flex; align-items: center; gap: 6px; overflow: hidden; }
.mob-card-time { font-size: 11px; color: #b3aa97; flex-shrink: 0; }
.mob-tag { font-size: 10px; padding: 2.5px 8px; border-radius: 999px; background: rgba(47, 62, 140, .1); color: var(--mob-brand); font-weight: 600; flex-shrink: 0; }
.mob-tag.danger { background: rgba(192, 86, 79, .12); color: var(--biz-danger); }
.mob-card-arrow { font-size: 22px; color: #cec6b4; flex-shrink: 0; line-height: 1; }
.mob-card-actions { display: flex; gap: 8px; margin-top: 12px; }
.mob-btn { flex: 1; height: 38px; border: 0; border-radius: 10px; font-weight: 600; font-size: 13px; cursor: pointer; display: flex; align-items: center; justify-content: center;
  &.ok { background: var(--biz-recv); color: #fff; box-shadow: 0 1px 3px rgba(93, 156, 70, .3); }
  &.ok.solid { background: linear-gradient(135deg, var(--mob-brand), #46539f); color: #fff; }
  &.no { background: #fff; color: var(--biz-danger); box-shadow: inset 0 0 0 1px rgba(192, 86, 79, .4); }
  &.ko { background: rgba(192, 86, 79, .1); color: var(--biz-danger); }
  &.ghost { background: #fff; border: 1px solid var(--mob-line); color: var(--mob-ink); }
}

/* ===== 图标色块(按业务色板多彩化,见 mobile.scss 变量) ===== */
.mob-ico-chip { width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  background: rgba(47, 62, 140, .09); color: var(--mob-brand);
  &.blue { background: rgba(62, 111, 176, .12); color: var(--biz-po); }
  &.green { background: rgba(93, 156, 70, .13); color: var(--biz-recv); }
  &.orange { background: rgba(217, 118, 66, .13); color: var(--biz-out); }
  &.rust { background: rgba(190, 92, 44, .13); color: #be5c2c; }
  &.purple { background: rgba(138, 111, 191, .13); color: var(--biz-loan); }
  &.teal { background: rgba(61, 155, 155, .13); color: var(--biz-inv); }
  &.amber { background: rgba(217, 160, 46, .15); color: var(--biz-approve); }
  &.red { background: rgba(192, 86, 79, .11); color: var(--biz-danger); }
  &.gray { background: rgba(118, 128, 142, .12); color: var(--mob-ink-2); }
}
.mob-ico-big { @extend .mob-ico-chip; width: 52px; height: 52px; border-radius: 16px; }

/* ===== 宫格(新增/单据类型) ===== */
.mob-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.mob-grid-card { background: #fff; border: 0; border-radius: 18px; padding: 20px 12px 16px; display: flex; flex-direction: column; align-items: center; gap: 8px; cursor: pointer;
  box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09);
}
.mob-grid-label { font-size: 14.5px; font-weight: 650; color: var(--mob-ink); }
.mob-grid-desc { font-size: 11.5px; color: var(--mob-ink-2); }

/* ===== 单据列表二级页 ===== */
.mob-list-head { display: flex; align-items: center; gap: 8px; margin-bottom: 2px; }
.mob-back { border: 0; background: transparent; color: var(--mob-brand); font-size: 15px; font-weight: 600; padding: 4px 8px 4px 0; cursor: pointer; }
.mob-list-title { font-size: 17px; font-weight: 750; color: var(--mob-ink); }
.mob-doc-status { font-size: 11px; padding: 3px 9px; border-radius: 999px; background: var(--mob-line); color: var(--mob-ink-2); flex-shrink: 0; white-space: nowrap; font-weight: 600;
  &.st-approved, &.st-received, &.st-out, &.st-returned, &.st-matched, &.st-closed { background: rgba(93, 156, 70, .13); color: var(--biz-recv); }
  &.st-pending, &.st-partial_received, &.st-partial_out, &.st-registered { background: rgba(217, 160, 46, .15); color: #c47f17; }
  &.st-rejected, &.st-cancelled { background: rgba(192, 86, 79, .1); color: var(--biz-danger); }
}

/* ===== 搜索 ===== */
.mob-search { position: relative; }
.mob-search-ico { position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--mob-ink-2); }
.mob-input { width: 100%; height: 46px; padding: 0 40px; border: 1.5px solid var(--mob-line); border-radius: 14px; font-size: 14.5px; background: #fff; outline: none; box-sizing: border-box;
  box-shadow: 0 2px 10px rgba(66, 54, 32, .05);
  &:focus { border-color: #46539f; box-shadow: 0 0 0 3px rgba(47, 62, 140, .12); }
}
.mob-search-clear { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 26px; height: 26px; border: 0; border-radius: 50%; background: var(--mob-line); color: var(--mob-ink-2); font-size: 12px; cursor: pointer; }
.mob-recent-title { font-size: 12px; color: var(--mob-ink-2); margin-bottom: 8px; font-weight: 600; }
.mob-recent-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.mob-chip { border: 1px solid var(--mob-line); background: #fff; color: var(--mob-ink); border-radius: 999px; padding: 7px 15px; font-size: 13px; cursor: pointer;
  &:active { background: rgba(47, 62, 140, .08); border-color: #46539f; color: var(--mob-brand); }
}
.mob-more-hint { text-align: center; font-size: 12px; color: var(--mob-ink-2); padding: 6px 0 2px; }

/* 单据筛选 */
.mob-filter-row { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 2px; -webkit-overflow-scrolling: touch;
  &::-webkit-scrollbar { display: none; }
  .mob-chip { flex-shrink: 0; padding: 6px 13px; font-size: 12.5px;
    &.active { background: var(--mob-brand); border-color: var(--mob-brand); color: #fff; }
  }
}
.mob-date-row { display: flex; align-items: center; gap: 8px; }
.mob-date { flex: 1; height: 40px; padding: 0 10px; border: 1.5px solid var(--mob-line); border-radius: 12px; font-size: 13.5px; background: #fff; outline: none; box-sizing: border-box; color: var(--mob-ink); min-width: 0;
  &:focus { border-color: #46539f; }
}
.mob-date-sep { font-size: 12px; color: var(--mob-ink-2); flex-shrink: 0; }
.mob-search-clear.static { position: static; transform: none; flex-shrink: 0; }

/* ===== 库存卡 ===== */
.mob-stock-card { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; background: #fff; border-radius: 16px; box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09); }
.mob-stock-left { min-width: 0; flex: 1; }
.mob-stock-name { font-weight: 650; font-size: 14.5px; }
.mob-stock-sub { font-size: 12px; color: var(--mob-ink-2); margin-top: 3px; }
.mob-stock-right { text-align: right; flex-shrink: 0; padding-left: 10px; }
.mob-stock-qty { font-size: 22px; font-weight: 800; color: var(--mob-brand); }
.mob-stock-unit { font-size: 12px; color: var(--mob-ink-2); margin-left: 3px; }

/* ===== 我的 ===== */
.mob-me-card { background: #fff; border-radius: 20px; padding: 22px 20px; display: flex; align-items: center; gap: 16px; color: var(--mob-ink);
  box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09);
}
.mob-me-avatar { width: 58px; height: 58px; border-radius: 50%; background: linear-gradient(135deg, #4a5aa8, var(--mob-brand)); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 26px; font-weight: 700; flex-shrink: 0; }
.mob-me-meta { min-width: 0; }
.mob-me-name { font-size: 19px; font-weight: 800; letter-spacing: .3px; }
.mob-me-badges { margin-top: 6px; }
.mob-me-role { font-size: 11.5px; background: rgba(47, 62, 140, .1); color: var(--mob-brand); font-weight: 600; display: inline-block; padding: 2.5px 10px; border-radius: 99px; }
.mob-me-org { font-size: 11.5px; color: var(--mob-ink-2); margin-top: 6px; }
.mob-group-title { font-size: 12px; color: var(--mob-ink-2); font-weight: 650; margin: 6px 2px -4px; }
.mob-me-list { background: #fff; border-radius: 16px; overflow: hidden; box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09); }
.mob-me-item { padding: 14px 16px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid var(--mob-line); font-size: 14.5px; cursor: pointer;
  &:last-child { border-bottom: 0; }
  &:active:not(.static) { background: #f5f0e7; }
  &.static { cursor: default; }
}
.mob-me-ico { width: 30px; height: 30px; border-radius: 9px; display: flex; align-items: center; justify-content: center; color: #fff; flex-shrink: 0;
  &.blue { background: linear-gradient(135deg, #4a5aa8, var(--mob-brand)); }
  &.green { background: linear-gradient(135deg, #74b359, var(--biz-recv)); }
  &.purple { background: linear-gradient(135deg, #9d86cc, var(--biz-loan)); }
  &.gray { background: linear-gradient(135deg, #a89f8f, #847c6c); }
}
.mob-me-item > span:nth-of-type(2) { flex: 1; }
.mob-me-arrow { font-size: 20px; color: #cec6b4; }
.mob-me-val { font-size: 13px; color: var(--mob-ink-2); }
.mob-logout-btn { width: 100%; height: 48px; border: 0; border-radius: 16px; background: #fff; color: var(--biz-danger); font-size: 15px; font-weight: 650; cursor: pointer; margin-top: 2px; display: flex; align-items: center; justify-content: center; gap: 6px; box-shadow: 0 1px 2px rgba(66, 54, 32, .05), 0 10px 28px rgba(66, 54, 32, .09);
  &:active { background: #f7ebe8; }
}

/* ===== 底部悬浮 Dock(圆角浮岛 + 中央凸起) ===== */
.mob-nav { display: flex; align-items: stretch;
  position: fixed; left: 14px; right: 14px; bottom: calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, .94); backdrop-filter: blur(16px);
  border-radius: 26px; border: 1px solid rgba(255, 255, 255, .7);
  box-shadow: 0 10px 32px rgba(66, 54, 32, .18), 0 2px 6px rgba(66, 54, 32, .07);
  z-index: 5;
}
.mob-nav-btn { flex: 1; height: 60px; border: 0; background: transparent; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; cursor: pointer; position: relative; color: var(--mob-ink-2);
  &.active { color: var(--mob-brand); }
  &.active .mob-nav-ico { background: rgba(47, 62, 140, .12); }
}
.mob-nav-ico { line-height: 0; padding: 5px 14px; border-radius: 999px; transition: background .15s; }
.mob-nav-label { font-size: 10.5px; font-weight: 600; }
.mob-nav-badge { position: absolute; top: 5px; right: calc(50% - 30px); min-width: 17px; height: 17px; border-radius: 9px; background: var(--biz-danger); color: #fff; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; padding: 0 5px; }
.mob-nav-fab { flex: 0 0 74px; border: 0; background: transparent; position: relative; cursor: pointer;
  display: flex; align-items: flex-start; justify-content: center;
  &::before { content: ''; position: absolute; top: -22px; width: 54px; height: 54px; border-radius: 50%;
    background: linear-gradient(150deg, var(--mob-brand), #46539f); box-shadow: 0 6px 18px rgba(47, 62, 140, .4);
    transition: transform .12s ease-out; }
  svg, :deep(svg) { position: relative; margin-top: -8px; color: #fff; z-index: 1; }
  &.active::before { background: linear-gradient(150deg, var(--mob-brand-press), var(--mob-brand)); }
  /* 中央 FAB 按下回弹 */
  &:active::before { transform: scale(.92); }
}

/* ===== 弹窗 ===== */
.mob-modal { position: fixed; inset: 0; z-index: 9999; background: rgba(15, 25, 40, .45); display: flex; align-items: flex-end; justify-content: center; backdrop-filter: blur(2px); }
.mob-modal-card { width: 100%; max-width: 460px; background: #fff; border-radius: 22px 22px 0 0; padding: 12px 16px calc(24px + env(safe-area-inset-bottom)); display: flex; flex-direction: column; gap: 14px; }
.mob-modal-grip { width: 38px; height: 4.5px; border-radius: 999px; background: var(--mob-line); margin: 0 auto 2px; }
.mob-modal-title { font-weight: 750; font-size: 16px; }
.mob-review-meta { font-size: 13px; color: var(--mob-ink-2); display: flex; align-items: center; gap: 6px; margin-top: -6px; }
.mob-review-flow { border: 1px solid var(--mob-line); border-radius: 14px; padding: 12px; background: var(--mob-bg); max-height: 42vh; overflow-y: auto; }
.mob-flow-loading { font-size: 13px; color: var(--mob-ink-2); padding: 2px 0; }
.mob-flow-appl { font-size: 12px; color: var(--mob-ink-2); margin-bottom: 10px; }
.mob-flow-step { display: flex; gap: 10px; padding-bottom: 12px; position: relative; }
.mob-flow-step:not(:last-child)::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 2px; background: var(--mob-line); }
.mob-flow-dot { width: 12px; height: 12px; border-radius: 50%; background: #d4ccba; margin-top: 2px; flex-shrink: 0; z-index: 1; }
.mob-flow-step.done .mob-flow-dot { background: var(--biz-recv); }
.mob-flow-step.rejected .mob-flow-dot { background: var(--biz-danger); }
.mob-flow-step.current .mob-flow-dot { background: var(--biz-approve); box-shadow: 0 0 0 3px rgba(217, 160, 46, .2); }
.mob-flow-body { flex: 1; min-width: 0; }
.mob-flow-name { font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.mob-flow-badge { font-size: 11px; font-weight: 500; color: var(--mob-ink-2); }
.mob-flow-step.done .mob-flow-badge { color: var(--biz-recv); }
.mob-flow-step.rejected .mob-flow-badge { color: var(--biz-danger); }
.mob-flow-step.current .mob-flow-badge { color: var(--biz-approve); }
.mob-flow-sub { font-size: 12px; color: var(--mob-ink-2); margin-top: 2px; word-break: break-all; }
.mob-flow-time { color: var(--mob-ink-2); }
.mob-review-detail { margin-top: 4px; width: 100%; height: 38px; }
.mob-textarea { width: 100%; padding: 10px 12px; border: 1.5px solid var(--mob-line); border-radius: 12px; font-size: 14px; resize: none; outline: none; box-sizing: border-box; font-family: inherit;
  &:focus { border-color: #46539f; }
}
.mob-modal-actions { display: flex; gap: 10px; }

@media (min-width: 500px) {
  .mob { max-width: 440px; margin: 0 auto; box-shadow: 0 0 40px rgba(66, 54, 32, .09); }
  .mob-nav { left: 50%; right: auto; transform: translateX(-50%); width: 408px; }
}

/* 统一的按压反馈(.mob-card/.mob-grid-card/.mob-btn/.mob-chip 等的 :active 规则)
   已搬到全局 styles/mobile.scss,与建单子页(.mf-*)/详情页(.mdoc-*)共用同一套。 */
</style>
