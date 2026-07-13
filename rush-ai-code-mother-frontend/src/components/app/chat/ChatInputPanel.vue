<template>
  <div class="input-container">
    <div class="input-wrapper">
      <a-tooltip v-if="!isOwner" title="无法在别人的作品下对话哦~" placement="top">
        <a-textarea
          :value="modelValue"
          :placeholder="placeholder"
          :rows="3"
          :maxlength="1000"
          :disabled="isGenerating || isOptimizingPrompt || !isOwner"
          @update:value="$emit('update:modelValue', $event)"
          @keydown="handleKeydown"
        />
      </a-tooltip>
      <a-textarea
        v-else
        :value="modelValue"
        :placeholder="placeholder"
        :rows="3"
        :maxlength="1000"
        :disabled="isGenerating || isOptimizingPrompt"
        @update:value="$emit('update:modelValue', $event)"
        @keydown="handleKeydown"
      />
      <div class="input-actions">
        <a-tooltip title="优化提示词" placement="top">
          <ChatToolbarButton
            class="optimize-button"
            type="text"
            :loading="isOptimizingPrompt"
            :disabled="!canOptimizePrompt"
            @click="$emit('optimize')"
          >
            <template #icon>
              <BulbOutlined />
            </template>
            优化
          </ChatToolbarButton>
        </a-tooltip>
        <ChatToolbarButton
          class="send-button"
          type="primary"
          :danger="isGenerating"
          :loading="stoppingGeneration"
          :disabled="!isOwner || isOptimizingPrompt"
          @click="$emit('sendButtonClick')"
        >
          <template #icon>
            <StopOutlined v-if="isGenerating" />
            <SendOutlined v-else />
          </template>
          {{ isGenerating ? '停止' : '发送' }}
        </ChatToolbarButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { BulbOutlined, SendOutlined, StopOutlined } from '@ant-design/icons-vue'
import ChatToolbarButton from './ChatToolbarButton.vue'

defineProps<{
  canOptimizePrompt: boolean
  isGenerating: boolean
  isOptimizingPrompt: boolean
  isOwner: boolean
  modelValue: string
  placeholder: string
  stoppingGeneration: boolean
}>()

const emit = defineEmits<{
  optimize: []
  send: []
  sendButtonClick: []
  'update:modelValue': [value: string]
}>()

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  emit('send')
}
</script>

<style scoped>
.input-container {
  padding: 14px 20px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), rgba(239, 246, 253, 0.9));
}

.input-wrapper {
  position: relative;
  padding: 9px;
  overflow: hidden;
  border: 1px solid var(--chat-line, rgba(112, 140, 175, 0.18));
  border-radius: 21px;
  background: var(--chat-surface-strong, rgba(255, 255, 255, 0.97));
  box-shadow:
    var(--chat-shadow-soft, 0 12px 30px rgba(67, 94, 130, 0.09)),
    inset 0 1px 0 rgba(255, 255, 255, 0.96);
  transition:
    border-color 0.28s var(--chat-ease, ease),
    box-shadow 0.28s var(--chat-ease, ease),
    transform 0.28s var(--chat-ease, ease);
}

.input-wrapper::before {
  position: absolute;
  top: 0;
  right: 14%;
  left: 14%;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(47, 139, 255, 0.48), transparent);
  content: '';
  opacity: 0;
  transition: opacity 0.24s ease;
}

.input-wrapper:focus-within {
  border-color: rgba(47, 139, 255, 0.34);
  box-shadow:
    0 18px 42px rgba(47, 139, 255, 0.13),
    0 0 0 4px rgba(47, 139, 255, 0.07);
  transform: translateY(-1px);
}

.input-wrapper:focus-within::before {
  opacity: 1;
}

:deep(.input-wrapper .ant-input) {
  padding: 14px 190px 14px 16px;
  border: 0;
  background: transparent;
  color: var(--chat-ink-strong, #102033);
  box-shadow: none;
  resize: none;
  font-size: 15px;
  line-height: 1.7;
}

:deep(.input-wrapper .ant-input:focus) {
  box-shadow: none;
}

:deep(.input-wrapper .ant-input::placeholder) {
  color: var(--chat-ink-soft, #6f8198);
}

.input-actions {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.optimize-button {
  color: var(--chat-primary-strong, #176fdd);
  border-color: rgba(47, 139, 255, 0.16);
  background: rgba(47, 139, 255, 0.07);
  font-weight: 600;
}

.send-button {
  color: white;
  border-color: transparent;
  background: linear-gradient(
    135deg,
    var(--chat-primary, #2f8bff),
    var(--chat-primary-strong, #176fdd)
  );
  box-shadow: 0 12px 26px rgba(47, 139, 255, 0.25);
}

.send-button.ant-btn-dangerous {
  background: rgba(229, 72, 104, 0.08);
  color: var(--chat-danger, #e54868);
  border-color: rgba(229, 72, 104, 0.18);
  box-shadow: none;
}

@media (max-width: 768px) {
  .input-container {
    padding: 12px 16px 16px;
  }

  :deep(.input-wrapper .ant-input) {
    padding-right: 16px;
    padding-bottom: 58px;
  }

  .input-actions :deep(.button-label) {
    max-width: 120px;
    opacity: 1;
    margin-left: 8px;
    transform: translateX(0);
  }
}
</style>
