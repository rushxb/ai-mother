<template>
  <a-alert
      v-if="selectedElementInfo"
      class="selected-element-alert"
      type="info"
      closable
      @close="$emit('close')"
  >
    <template #message>
      <div class="selected-element-info">
        <div class="element-header">
          <span class="element-tag">
            选中元素：{{ selectedElementInfo.tagName.toLowerCase() }}
          </span>
          <span v-if="selectedElementInfo.id" class="element-id">
            #{{ selectedElementInfo.id }}
          </span>
          <span v-if="selectedElementInfo.className" class="element-class">
            .{{ selectedElementInfo.className.split(' ').join('.') }}
          </span>
        </div>
        <div class="element-details">
          <div v-if="selectedElementInfo.textContent" class="element-item">
            内容: {{ selectedElementInfo.textContent.substring(0, 50) }}
            {{ selectedElementInfo.textContent.length > 50 ? '...' : '' }}
          </div>
          <div v-if="selectedElementInfo.pagePath" class="element-item">
            页面路径: {{ selectedElementInfo.pagePath }}
          </div>
          <div class="element-item">
            选择器:
            <code class="element-selector-code">{{ selectedElementInfo.selector }}</code>
          </div>
        </div>
      </div>
    </template>
  </a-alert>
</template>

<script setup lang="ts">
import type { ElementInfo } from '@/utils/visualEditor'

defineProps<{
  selectedElementInfo: ElementInfo | null
}>()

defineEmits<{
  close: []
}>()
</script>

<style scoped>
.selected-element-alert {
  margin: 0 20px;
}

:deep(.selected-element-alert.ant-alert) {
  border-color: var(--chat-line, rgba(112, 140, 175, 0.18));
  border-radius: 14px;
  background:
    linear-gradient(135deg, rgba(47, 139, 255, 0.07), rgba(255, 255, 255, 0.74)),
    var(--chat-surface-soft, rgba(246, 249, 253, 0.88));
  box-shadow: var(--chat-shadow-soft, 0 12px 30px rgba(67, 94, 130, 0.09));
}

.selected-element-info {
  display: grid;
  gap: 10px;
}

.element-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.element-details {
  display: grid;
  gap: 6px;
}

.element-item {
  color: var(--chat-ink, #2f4158);
  font-size: 13px;
  line-height: 1.6;
}

.element-tag,
.element-id,
.element-class {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.05);
  color: var(--chat-ink-strong, #102033);
  font-size: 12px;
  font-weight: 600;
}

.element-tag {
  background: rgba(47, 139, 255, 0.1);
  color: var(--chat-primary-strong, #176fdd);
}

.element-id {
  background: rgba(15, 23, 42, 0.06);
}

.element-class {
  background: rgba(100, 116, 139, 0.08);
}

.element-selector-code {
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.06);
  color: var(--chat-ink-strong, #102033);
  font-size: 12px;
}

@media (max-width: 768px) {
  .selected-element-alert {
    margin: 0 16px;
  }
}
</style>
