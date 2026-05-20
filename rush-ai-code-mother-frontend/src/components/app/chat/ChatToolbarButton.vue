<template>
  <a-button class="toolbar-button" v-bind="$attrs">
    <template v-if="$slots.icon" #icon>
      <slot name="icon" />
    </template>
    <span class="button-label">
      <slot />
    </span>
  </a-button>
</template>

<script setup lang="ts">
defineOptions({
  inheritAttrs: false,
})
</script>

<style scoped>
.toolbar-button {
  display: inline-flex;
  align-items: center;
  gap: 0;
  height: 38px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(255, 255, 255, 0.82);
  color: #475569;
  box-shadow: none;
  overflow: hidden;
  white-space: nowrap;
  transition:
    transform 0.24s ease,
    box-shadow 0.24s ease,
    border-color 0.24s ease,
    background 0.24s ease,
    color 0.24s ease,
    padding 0.24s ease;
}

.toolbar-button :deep(.ant-btn-icon) {
  margin-inline-end: 0;
  font-size: 15px;
}

.toolbar-button .button-label {
  max-width: 0;
  overflow: hidden;
  opacity: 0;
  margin-left: 0;
  transform: translateX(-4px);
  transition:
    max-width 0.24s ease,
    opacity 0.2s ease,
    transform 0.24s ease,
    margin-left 0.24s ease;
}

.toolbar-button:hover,
.toolbar-button:focus-visible {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.96);
  color: #0f172a;
  border-color: rgba(148, 163, 184, 0.28);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.08);
}

.toolbar-button:hover .button-label,
.toolbar-button:focus-visible .button-label {
  max-width: 160px;
  opacity: 1;
  margin-left: 8px;
  transform: translateX(0);
}

.toolbar-button.deploy-button {
  color: #1677ff;
  border-color: rgba(22, 119, 255, 0.16);
  background: rgba(22, 119, 255, 0.06);
}

.toolbar-button.deploy-button:hover,
.toolbar-button.deploy-button:focus-visible {
  background: rgba(22, 119, 255, 0.1);
  border-color: rgba(22, 119, 255, 0.22);
  box-shadow: 0 12px 24px rgba(22, 119, 255, 0.12);
}

@media (max-width: 768px) {
  .toolbar-button {
    flex: 0 0 auto;
    justify-content: center;
  }

  .toolbar-button .button-label {
    max-width: 120px;
    opacity: 1;
    margin-left: 8px;
    transform: translateX(0);
  }
}
</style>
