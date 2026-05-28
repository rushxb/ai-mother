npm <template>
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
            @keydown.enter.prevent="$emit('send')"
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
          @keydown.enter.prevent="$emit('send')"
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

defineEmits<{
  optimize: []
  send: []
  sendButtonClick: []
  'update:modelValue': [value: string]
}>()
</script>

<style scoped>
.input-container {
  padding: 16px 20px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(248, 250, 252, 0.92) 100%);
}

.input-wrapper {
  position: relative;
  padding: 10px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow:
    0 14px 32px rgba(15, 23, 42, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
}

:deep(.input-wrapper .ant-input) {
  padding: 14px 190px 14px 16px;
  border: 0;
  background: transparent;
  color: #0f172a;
  box-shadow: none;
  resize: none;
  font-size: 15px;
  line-height: 1.7;
}

:deep(.input-wrapper .ant-input:focus) {
  box-shadow: none;
}

:deep(.input-wrapper .ant-input::placeholder) {
  color: #94a3b8;
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
  color: #1677ff;
  border-color: rgba(22, 119, 255, 0.14);
  background: rgba(22, 119, 255, 0.06);
  font-weight: 600;
}

.send-button {
  color: white;
  border-color: transparent;
  background: #1677ff;
  box-shadow: 0 12px 24px rgba(22, 119, 255, 0.22);
}

.send-button.ant-btn-dangerous {
  background: rgba(239, 68, 68, 0.08);
  color: #dc2626;
  border-color: rgba(239, 68, 68, 0.16);
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
