<template>
  <div class="section-header chat-header">
    <div class="chat-header-main">
      <ChatToolbarButton type="text" class="back-button" @click="$emit('back')">
        <template #icon>
          <ArrowLeftOutlined />
        </template>
        返回
      </ChatToolbarButton>
      <div class="chat-title-block">
        <div class="chat-title-row">
          <span class="workspace-label">AI Workspace</span>
          <a-tag v-if="codeGenTypeText" color="blue" class="code-gen-type-tag">
            {{ codeGenTypeText }}
          </a-tag>
        </div>
        <div class="app-name-shell" :class="{ editable: canEditAppName, editing: isEditingAppName }">
          <a-input
              v-if="isEditingAppName"
              ref="appNameInputRef"
              :value="appNameDraft"
              class="app-name-input"
              :maxlength="50"
              :bordered="false"
              :disabled="savingAppName"
              placeholder="请输入应用名称"
              @update:value="$emit('update:appNameDraft', $event)"
              @pressEnter="$emit('saveAppName')"
              @blur="$emit('saveAppName')"
              @keydown.esc="$emit('cancelAppNameEdit')"
          />
          <h1
              v-else
              class="app-name"
              :class="{ clickable: canEditAppName }"
              @click="$emit('startEditAppName')"
          >
            {{ appDisplayName }}
          </h1>
          <span v-if="canEditAppName && !isEditingAppName" class="app-name-tip">点击修改</span>
        </div>
      </div>
    </div>
    <div class="generation-status" :class="{ active: isGenerating, failed: latestGenerationFailed }">
      <span class="status-dot"></span>
      {{ generationStatusText }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'
import ChatToolbarButton from './ChatToolbarButton.vue'

defineProps<{
  appDisplayName: string
  appNameDraft: string
  canEditAppName: boolean
  codeGenTypeText?: string
  generationStatusText: string
  isEditingAppName: boolean
  isGenerating: boolean
  latestGenerationFailed: boolean
  savingAppName: boolean
}>()

defineEmits<{
  back: []
  cancelAppNameEdit: []
  saveAppName: []
  startEditAppName: []
  'update:appNameDraft': [value: string]
}>()

const appNameInputRef = ref()

const focusInput = async () => {
  await nextTick()
  appNameInputRef.value?.focus?.()
  appNameInputRef.value?.input?.select?.()
}

defineExpose({
  focusInput,
})
</script>

<style scoped>
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
}

.chat-header {
  align-items: flex-start;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

.chat-header-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.back-button {
  flex: none;
}

.chat-title-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.chat-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.workspace-label {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.05);
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.code-gen-type-tag {
  margin: 0;
  border-radius: 999px;
  font-size: 12px;
  padding-inline: 10px;
}

.app-name-shell {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  max-width: 100%;
}

.app-name-shell.editable {
  cursor: text;
}

.app-name {
  margin: 0;
  font-size: 24px;
  line-height: 1.15;
  font-weight: 700;
  color: #0f172a;
}

.app-name.clickable {
  cursor: text;
  transition: color 0.2s ease;
}

.app-name.clickable:hover {
  color: #1677ff;
}

.app-name-input {
  min-width: 220px;
  max-width: 100%;
}

.app-name-input :deep(.ant-input) {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: #0f172a;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.15;
}

.app-name-tip {
  color: #64748b;
  font-size: 12px;
  white-space: nowrap;
}

.generation-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.04);
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  flex: none;
}

.generation-status.active {
  background: rgba(22, 119, 255, 0.1);
  color: #1677ff;
}

.generation-status.failed {
  background: rgba(239, 68, 68, 0.1);
  color: #dc2626;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 6px rgba(100, 116, 139, 0.08);
}

.generation-status.active .status-dot {
  animation: pulse 1.6s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.16);
    opacity: 0.72;
  }
}

@media (max-width: 768px) {
  .section-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding-left: 16px;
    padding-right: 16px;
  }

  .app-name {
    font-size: 20px;
  }

  .app-name-input :deep(.ant-input) {
    font-size: 20px;
  }
}
</style>
