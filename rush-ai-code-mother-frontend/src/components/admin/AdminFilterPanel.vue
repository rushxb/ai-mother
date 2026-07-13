<template>
  <section class="admin-filter-panel">
    <header v-if="title || description" class="filter-panel-header">
      <div>
        <span class="filter-eyebrow">QUERY CONTROLS</span>
        <h2 v-if="title">{{ title }}</h2>
        <p v-if="description">{{ description }}</p>
      </div>
      <slot name="extra" />
    </header>
    <div class="filter-panel-content">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
interface AdminFilterPanelProps {
  title?: string
  description?: string
}

withDefaults(defineProps<AdminFilterPanelProps>(), {
  title: '筛选与检索',
  description: '',
})
</script>

<style scoped>
.admin-filter-panel {
  overflow: hidden;
  border: 1px solid rgba(119, 150, 187, 0.16);
  border-radius: 21px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.91), rgba(247, 251, 255, 0.78)),
    rgba(255, 255, 255, 0.82);
  box-shadow:
    0 18px 52px rgba(70, 101, 140, 0.075),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(18px);
}

.filter-panel-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 21px 0;
}

.filter-eyebrow {
  color: var(--color-primary);
  font-size: 9px;
  font-weight: 820;
  letter-spacing: 0.16em;
}

.filter-panel-header h2 {
  margin: 4px 0 0;
  color: var(--color-ink-strong);
  font-size: 15px;
  line-height: 1.3;
}

.filter-panel-header p {
  margin: 5px 0 0;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.55;
}

.filter-panel-content {
  padding: 18px 21px 19px;
}

.filter-panel-content :deep(.toolbar-form) {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 11px 9px;
}

.filter-panel-content :deep(.ant-form-item) {
  margin: 0;
}

.filter-panel-content :deep(.ant-form-item-label) {
  padding-bottom: 5px;
}

.filter-panel-content :deep(.ant-form-item-label > label) {
  color: #60738a;
  font-size: 11px;
  font-weight: 680;
}

.filter-panel-content :deep(.ant-input),
.filter-panel-content :deep(.ant-input-number),
.filter-panel-content :deep(.ant-select-selector),
.filter-panel-content :deep(.ant-btn) {
  min-height: 39px;
  border-radius: 12px !important;
}

.filter-panel-content :deep(.ant-input),
.filter-panel-content :deep(.ant-input-number),
.filter-panel-content :deep(.ant-select-selector) {
  border-color: rgba(112, 143, 179, 0.18) !important;
  background: rgba(255, 255, 255, 0.74) !important;
  box-shadow: none !important;
}

.filter-panel-content :deep(.ant-input-number-input) {
  min-height: 37px;
}

.filter-panel-content :deep(.ant-btn-primary) {
  border-color: transparent;
  background: linear-gradient(135deg, #267ff1, #49b6ee);
  box-shadow: 0 10px 22px rgba(47, 139, 255, 0.18);
}

@media (max-width: 760px) {
  .filter-panel-header,
  .filter-panel-content {
    padding-right: 17px;
    padding-left: 17px;
  }

  .filter-panel-content :deep(.toolbar-form),
  .filter-panel-content :deep(.ant-space) {
    width: 100%;
  }

  .filter-panel-content :deep(.ant-form-item) {
    width: 100%;
  }

  .filter-panel-content :deep(.ant-input),
  .filter-panel-content :deep(.ant-input-number),
  .filter-panel-content :deep(.ant-select) {
    width: 100% !important;
  }
}
</style>
