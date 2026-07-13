import type { ChatMessage } from '@/components/app/chat/types'

let fallbackSequence = 0

/**
 * 创建在当前浏览器会话内稳定且不依赖数组位置的消息 ID。
 * crypto.randomUUID 不可用时使用时间戳和递增序列兜底。
 */
export const createChatMessageId = (namespace = 'local'): string => {
  const randomId = globalThis.crypto?.randomUUID?.()
  if (randomId) {
    return `${namespace}:${randomId}`
  }
  fallbackSequence += 1
  return `${namespace}:${Date.now().toString(36)}:${fallbackSequence.toString(36)}`
}

export const createHistoryMessageId = (historyId: unknown, fallbackIndex: number): string => {
  const normalizedId = typeof historyId === 'string' || typeof historyId === 'number'
    ? String(historyId).trim()
    : ''
  return normalizedId ? `history:${normalizedId}` : createChatMessageId(`history-fallback-${fallbackIndex}`)
}

export const createUserMessage = (content: string, createTime?: string): ChatMessage => ({
  id: createChatMessageId('user'),
  type: 'user',
  content,
  createTime,
})

export const createPendingAiMessage = (createTime?: string): ChatMessage => ({
  id: createChatMessageId('ai'),
  type: 'ai',
  content: '',
  loading: true,
  thinkingActive: true,
  thinkingCollapsed: false,
  createTime,
})
