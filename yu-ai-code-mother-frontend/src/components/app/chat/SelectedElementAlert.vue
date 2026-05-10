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
  color: #475569;
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
  color: #0f172a;
  font-size: 12px;
  font-weight: 600;
}

.element-tag {
  background: rgba(22, 119, 255, 0.1);
  color: #1677ff;
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
  color: #0f172a;
  font-size: 12px;
}

@media (max-width: 768px) {
  .selected-element-alert {
    margin: 0 16px;
  }
}
</style>
