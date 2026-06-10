import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'

export const useChatPaneResize = (appId: Ref<string | number | undefined>) => {
  const mainContentRef = ref<HTMLElement | null>(null)

  const CHAT_LAYOUT_BREAKPOINT = 1024
  const CHAT_PANE_MIN_WIDTH = 360
  const WORKSPACE_MIN_WIDTH = 360
  const CHAT_SPLITTER_WIDTH = 12
  const CHAT_PANE_DEFAULT_RATIO = 0.4
  const CHAT_PANE_STORAGE_PREFIX = 'app-chat-pane-ratio:'

  const chatPaneWidthRatio = ref(CHAT_PANE_DEFAULT_RATIO)
  const mainContentWidth = ref(0)
  const isChatPaneResizing = ref(false)

  let chatPaneResizeObserver: ResizeObserver | null = null
  let chatPaneResizePointerId: number | null = null
  let chatPaneResizeHandle: HTMLElement | null = null
  let chatPaneResizeOffset = 0

  const chatLayoutStyle = computed(() => ({
    '--chat-pane-width': `${(chatPaneWidthRatio.value * 100).toFixed(4)}%`,
  }))

  const chatPaneCurrentWidth = computed(() => Math.round(mainContentWidth.value * chatPaneWidthRatio.value))

  const chatPaneMaxWidth = computed(() => {
    if (mainContentWidth.value <= CHAT_LAYOUT_BREAKPOINT) {
      return Math.max(CHAT_PANE_MIN_WIDTH, mainContentWidth.value)
    }
    return Math.max(CHAT_PANE_MIN_WIDTH, mainContentWidth.value - WORKSPACE_MIN_WIDTH - CHAT_SPLITTER_WIDTH)
  })

  const updateMainContentWidth = () => {
    mainContentWidth.value = mainContentRef.value?.getBoundingClientRect().width || 0
  }

  const getChatPaneStorageKey = () => {
    if (!appId.value) {
      return ''
    }
    return `${CHAT_PANE_STORAGE_PREFIX}${appId.value}`
  }

  const readStoredChatPaneRatio = () => {
    const storageKey = getChatPaneStorageKey()
    if (!storageKey) {
      return null
    }
    try {
      const rawValue = window.localStorage.getItem(storageKey)
      if (!rawValue) {
        return null
      }
      const parsedValue = Number.parseFloat(rawValue)
      if (!Number.isFinite(parsedValue)) {
        return null
      }
      return parsedValue
    } catch {
      return null
    }
  }

  const saveChatPaneRatio = (ratio: number) => {
    const storageKey = getChatPaneStorageKey()
    if (!storageKey) {
      return
    }
    try {
      window.localStorage.setItem(storageKey, ratio.toFixed(4))
    } catch {
      // ignore storage failures
    }
  }

  const clampChatPaneRatio = (ratio: number, width = mainContentWidth.value) => {
    if (width <= CHAT_LAYOUT_BREAKPOINT) {
      return ratio
    }
    const minRatio = CHAT_PANE_MIN_WIDTH / width
    const maxRatio = (width - WORKSPACE_MIN_WIDTH - CHAT_SPLITTER_WIDTH) / width
    return Math.min(maxRatio, Math.max(minRatio, ratio))
  }

  const syncChatPaneWidthBounds = () => {
    updateMainContentWidth()
    if (mainContentWidth.value > CHAT_LAYOUT_BREAKPOINT) {
      chatPaneWidthRatio.value = clampChatPaneRatio(chatPaneWidthRatio.value, mainContentWidth.value)
    }
  }

  const restoreChatPaneWidthPreference = () => {
    const storedRatio = readStoredChatPaneRatio()
    if (storedRatio !== null) {
      chatPaneWidthRatio.value = storedRatio
    } else {
      chatPaneWidthRatio.value = CHAT_PANE_DEFAULT_RATIO
    }
  }

  const applyChatPaneWidth = (width: number, persist = false) => {
    updateMainContentWidth()
    if (!mainContentWidth.value) {
      return
    }
    const nextWidth = mainContentWidth.value > CHAT_LAYOUT_BREAKPOINT
      ? Math.min(chatPaneMaxWidth.value, Math.max(CHAT_PANE_MIN_WIDTH, width))
      : width
    chatPaneWidthRatio.value = nextWidth / mainContentWidth.value
    if (persist) {
      saveChatPaneRatio(chatPaneWidthRatio.value)
    }
  }

  const stopChatPaneResize = () => {
    if (!isChatPaneResizing.value) {
      return
    }
    isChatPaneResizing.value = false
    if (chatPaneResizeHandle && chatPaneResizePointerId !== null && chatPaneResizeHandle.hasPointerCapture?.(chatPaneResizePointerId)) {
      try {
        chatPaneResizeHandle.releasePointerCapture(chatPaneResizePointerId)
      } catch {
        // ignore pointer capture release failures
      }
    }
    chatPaneResizeHandle = null
    chatPaneResizePointerId = null
    window.removeEventListener('pointermove', handleChatPaneResizeMove)
    window.removeEventListener('pointerup', stopChatPaneResize)
    window.removeEventListener('pointercancel', stopChatPaneResize)
    window.removeEventListener('blur', stopChatPaneResize)
    saveChatPaneRatio(chatPaneWidthRatio.value)
  }

  const handleChatPaneResizeMove = (event: PointerEvent) => {
    if (!isChatPaneResizing.value || event.pointerId !== chatPaneResizePointerId || !mainContentRef.value) {
      return
    }
    const containerRect = mainContentRef.value.getBoundingClientRect()
    const nextWidth = event.clientX - containerRect.left - chatPaneResizeOffset
    applyChatPaneWidth(nextWidth)
  }

  const startChatPaneResize = (event: PointerEvent) => {
    updateMainContentWidth()
    if (event.button !== 0 || mainContentWidth.value <= CHAT_LAYOUT_BREAKPOINT || !mainContentRef.value) {
      return
    }
    event.preventDefault()
    syncChatPaneWidthBounds()
    const containerRect = mainContentRef.value.getBoundingClientRect()
    chatPaneResizePointerId = event.pointerId
    chatPaneResizeHandle = event.currentTarget as HTMLElement | null
    chatPaneResizeOffset = event.clientX - containerRect.left - chatPaneCurrentWidth.value
    isChatPaneResizing.value = true
    chatPaneResizeHandle?.setPointerCapture?.(event.pointerId)
    window.addEventListener('pointermove', handleChatPaneResizeMove)
    window.addEventListener('pointerup', stopChatPaneResize)
    window.addEventListener('pointercancel', stopChatPaneResize)
    window.addEventListener('blur', stopChatPaneResize)
  }

  const handleChatPaneResizeKeydown = (event: KeyboardEvent) => {
    updateMainContentWidth()
    if (mainContentWidth.value <= CHAT_LAYOUT_BREAKPOINT) {
      return
    }
    const step = event.shiftKey ? 48 : 24
    if (event.key === 'ArrowLeft') {
      applyChatPaneWidth(chatPaneCurrentWidth.value - step, true)
      event.preventDefault()
      return
    }
    if (event.key === 'ArrowRight') {
      applyChatPaneWidth(chatPaneCurrentWidth.value + step, true)
      event.preventDefault()
      return
    }
    if (event.key === 'Home') {
      applyChatPaneWidth(CHAT_PANE_MIN_WIDTH, true)
      event.preventDefault()
      return
    }
    if (event.key === 'End') {
      applyChatPaneWidth(chatPaneMaxWidth.value, true)
      event.preventDefault()
    }
  }

  watch(
    () => appId.value,
    () => {
      restoreChatPaneWidthPreference()
      syncChatPaneWidthBounds()
    },
    { immediate: true },
  )

  onMounted(() => {
    if (window.ResizeObserver && mainContentRef.value) {
      chatPaneResizeObserver = new ResizeObserver(() => {
        syncChatPaneWidthBounds()
      })
      chatPaneResizeObserver.observe(mainContentRef.value)
    }
  })

  onUnmounted(() => {
    stopChatPaneResize()
    chatPaneResizeObserver?.disconnect()
    chatPaneResizeObserver = null
  })

  return {
    CHAT_PANE_MIN_WIDTH,
    CHAT_LAYOUT_BREAKPOINT,
    chatLayoutStyle,
    chatPaneCurrentWidth,
    chatPaneMaxWidth,
    chatPaneWidthRatio,
    handleChatPaneResizeKeydown,
    isChatPaneResizing,
    mainContentRef,
    startChatPaneResize,
  }
}
