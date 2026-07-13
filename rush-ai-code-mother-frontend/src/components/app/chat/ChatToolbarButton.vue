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
  border: 1px solid var(--chat-line, rgba(112, 140, 175, 0.18));
  background: var(--chat-surface, rgba(255, 255, 255, 0.88));
  color: var(--chat-ink, #2f4158);
  box-shadow: none;
  overflow: hidden;
  white-space: nowrap;
  transition:
    transform 0.28s var(--chat-ease, ease),
    box-shadow 0.28s var(--chat-ease, ease),
    border-color 0.28s var(--chat-ease, ease),
    background 0.28s var(--chat-ease, ease),
    color 0.28s var(--chat-ease, ease),
    padding 0.28s var(--chat-ease, ease);
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
  background: var(--chat-surface-strong, rgba(255, 255, 255, 0.97));
  color: var(--chat-ink-strong, #102033);
  border-color: var(--chat-line-strong, rgba(86, 120, 161, 0.28));
  box-shadow: var(--chat-shadow-soft, 0 12px 30px rgba(67, 94, 130, 0.09));
}

.toolbar-button:hover .button-label,
.toolbar-button:focus-visible .button-label {
  max-width: 160px;
  opacity: 1;
  margin-left: 8px;
  transform: translateX(0);
}

.toolbar-button.deploy-button {
  color: var(--chat-primary-strong, #176fdd);
  border-color: rgba(47, 139, 255, 0.2);
  background: rgba(47, 139, 255, 0.075);
}

.toolbar-button.deploy-button:hover,
.toolbar-button.deploy-button:focus-visible {
  background: rgba(47, 139, 255, 0.12);
  border-color: rgba(47, 139, 255, 0.28);
  box-shadow: 0 12px 26px rgba(47, 139, 255, 0.16);
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
