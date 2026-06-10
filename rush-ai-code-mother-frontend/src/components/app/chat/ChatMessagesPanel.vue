<template>
  <div class="messages-panel">
    <div ref="messagesContainerRef" class="messages-container" @scroll="$emit('scroll')">
      <div v-if="hasMoreHistory" class="load-more-container">
        <ChatToolbarButton
            type="text"
            class="load-more-button"
            :loading="loadingHistory"
            size="small"
            @click="$emit('loadMoreHistory')"
        >
          <template #icon>
            <DownOutlined />
          </template>
          更多历史
        </ChatToolbarButton>
      </div>
      <div
          v-for="(message, index) in messages"
          :key="index"
          class="message-item"
          :class="`message-item-${message.type}`"
      >
        <div v-if="message.type === 'user'" class="message-row message-row-user">
          <div class="message-body message-body-user">
            <div class="message-content">{{ message.content }}</div>
            <div class="message-actions message-actions-user">
              <ChatToolbarButton
                  size="small"
                  type="text"
                  class="message-action-button"
                  :disabled="isGenerating || !isOwner"
                  @click="$emit('editUserMessage', index)"
              >
                <template #icon>
                  <EditOutlined />
                </template>
                编辑
              </ChatToolbarButton>
              <span v-if="message.createTime" class="message-time">{{ formatMessageTime(message.createTime) }}</span>
            </div>
          </div>
          <div class="message-avatar">
            <a-avatar :src="loginUserAvatar" />
          </div>
        </div>
        <div v-else class="message-row message-row-ai">
          <div class="message-avatar">
            <a-avatar :src="aiAvatar" />
          </div>
          <div class="message-body message-body-ai">
            <div class="message-content">
              <div
                  v-if="message.thinkingContent"
                  class="thinking-block"
                  :class="{ collapsed: message.thinkingCollapsed }"
              >
                <button
                    type="button"
                    class="thinking-header"
                    @click="message.thinkingCollapsed = !message.thinkingCollapsed"
                >
                  <span class="thinking-status">
                    <span
                        class="thinking-dot"
                        :class="{ active: message.thinkingActive && !message.thinkingCollapsed }"
                    ></span>
                    {{ message.thinkingActive ? 'AI 正在思考' : '已完成思考' }}
                  </span>
                  <DownOutlined class="thinking-toggle" />
                </button>
                <div v-show="!message.thinkingCollapsed" class="thinking-content">
                  {{ message.thinkingContent }}
                </div>
              </div>
              <template v-if="message.content">
                <template
                    v-for="(segment, segmentIndex) in getAiMessageSegments(message)"
                    :key="`${index}-${segmentIndex}`"
                >
                  <MarkdownRenderer
                      v-if="segment.type === 'markdown'"
                      :content="segment.content"
                  />
                  <button
                      v-else
                      type="button"
                      class="tool-call-file-card"
                      :class="{ active: isToolCallFileActive(segment.filePath) }"
                      @click="$emit('openToolCallFile', segment.filePath)"
                  >
                    <span
                        class="file-type-icon"
                        :class="`file-type-icon-${getToolCallFileIcon(segment.filePath).type}`"
                    >
                      {{ getToolCallFileIcon(segment.filePath).label }}
                    </span>
                    <span class="tool-call-file-main">
                      <span class="tool-call-file-label">{{ segment.label }}</span>
                      <span class="tool-call-file-path">{{ segment.filePath }}</span>
                    </span>
                    <span class="tool-call-file-action">查看文件</span>
                  </button>
                </template>
              </template>
              <div v-if="message.agentEvents?.length" class="agent-timeline">
                <div
                    v-for="agentEvent in message.agentEvents"
                    :key="`${agentEvent.dagNode || agentEvent.stage}-${agentEvent.agent}-${agentEvent.status}`"
                    class="agent-timeline-item"
                    :class="`agent-timeline-item-${agentEvent.status}`"
                >
                  <span class="agent-timeline-dot"></span>
                  <div class="agent-timeline-copy">
                    <div class="agent-timeline-title">
                      <strong>{{ agentEvent.agent }}</strong>
                      <span v-if="agentEvent.dagNode" class="agent-node-chip">{{ agentEvent.dagNode }}</span>
                      <span v-if="agentEvent.durationMs" class="agent-duration">{{ formatDuration(agentEvent.durationMs) }}</span>
                    </div>
                    <span>{{ agentEvent.summary }}</span>
                    <div v-if="agentEvent.qualityGate || agentEvent.recoverable" class="agent-timeline-meta">
                      <span v-if="agentEvent.qualityGate">门禁：{{ agentEvent.qualityGate }}</span>
                      <span v-if="agentEvent.recoverable">可恢复</span>
                    </div>
                  </div>
                </div>
              </div>
              <div
                  v-if="message.buildResult"
                  class="build-result-card"
                  :class="`build-result-${message.buildResult.status}`"
              >
                <div class="build-result-main">
                  <CheckCircleOutlined v-if="message.buildResult.status === 'success'" />
                  <CloseCircleOutlined v-else />
                  <div class="build-result-copy">
                    <strong>{{ message.buildResult.status === 'success' ? '构建通过' : '构建失败' }}</strong>
                    <span>{{ message.buildResult.summary }}</span>
                  </div>
                </div>
                <a-tag class="build-stage-tag" :color="message.buildResult.status === 'success' ? 'success' : 'error'">
                  {{ message.buildResult.stage }}
                </a-tag>
              </div>
              <div v-if="message.generationFailed && isOwner" class="message-retry-row">
                <ChatToolbarButton
                    size="small"
                    type="text"
                    class="message-retry-button"
                    @click="$emit('retryLastGeneration')"
                >
                  <template #icon>
                    <RedoOutlined />
                  </template>
                  重试本轮生成
                </ChatToolbarButton>
              </div>
              <div v-if="message.loading" class="loading-indicator">
                <a-spin size="small" />
                <span>AI 正在思考...</span>
              </div>
            </div>
            <div class="message-actions message-actions-ai">
              <ChatToolbarButton
                  size="small"
                  type="text"
                  class="message-action-button"
                  @click="copyMessageAsMarkdown(message, index)"
              >
                <template #icon>
                  <CopyOutlined />
                </template>
                {{ copiedIndices.has(index) ? '已复制' : '复制' }}
              </ChatToolbarButton>
              <ChatToolbarButton
                  size="small"
                  type="text"
                  class="message-action-button"
                  :disabled="isGenerating || !isOwner"
                  @click="$emit('regenerateAiMessage', index)"
              >
                <template #icon>
                  <RedoOutlined />
                </template>
                重新生成
              </ChatToolbarButton>
              <ChatToolbarButton
                  size="small"
                  type="text"
                  class="message-action-button"
                  :class="{ active: message.feedback === 'like' }"
                  @click="$emit('toggleAiFeedback', index, 'like')"
              >
                <template #icon>
                  <LikeOutlined />
                </template>
                喜欢
              </ChatToolbarButton>
              <ChatToolbarButton
                  size="small"
                  type="text"
                  class="message-action-button"
                  :class="{ active: message.feedback === 'dislike' }"
                  @click="$emit('toggleAiFeedback', index, 'dislike')"
              >
                <template #icon>
                  <DislikeOutlined />
                </template>
                不喜欢
              </ChatToolbarButton>
              <span v-if="message.createTime" class="message-time">{{ formatMessageTime(message.createTime) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <Transition name="scroll-bottom-fade">
      <button
          v-if="showScrollToBottom"
          type="button"
          class="scroll-to-bottom"
          @click="$emit('scrollToBottom')"
      >
        <VerticalAlignBottomOutlined />
        <span class="button-label">回到底部</span>
      </button>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  DislikeOutlined,
  DownOutlined,
  EditOutlined,
  LikeOutlined,
  RedoOutlined,
  VerticalAlignBottomOutlined,
} from '@ant-design/icons-vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import type { AiMessageSegment, ChatMessage, FileIconMeta } from './types'
import ChatToolbarButton from './ChatToolbarButton.vue'

defineProps<{
  aiAvatar: string
  formatDuration: (durationMs?: number) => string
  formatMessageTime: (time?: string) => string
  getAiMessageSegments: (message: ChatMessage) => AiMessageSegment[]
  getToolCallFileIcon: (filePath: string) => FileIconMeta
  hasMoreHistory: boolean
  isGenerating: boolean
  isOwner: boolean
  isToolCallFileActive: (filePath: string) => boolean
  loadingHistory: boolean
  loginUserAvatar: string
  messages: ChatMessage[]
  showScrollToBottom: boolean
}>()

defineEmits<{
  editUserMessage: [index: number]
  loadMoreHistory: []
  openToolCallFile: [filePath: string]
  regenerateAiMessage: [index: number]
  retryLastGeneration: []
  scroll: []
  scrollToBottom: []
  toggleAiFeedback: [index: number, feedback: 'like' | 'dislike']
}>()

const messagesContainerRef = ref<HTMLElement>()
const copiedIndices = ref(new Set<number>())

const copyMessageAsMarkdown = async (message: ChatMessage, index: number) => {
  const text = message.content ?? ''
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
  copiedIndices.value.add(index)
  setTimeout(() => { copiedIndices.value.delete(index) }, 2000)
}

defineExpose({
  container: messagesContainerRef,
  getContainer: () => messagesContainerRef.value,
})
</script>

<style scoped>
.messages-panel {
  position: relative;
  flex: 1;
  min-height: 0;
}

.messages-container {
  height: 100%;
  padding: 18px 24px 80px;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.scroll-to-bottom {
  position: absolute;
  right: 24px;
  bottom: 18px;
  display: inline-flex;
  align-items: center;
  gap: 0;
  height: 38px;
  padding: 0 12px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  color: #0f172a;
  font-size: 13px;
  font-weight: 600;
  box-shadow:
    0 14px 28px rgba(15, 23, 42, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(14px);
  cursor: pointer;
  transition:
    transform 0.24s ease,
    box-shadow 0.24s ease,
    opacity 0.24s ease,
    background 0.24s ease;
}

.scroll-to-bottom .button-label {
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

.scroll-to-bottom:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.96);
  box-shadow:
    0 18px 34px rgba(15, 23, 42, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.96);
}

.scroll-to-bottom:hover .button-label,
.scroll-to-bottom:focus-visible .button-label {
  max-width: 96px;
  opacity: 1;
  margin-left: 8px;
  transform: translateX(0);
}

.scroll-to-bottom:focus-visible {
  outline: 2px solid rgba(59, 130, 246, 0.26);
  outline-offset: 3px;
}

.scroll-bottom-fade-enter-active,
.scroll-bottom-fade-leave-active {
  transition:
    opacity 0.22s ease,
    transform 0.22s ease;
}

.scroll-bottom-fade-enter-from,
.scroll-bottom-fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

.message-item {
  margin-bottom: 16px;
  animation: messageRise 0.28s ease;
}

.message-item-user + .message-item-ai,
.message-item-ai + .message-item-user {
  margin-top: 20px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.message-row-user {
  justify-content: flex-end;
}

.message-row-ai {
  justify-content: flex-start;
}

.message-body {
  max-width: 70%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-body-user {
  align-items: flex-end;
}

.message-body-ai {
  align-items: flex-start;
}

.message-content {
  max-width: 100%;
  padding: 14px 18px;
  border-radius: 20px;
  line-height: 1.7;
  word-wrap: break-word;
  transition: transform 0.24s ease, box-shadow 0.24s ease;
}

.message-content:hover {
  transform: translateY(-1px);
}

.message-body-user .message-content {
  background: linear-gradient(135deg, #1677ff 0%, #3b82f6 100%);
  color: white;
  border-top-right-radius: 8px;
  box-shadow: 0 14px 30px rgba(22, 119, 255, 0.2);
}

.message-body-ai .message-content {
  background: rgba(255, 255, 255, 0.92);
  color: #0f172a;
  padding: 14px 18px;
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-top-left-radius: 8px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.message-body-ai .message-content > :deep(.markdown-content + .markdown-content) {
  margin-top: 10px;
}

.message-actions {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 24px;
  color: #94a3b8;
  font-size: 12px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.18s ease;
}

.message-item:last-child .message-actions,
.message-row:hover .message-actions,
.message-row:focus-within .message-actions {
  opacity: 1;
  pointer-events: auto;
}

.message-actions-user {
  justify-content: flex-end;
}

.message-actions-ai {
  justify-content: flex-start;
}

.message-action-button {
  height: 24px;
  padding: 0 7px;
  border-radius: 999px;
  color: #64748b;
  font-size: 12px;
}

.message-action-button.active {
  color: #1677ff;
  background: rgba(22, 119, 255, 0.08);
}

.message-time {
  padding: 0 4px;
  line-height: 24px;
  white-space: nowrap;
}

.thinking-block {
  margin-bottom: 12px;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.88);
}

.thinking-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 38px;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.thinking-status {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.thinking-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #94a3b8;
}

.thinking-dot.active {
  background: #1677ff;
  box-shadow: 0 0 0 5px rgba(22, 119, 255, 0.1);
  animation: pulse 1.6s ease-in-out infinite;
}

.thinking-toggle {
  flex: none;
  color: #94a3b8;
  transition: transform 0.2s ease;
}

.thinking-block.collapsed .thinking-toggle {
  transform: rotate(-90deg);
}

.thinking-content {
  max-height: 220px;
  overflow: auto;
  padding: 0 12px 12px 27px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.tool-call-file-card {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.82);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
}

.tool-call-file-card:hover {
  transform: translateY(-1px);
  border-color: rgba(22, 119, 255, 0.26);
  background: rgba(240, 247, 255, 0.92);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.06);
}

.tool-call-file-card.active {
  border-color: rgba(22, 119, 255, 0.32);
  background: rgba(240, 247, 255, 0.96);
}

.tool-call-file-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tool-call-file-label {
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
}

.tool-call-file-path {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-call-file-action {
  flex: none;
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
}

.agent-timeline {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(248, 250, 252, 0.88);
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.agent-timeline-item {
  display: flex;
  gap: 10px;
  padding: 8px 0;
}

.agent-timeline-dot {
  flex: none;
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 50%;
  background: #94a3b8;
}

.agent-timeline-item-running .agent-timeline-dot {
  background: #1677ff;
  box-shadow: 0 0 0 5px rgba(22, 119, 255, 0.1);
  animation: pulse 1.6s ease-in-out infinite;
}

.agent-timeline-item-done .agent-timeline-dot {
  background: #16a34a;
}

.agent-timeline-item-failed .agent-timeline-dot {
  background: #dc2626;
}

.agent-timeline-copy {
  min-width: 0;
  flex: 1;
  color: #475569;
  font-size: 13px;
}

.agent-timeline-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 2px;
}

.agent-timeline-title strong {
  color: #0f172a;
  font-size: 13px;
}

.agent-node-chip,
.agent-duration {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: rgba(22, 119, 255, 0.08);
  color: #1677ff;
  font-size: 11px;
  font-weight: 700;
}

.agent-duration {
  background: rgba(15, 23, 42, 0.05);
  color: #64748b;
}

.agent-timeline-copy span {
  line-height: 1.6;
}

.agent-timeline-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.agent-timeline-meta span {
  min-height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.05);
  color: #64748b;
  font-size: 11px;
}

.message-avatar {
  flex: none;
}

:deep(.message-avatar .ant-avatar) {
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
}

.loading-indicator {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: #64748b;
}

.build-result-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(248, 250, 252, 0.9);
}

.build-result-main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 0;
}

.build-result-main :deep(.anticon) {
  margin-top: 3px;
  font-size: 16px;
}

.build-result-success {
  border-color: rgba(34, 197, 94, 0.18);
  background: rgba(240, 253, 244, 0.92);
}

.build-result-success .build-result-main :deep(.anticon) {
  color: #16a34a;
}

.build-result-failed {
  border-color: rgba(239, 68, 68, 0.18);
  background: rgba(254, 242, 242, 0.92);
}

.build-result-failed .build-result-main :deep(.anticon) {
  color: #dc2626;
}

.build-result-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.build-result-copy strong {
  color: #0f172a;
  font-size: 13px;
  line-height: 1.5;
}

.build-result-copy span {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.build-stage-tag {
  margin: 0;
  flex: none;
}

.message-retry-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.load-more-container {
  text-align: center;
  padding: 4px 0 10px;
  margin-bottom: 12px;
}

.file-type-icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(100, 116, 139, 0.1);
  color: #475569;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.file-type-icon-html,
.file-type-icon-xml {
  background: rgba(249, 115, 22, 0.12);
  color: #ea580c;
}

.file-type-icon-css,
.file-type-icon-scss {
  background: rgba(14, 165, 233, 0.12);
  color: #0284c7;
}

.file-type-icon-js,
.file-type-icon-jsx {
  background: rgba(234, 179, 8, 0.16);
  color: #a16207;
}

.file-type-icon-ts,
.file-type-icon-tsx {
  background: rgba(59, 130, 246, 0.12);
  color: #2563eb;
}

.file-type-icon-vue {
  background: rgba(34, 197, 94, 0.12);
  color: #16a34a;
}

.file-type-icon-json {
  background: rgba(168, 85, 247, 0.12);
  color: #7c3aed;
}

.file-type-icon-md {
  background: rgba(15, 23, 42, 0.08);
  color: #334155;
}

.file-type-icon-image {
  background: rgba(236, 72, 153, 0.12);
  color: #db2777;
}

.file-type-icon-yaml,
.file-type-icon-text,
.file-type-icon-env,
.file-type-icon-lock,
.file-type-icon-file {
  background: rgba(100, 116, 139, 0.1);
  color: #475569;
}

.file-type-icon-sql {
  background: rgba(20, 184, 166, 0.12);
  color: #0f766e;
}

.file-type-icon-go {
  background: rgba(6, 182, 212, 0.12);
  color: #0891b2;
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

@keyframes messageRise {
  from {
    transform: translateY(8px);
    opacity: 0;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .messages-container {
    padding: 12px 16px 80px;
  }

  .message-body {
    max-width: 85%;
  }

  .scroll-to-bottom {
    right: 16px;
    bottom: 14px;
    height: 36px;
    padding: 0 12px;
  }
}
</style>
