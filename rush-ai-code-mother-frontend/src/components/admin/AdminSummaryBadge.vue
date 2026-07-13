<template>
  <article class="admin-summary-badge" :class="`tone-${tone}`">
    <span class="summary-indicator" aria-hidden="true"></span>
    <div>
      <span class="summary-label">{{ label }}</span>
      <strong :title="title">{{ value }}</strong>
    </div>
  </article>
</template>

<script setup lang="ts">
interface AdminSummaryBadgeProps {
  label: string
  value: string | number
  tone?: 'blue' | 'success' | 'warning' | 'danger' | 'slate'
  title?: string
}

withDefaults(defineProps<AdminSummaryBadgeProps>(), {
  tone: 'blue',
  title: '',
})
</script>

<style scoped>
.admin-summary-badge {
  --summary-color: var(--color-primary);
  --summary-glow: rgba(47, 139, 255, 0.12);
  display: inline-flex;
  align-items: center;
  gap: 11px;
  min-width: 132px;
  min-height: 62px;
  padding: 11px 15px;
  border: 1px solid rgba(119, 150, 187, 0.17);
  border-radius: 17px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow:
    0 12px 28px rgba(67, 95, 130, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(14px);
}

.summary-indicator {
  width: 9px;
  height: 9px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--summary-color);
  box-shadow: 0 0 0 6px var(--summary-glow);
}

.summary-label,
.admin-summary-badge strong {
  display: block;
}

.summary-label {
  color: var(--color-ink-soft);
  font-size: 10px;
  font-weight: 720;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.admin-summary-badge strong {
  max-width: 160px;
  margin-top: 3px;
  overflow: hidden;
  color: var(--color-ink-strong);
  font-size: 16px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tone-success {
  --summary-color: #16a085;
  --summary-glow: rgba(22, 160, 133, 0.12);
}

.tone-warning {
  --summary-color: #d88913;
  --summary-glow: rgba(216, 137, 19, 0.13);
}

.tone-danger {
  --summary-color: #df5c67;
  --summary-glow: rgba(223, 92, 103, 0.12);
}

.tone-slate {
  --summary-color: #71859c;
  --summary-glow: rgba(113, 133, 156, 0.11);
}

@media (max-width: 760px) {
  .admin-summary-badge {
    min-width: min(100%, 150px);
    flex: 1 1 132px;
  }
}
</style>
