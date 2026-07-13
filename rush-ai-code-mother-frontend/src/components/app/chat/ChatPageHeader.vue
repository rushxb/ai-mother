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
        <div
          class="app-name-shell"
          :class="{ editable: canEditAppName, editing: isEditingAppName }"
        >
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
    <div
      class="generation-status"
      :class="{ active: isGenerating, failed: latestGenerationFailed }"
    >
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
  padding: 20px 22px 16px;
}

.chat-header {
  align-items: flex-start;
  border-bottom: 1px solid var(--chat-line, rgba(112, 140, 175, 0.18));
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.62), rgba(255, 255, 255, 0.18));
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
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 12px;
  border: 1px solid rgba(47, 139, 255, 0.14);
  border-radius: 999px;
  background: rgba(47, 139, 255, 0.065);
  color: var(--chat-primary-strong, #176fdd);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.workspace-label::before {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--chat-secondary, #3cc9bb);
  box-shadow: 0 0 0 4px rgba(60, 201, 187, 0.12);
  content: '';
}

.code-gen-type-tag {
  margin: 0;
  border-color: rgba(112, 140, 175, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  color: var(--chat-ink, #2f4158);
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
  font-family: var(--font-display);
  font-size: clamp(21px, 1.55vw, 26px);
  line-height: 1.15;
  font-weight: 720;
  color: var(--chat-ink-strong, #102033);
  letter-spacing: -0.025em;
}

.app-name.clickable {
  cursor: text;
  transition: color 0.24s var(--chat-ease, ease);
}

.app-name.clickable:hover {
  color: var(--chat-primary-strong, #176fdd);
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
  color: var(--chat-ink-strong, #102033);
  font-family: var(--font-display);
  font-size: clamp(21px, 1.55vw, 26px);
  font-weight: 700;
  line-height: 1.15;
}

.app-name-tip {
  color: var(--chat-ink-soft, #6f8198);
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
  border: 1px solid var(--chat-line, rgba(112, 140, 175, 0.18));
  background: rgba(255, 255, 255, 0.66);
  color: var(--chat-ink, #2f4158);
  font-size: 12px;
  font-weight: 600;
  flex: none;
}

.generation-status.active {
  border-color: rgba(47, 139, 255, 0.2);
  background: rgba(47, 139, 255, 0.09);
  color: var(--chat-primary-strong, #176fdd);
}

.generation-status.failed {
  border-color: rgba(229, 72, 104, 0.2);
  background: rgba(229, 72, 104, 0.09);
  color: var(--chat-danger, #e54868);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 5px rgba(112, 140, 175, 0.09);
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
