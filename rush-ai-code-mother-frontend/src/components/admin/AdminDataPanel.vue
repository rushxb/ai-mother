<template>
  <section class="admin-data-panel">
    <header v-if="title || description || $slots.extra" class="data-panel-header">
      <div>
        <span class="data-eyebrow">LIVE DATASET</span>
        <h2 v-if="title">{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
      <div v-if="$slots.extra" class="data-panel-extra">
        <slot name="extra" />
      </div>
    </header>
    <div class="data-panel-content">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
interface AdminDataPanelProps {
  title?: string
  description?: string
}

withDefaults(defineProps<AdminDataPanelProps>(), {
  title: '',
  description: '',
})
</script>

<style scoped>
.admin-data-panel {
  overflow: hidden;
  border: 1px solid rgba(119, 150, 187, 0.16);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.87);
  box-shadow:
    0 20px 56px rgba(70, 101, 140, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(18px);
}

.data-panel-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  min-height: 70px;
  padding: 17px 21px 14px;
  border-bottom: 1px solid rgba(119, 150, 187, 0.12);
}

.data-eyebrow {
  color: #7b8da3;
  font-size: 9px;
  font-weight: 820;
  letter-spacing: 0.15em;
}

.data-panel-header h2 {
  margin: 4px 0 0;
  color: var(--color-ink-strong);
  font-size: 16px;
  line-height: 1.3;
}

.data-panel-header p {
  margin: 4px 0 0;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.55;
}

.data-panel-extra {
  flex: 0 0 auto;
}

.data-panel-content {
  padding: 6px 12px 12px;
}

.data-panel-content :deep(.load-error) {
  margin: 10px 8px;
}

.data-panel-content :deep(.ant-table),
.data-panel-content :deep(.ant-table-container) {
  background: transparent;
}

.data-panel-content :deep(.ant-table-container) {
  overflow: hidden;
  border-radius: 15px;
}

.data-panel-content :deep(.ant-table-thead > tr > th) {
  padding-block: 14px;
  border-bottom: 1px solid rgba(119, 150, 187, 0.14);
  background: rgba(244, 248, 252, 0.9);
  color: #526579;
  font-size: 12px;
  font-weight: 680;
}

.data-panel-content :deep(.ant-table-tbody > tr > td) {
  padding-block: 14px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.78);
  vertical-align: middle;
}

.data-panel-content :deep(.ant-table-tbody > tr:hover > td) {
  background: rgba(47, 139, 255, 0.035) !important;
}

.data-panel-content :deep(.ant-pagination) {
  margin-right: 8px;
}

.data-panel-content :deep(.ant-pagination-item),
.data-panel-content :deep(.ant-pagination-prev .ant-pagination-item-link),
.data-panel-content :deep(.ant-pagination-next .ant-pagination-item-link),
.data-panel-content :deep(.ant-select-selector),
.data-panel-content :deep(.ant-btn) {
  border-radius: 10px !important;
}

.data-panel-content :deep(.ant-pagination-item-active) {
  border-color: rgba(47, 139, 255, 0.34);
  background: rgba(47, 139, 255, 0.08);
}

@media (max-width: 760px) {
  .data-panel-header {
    align-items: flex-start;
    flex-direction: column;
    min-height: 0;
    padding: 16px 17px 13px;
  }

  .data-panel-content {
    padding-inline: 6px;
  }
}
</style>
