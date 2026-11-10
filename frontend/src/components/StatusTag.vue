<template>
  <span class="status-tag" :class="`status-${variant}`">
    <span class="status-dot"></span>
    {{ label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
  effect?: 'light' | 'dark' | 'plain'
}>()

type StatusVariant = 'draft' | 'pending' | 'approved' | 'danger' | 'partial' | 'info' | 'primary'

const STATUS_MAP: Record<string, { label: string; variant: StatusVariant }> = {
  READY: { label: '待实际交接', variant: 'pending' },
  DRAFT:            { label: '草稿',    variant: 'draft' },
  PENDING:          { label: '待审批',  variant: 'pending' },
  APPROVED:         { label: '已审批',  variant: 'approved' },
  REJECTED:         { label: '已驳回',  variant: 'danger' },
  CANCELLED:        { label: '已撤销',  variant: 'danger' },
  CONVERTED:        { label: '已转订单', variant: 'primary' },
  PARTIAL_RECEIVED: { label: '部分入库', variant: 'partial' },
  RECEIVED:         { label: '已入库',  variant: 'approved' },
  PARTIAL_INVOICED: { label: '部分开票', variant: 'partial' },
  INVOICED:         { label: '已开票',  variant: 'approved' },
  MATCHED:          { label: '已核销',  variant: 'approved' },
  PARTIAL_OUT:      { label: '部分出库', variant: 'partial' },
  OUT:              { label: '已出库',  variant: 'approved' },
  REGISTERED:       { label: '已登记',  variant: 'primary' },
  CLOSED:           { label: '已关闭',  variant: 'info' },
}

const variant = computed(() => STATUS_MAP[props.status]?.variant || 'info')
const label = computed(() => STATUS_MAP[props.status]?.label || props.status)
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px 2px 7px;
  border-radius: 99px;
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
  border: 1px solid transparent;
  transition: opacity .15s ease;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: 0 0 auto;
}

/* 草稿 — 中性灰 */
.status-draft {
  background: var(--draft-bg, #f5f3f0);
  color: var(--draft-text, #78716c);
  border-color: var(--draft-border, #e7e5e4);
}
.status-draft .status-dot { background: #98a1b0; }

/* 待审批 — 琥珀黄（设计系统 accent） */
.status-pending {
  background: var(--pending-bg, #fef9ef);
  color: var(--pending-text, #9a6020);
  border-color: var(--pending-border, #fde68a);
}
.status-pending .status-dot { background: #d6881f; }

/* 已审批/完成 — 绿 */
.status-approved {
  background: var(--approved-bg, #f0fdf4);
  color: var(--approved-text, #15803d);
  border-color: var(--approved-border, #bbf7d0);
}
.status-approved .status-dot { background: #2e9e6b; }

/* 已驳回/撤销 — 红 */
.status-danger {
  background: var(--danger-bg, #fff1f2);
  color: var(--danger-text, #be123c);
  border-color: var(--danger-border, #fecdd3);
}
.status-danger .status-dot { background: #dc4d4d; }

/* 部分完成 — 蓝灰 */
.status-partial {
  background: var(--partial-bg, #eff6ff);
  color: var(--partial-text, #1d4ed8);
  border-color: var(--partial-border, #bfdbfe);
}
.status-partial .status-dot { background: #2f8fd0; }

/* 已登记/已转 — 紫 */
.status-primary {
  background: var(--primary-tag-bg, #f5f3ff);
  color: var(--primary-tag-text, #6d28d9);
  border-color: var(--primary-tag-border, #ddd6fe);
}
.status-primary .status-dot { background: #7a5db0; }

/* 已关闭 — 冷灰 */
.status-info {
  background: var(--info-bg, #f8fafc);
  color: var(--info-text, #64748b);
  border-color: var(--info-border, #e2e8f0);
}
.status-info .status-dot { background: #94a3b8; }

/* 深色模式 */
:global([data-theme='dark']) .status-draft {
  --draft-bg: rgba(120, 113, 108, .15);
  --draft-text: #a8a29e;
  --draft-border: rgba(120, 113, 108, .25);
}
:global([data-theme='dark']) .status-pending {
  --pending-bg: rgba(200, 149, 74, .14);
  --pending-text: #e0a23e;
  --pending-border: rgba(200, 149, 74, .28);
}
:global([data-theme='dark']) .status-approved {
  --approved-bg: rgba(93, 138, 78, .15);
  --approved-text: #43b884;
  --approved-border: rgba(93, 138, 78, .28);
}
:global([data-theme='dark']) .status-danger {
  --danger-bg: rgba(196, 85, 74, .14);
  --danger-text: #e86a6a;
  --danger-border: rgba(196, 85, 74, .28);
}
:global([data-theme='dark']) .status-partial {
  --partial-bg: rgba(74, 123, 168, .14);
  --partial-text: #5bb6e0;
  --partial-border: rgba(74, 123, 168, .28);
}
:global([data-theme='dark']) .status-primary {
  --primary-tag-bg: rgba(138, 109, 156, .14);
  --primary-tag-text: #a98fd0;
  --primary-tag-border: rgba(138, 109, 156, .28);
}
:global([data-theme='dark']) .status-info {
  --info-bg: rgba(100, 116, 139, .12);
  --info-text: #94a3b8;
  --info-border: rgba(100, 116, 139, .24);
}
</style>
