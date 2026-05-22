import { defineComponent, ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import dayjs from 'dayjs'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  chatToGenCode,
  stopChatToGenCode,
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
  optimizePrompt,
  listAppCodeFiles,
  getAppCodeFileContent,
  saveAppCodeFile,
  syncAppDeployment,
  updateApp,
  startDevServer,
  stopDevServer,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import request from '@/request'

import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import { API_BASE_URL, getDeployUrl, getStaticPreviewUrl, getDevServerPreviewUrl, getPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import ChatPageHeader from '@/components/app/chat/ChatPageHeader.vue'
import ChatMessagesPanel from '@/components/app/chat/ChatMessagesPanel.vue'
import ChatInputPanel from '@/components/app/chat/ChatInputPanel.vue'
import SelectedElementAlert from '@/components/app/chat/SelectedElementAlert.vue'
import AppWorkspacePanel from '@/components/app/chat/AppWorkspacePanel.vue'
import type { ChatMessage, FileTreeNode, WorkspaceTabKey } from '@/components/app/chat/types'
import {
  appendPreviewCacheBuster,
  buildModifiedFilePreview,
  codeLanguageAliasMap,
  collectDirectoryKeys,
  createFileTreeNode,
  extractDeployKey,
  type GenerationStreamEvent,
  getFileIconMeta,
  formatDuration,
  getAiMessageSegments,
  getGenerationErrorDisplay,
  parseGenerationStreamEvent,
  isCompileFailureMessage,
  isFrontendEditableFile,
  mapFileTreeNodes,
  normalizeFilePath,
  upsertAgentEvent,
  decorateMessageWithBuildResult,
} from './appChatUtils'
import { useChatPaneResize } from './useChatPaneResize'

export default defineComponent({
  components: {
    AppDetailModal,
    DeploySuccessModal,
    ChatPageHeader,
    ChatMessagesPanel,
    ChatInputPanel,
    SelectedElementAlert,
    AppWorkspacePanel,
  },
  setup() {

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)

// 应用信息
const appInfo = ref<API.AppVO>()
const appId = ref<any>()
const isEditingAppName = ref(false)
const appNameDraft = ref('')
const savingAppName = ref(false)

const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isGenerating = ref(false)
const stoppingGeneration = ref(false)
const isOptimizingPrompt = ref(false)
const isNearBottom = ref(true)
const showScrollToBottom = ref(false)

// 对话历史相关
const loadingHistory = ref(false)
const hasMoreHistory = ref(false)
const lastCreateTime = ref<string>()
const historyLoaded = ref(false)
const refreshingGenerationState = ref(false)
const lastGenerationPrompt = ref('')
const editingMessageIndex = ref<number | null>(null)

let generationPollingTimer: ReturnType<typeof window.setInterval> | null = null
let activeEventSource: EventSource | null = null
const AUTO_SCROLL_THRESHOLD = 96
const SCROLL_BUTTON_THRESHOLD = 180

// 预览相关
const previewUrl = ref('')
const previewReady = ref(false)
const previewRefreshKey = ref(0)
const devServerRunning = ref(false)

const activeWorkspaceTab = ref<WorkspaceTabKey>('preview')
const generationPhase = ref<'idle' | 'codegen' | 'build' | 'repair' | 'done' | 'failed'>('idle')
const currentAgentStageText = ref('')

const generationSteps = [
  { key: 'codegen', label: '生成代码', index: 0 },
  { key: 'build', label: '构建校验', index: 1 },
  { key: 'preview', label: '预览就绪', index: 2 },
]

const loadingFiles = ref(false)
const loadingFileContent = ref(false)
const savingFile = ref(false)
const syncingDeploy = ref(false)
const fileTreeData = ref<FileTreeNode[]>([])
const expandedFileTreeKeys = ref<Array<string | number>>([])
const selectedFilePath = ref('')
const selectedFileName = ref('')
const fileContent = ref('')
const savedFileContent = ref('')
const openFileTabs = ref<Array<{ path: string; name: string }>>([])
const isStreamingFilePreview = ref(false)
const streamingFilePath = ref('')
const streamingFileFinalContent = ref('')
const streamingFileDisplayContent = ref('')
let streamingFileTimer: ReturnType<typeof window.setInterval> | null = null
const loadedFileContentCache = new Map<string, string>()

// 部署相关
const deploying = ref(false)
const deployModalVisible = ref(false)
const deployUrl = ref('')

// 下载相关
const downloading = ref(false)

// 可视化编辑相关
const isEditMode = ref(false)
const selectedElementInfo = ref<ElementInfo | null>(null)
const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo: ElementInfo) => {
    selectedElementInfo.value = elementInfo
  },
})
const chatPageHeaderRef = ref<InstanceType<typeof ChatPageHeader> | null>(null)
const messagesPanelRef = ref<InstanceType<typeof ChatMessagesPanel> | null>(null)
const chatSplitterRef = ref<HTMLElement | null>(null)
const {
  CHAT_LAYOUT_BREAKPOINT,
  CHAT_PANE_MIN_WIDTH,
  chatLayoutStyle,
  chatPaneCurrentWidth,
  chatPaneMaxWidth,
  chatPaneWidthRatio,
  handleChatPaneResizeKeydown,
  isChatPaneResizing,
  mainContentRef,
  startChatPaneResize,
} = useChatPaneResize(appId)

const handleIframeMessage = (event: MessageEvent) => {
  visualEditor.handleIframeMessage(event)
}

const updateAppNameDraft = (value: string) => {
  appNameDraft.value = value
}

const getMessagesContainer = () => messagesPanelRef.value?.getContainer?.() || null

const goBack = () => {
  const previousRoute = router.options.history.state?.back
  if (typeof previousRoute === 'string' && previousRoute) {
    router.replace(previousRoute)
    return
  }
  router.replace('/')
}

// 权限相关
const isOwner = computed(() => {
  return appInfo.value?.userId === loginUserStore.loginUser.id
})

const isAdmin = computed(() => {
  return loginUserStore.loginUser.userRole === 'admin'
})

const hasDeployed = computed(() => {
  return Boolean(appInfo.value?.deployKey || deployUrl.value)
})

const canEditAppName = computed(() => {
  return Boolean(isOwner.value && appInfo.value?.id)
})

const appDisplayName = computed(() => {
  return appInfo.value?.appName?.trim() || '网站生成器'
})

const currentDeployUrl = computed(() => {
  if (deployUrl.value) {
    return deployUrl.value
  }
  if (appInfo.value?.deployKey) {
    return getDeployUrl(appInfo.value.deployKey)
  }
  return ''
})

const canOptimizePrompt = computed(() => {
  return Boolean(isOwner.value && userInput.value.trim() && !isGenerating.value && !isOptimizingPrompt.value)
})

const latestGenerationFailed = computed(() => {
  const latestAiMessage = [...messages.value].reverse().find((currentMessage) => currentMessage.type === 'ai')
  return Boolean(!isGenerating.value && latestAiMessage?.generationFailed)
})

const generationStatusText = computed(() => {
  if (stoppingGeneration.value) {
    return '停止中'
  }
  if (currentAgentStageText.value && generationPhase.value === 'codegen') {
    return currentAgentStageText.value
  }
  if (generationPhase.value === 'build') {
    return '构建校验中'
  }
  if (generationPhase.value === 'repair') {
    return '自动修复中'
  }
  if (isGenerating.value) {
    return '生成中'
  }
  if (latestGenerationFailed.value) {
    return '需重试'
  }
  return '就绪'
})

const generationStepIndex = computed(() => {
  if (generationPhase.value === 'build') {
    return 1
  }
  if (generationPhase.value === 'done') {
    return 2
  }
  return 0
})

const generationStageDescription = computed(() => {
  const stage = appInfo.value?.generatingStage || generationPhase.value
  if (stoppingGeneration.value) {
    return '正在终止本次生成'
  }
  if (currentAgentStageText.value && generationPhase.value === 'codegen') {
    return '智能体正在并行拆解需求、准备上下文并规划生成策略'
  }
  if (stage === 'build' || generationPhase.value === 'build') {
    return '代码已写入，正在后台执行依赖安装和构建校验'
  }
  if (stage === 'repair' || generationPhase.value === 'repair') {
    return '构建未通过，正在尝试最小范围修复'
  }
  if (stage === 'agent') {
    return '智能体正在并行拆解需求、准备上下文并规划生成策略'
  }
  if (stage === 'update') {
    return '正在基于现有项目改修，旧版预览会保留'
  }
  if (stage === 'create') {
    return '正在创建项目文件，生成后会立即进入构建校验'
  }
  return '正在处理生成任务'
})

const isFileDirty = computed(() => {
  return selectedFilePath.value && fileContent.value !== savedFileContent.value
})

const canSaveFile = computed(() => {
  return Boolean(isOwner.value && selectedFilePath.value && isFileDirty.value && !savingFile.value && !loadingFileContent.value)
})

const editorStatusText = computed(() => {
  if (isStreamingFilePreview.value) {
    return 'AI 正在写入'
  }
  if (savingFile.value) {
    return '保存中'
  }
  if (loadingFileContent.value) {
    return '读取中'
  }
  if (isFileDirty.value) {
    return '有未保存修改'
  }
  return selectedFilePath.value ? '已保存' : ''
})

const selectedFileLanguage = computed(() => {
  const filePath = selectedFilePath.value || selectedFileName.value
  const extension = filePath.split('.').pop()?.toLowerCase() || ''
  return codeLanguageAliasMap[extension] || extension
})

const highlightedFileContent = computed(() => {
  const content = fileContent.value || ' '
  const language = selectedFileLanguage.value
  if (language && hljs.getLanguage(language)) {
    try {
      return hljs.highlight(content, { language, ignoreIllegals: true }).value
    } catch {
      // 使用自动识别兜底
    }
  }
  return hljs.highlightAuto(content).value
})

const rememberOpenFileTab = (filePath: string, fileName?: string) => {
  const normalizedPath = normalizeFilePath(filePath)
  if (!normalizedPath) {
    return
  }
  const normalizedName = fileName || normalizedPath.split('/').pop() || normalizedPath
  const nextTabs = openFileTabs.value.filter((tab) => normalizeFilePath(tab.path) !== normalizedPath)
  nextTabs.push({ path: normalizedPath, name: normalizedName })
  openFileTabs.value = nextTabs.slice(-5)
}

// 应用详情相关
const appDetailVisible = ref(false)

// 显示应用详情
const showAppDetail = () => {
  appDetailVisible.value = true
}

const stopGenerationPolling = () => {
  if (generationPollingTimer !== null) {
    window.clearInterval(generationPollingTimer)
    generationPollingTimer = null
  }
}

const findPendingAiMessageIndex = () => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    const currentMessage = messages.value[index]
    if (currentMessage.type === 'ai' && currentMessage.loading) {
      return index
    }
  }
  return -1
}

const closeActiveEventSource = () => {
  activeEventSource?.close()
  activeEventSource = null
}

const markCurrentGenerationStopped = () => {
  const pendingAiMessageIndex = findPendingAiMessageIndex()
  if (pendingAiMessageIndex >= 0) {
    const targetMessage = messages.value[pendingAiMessageIndex]
    targetMessage.loading = false
    targetMessage.thinkingActive = false
    if (targetMessage.thinkingContent) {
      targetMessage.thinkingCollapsed = true
    }
    if (!targetMessage.content.includes('[系统] 已停止本次生成')) {
      targetMessage.content = `${targetMessage.content || ''}\n\n[系统] 已停止本次生成`.trim()
    }
  }
  if (appInfo.value) {
    appInfo.value = {
      ...appInfo.value,
      isGenerating: 0,
      generatingMessage: '',
      generatingStage: undefined,
    }
  }
  generationPhase.value = 'idle'
  isGenerating.value = false
  stoppingGeneration.value = false
  currentAgentStageText.value = ''
  stopGenerationPolling()
  stopStreamingFilePreview(true)
  closeActiveEventSource()
}

const getToolCallFileIcon = (filePath: string) => {
  return getFileIconMeta(filePath)
}

const isToolCallFileActive = (filePath: string) => {
  return Boolean(filePath && normalizeFilePath(selectedFilePath.value) === normalizeFilePath(filePath))
}

const openToolCallFile = async (filePath: string) => {
  const normalizedFilePath = normalizeFilePath(filePath)
  if (!normalizedFilePath) {
    return
  }
  if (!isOwner.value) {
    message.warning('仅应用创建者可以查看代码文件')
    return
  }
  if (
    isStreamingFilePreview.value &&
    streamingFilePath.value &&
    streamingFilePath.value !== normalizedFilePath
  ) {
    message.warning('AI 正在流式写入当前文件，请等待生成完成')
    return
  }
  if (
    isFileDirty.value &&
    selectedFilePath.value &&
    normalizeFilePath(selectedFilePath.value) !== normalizedFilePath
  ) {
    message.warning('当前文件有未保存修改，请先保存后再切换')
    return
  }

  activeWorkspaceTab.value = 'files'
  if (!fileTreeData.value.length) {
    await loadCodeFiles()
  }
  ensureFileTreePath(normalizedFilePath)
  await loadFileContent(normalizedFilePath)
}

const syncGeneratingMessageFromAppInfo = () => {
  const appGenerating = Boolean(appInfo.value?.isGenerating)
  if (!appGenerating) {
    isGenerating.value = false
    stoppingGeneration.value = false
    currentAgentStageText.value = ''
    if (generationPhase.value !== 'failed') {
      generationPhase.value = 'idle'
    }
    return false
  }
  generationPhase.value = appInfo.value?.generatingStage === 'build'
    ? 'build'
    : appInfo.value?.generatingStage === 'repair'
      ? 'repair'
      : 'codegen'
  if (generationPhase.value !== 'codegen') {
    currentAgentStageText.value = ''
  }
  const generatingMessage = appInfo.value?.generatingMessage || ''
  const pendingAiMessageIndex = findPendingAiMessageIndex()
  if (pendingAiMessageIndex >= 0) {
    messages.value[pendingAiMessageIndex].content = generatingMessage
    messages.value[pendingAiMessageIndex].loading = true
    decorateMessageWithBuildResult(messages.value[pendingAiMessageIndex])
  } else {
    const restoredMessage: ChatMessage = {
      type: 'ai',
      content: generatingMessage,
      loading: true,
      thinkingCollapsed: true,
      createTime: getCurrentMessageTime(),
    }
    decorateMessageWithBuildResult(restoredMessage)
    messages.value.push(restoredMessage)
  }
  isGenerating.value = true
  nextTick(() => {
    void syncMessagesViewport()
  })
  return true
}

const getDistanceFromBottom = () => {
  const container = getMessagesContainer()
  if (!container) {
    return 0
  }
  return Math.max(container.scrollHeight - container.scrollTop - container.clientHeight, 0)
}

const updateMessageScrollState = () => {
  const distance = getDistanceFromBottom()
  isNearBottom.value = distance <= AUTO_SCROLL_THRESHOLD
  showScrollToBottom.value = distance > SCROLL_BUTTON_THRESHOLD
}

const handleMessagesScroll = () => {
  updateMessageScrollState()
}

const scrollToBottom = (behavior: ScrollBehavior = 'smooth') => {
  const container = getMessagesContainer()
  if (!container) {
    return
  }
  container.scrollTo({
    top: container.scrollHeight,
    behavior,
  })
  isNearBottom.value = true
  showScrollToBottom.value = false
  window.requestAnimationFrame(() => {
    updateMessageScrollState()
  })
}

const scrollMessagesToBottom = () => {
  scrollToBottom('smooth')
}

const syncMessagesViewport = async (shouldStickToBottom = isNearBottom.value) => {
  await nextTick()
  if (shouldStickToBottom) {
    scrollToBottom('auto')
    return
  }
  updateMessageScrollState()
}

const fetchAppStateOnly = async () => {
  const res = await getAppVoById({ id: appId.value })
  if (res.data.code !== 0 || !res.data.data) {
    throw new Error(res.data.message || '获取应用信息失败')
  }
  appInfo.value = res.data.data
  return res.data.data
}

const refreshAfterGeneration = async () => {
  isGenerating.value = false
  stoppingGeneration.value = false
  currentAgentStageText.value = ''
  generationPhase.value = latestGenerationFailed.value ? 'failed' : 'done'
  await loadChatHistory()
  updatePreview()
  if (activeWorkspaceTab.value === 'files') {
    await loadCodeFiles()
  }
  if (!latestGenerationFailed.value) {
    activeWorkspaceTab.value = 'preview'
  }
}

const retryLastGeneration = async () => {
  if (!lastGenerationPrompt.value || isGenerating.value || !isOwner.value) {
    return
  }
  const latestAiIndex = (() => {
    for (let index = messages.value.length - 1; index >= 0; index -= 1) {
      if (messages.value[index].type === 'ai') {
        return index
      }
    }
    return -1
  })()
  if (latestAiIndex >= 0) {
    await regenerateAiMessage(latestAiIndex)
    return
  }
  userInput.value = lastGenerationPrompt.value
  await sendMessage()
}

const stopCurrentGeneration = async () => {
  if (!appId.value || !isGenerating.value || stoppingGeneration.value || !isOwner.value) {
    return
  }
  stoppingGeneration.value = true
  try {
    const res = await stopChatToGenCode({
      appId: appId.value,
    })
    if (res.data.code !== 0) {
      throw new Error(res.data.message || '停止生成失败')
    }
    markCurrentGenerationStopped()
    message.success('已停止生成')
    await fetchAppStateOnly()
  } catch (error) {
    console.error('停止生成失败：', error)
    stoppingGeneration.value = false
    message.error(error instanceof Error ? error.message : '停止生成失败，请重试')
  }
}

const handleSendButtonClick = () => {
  if (isGenerating.value) {
    void stopCurrentGeneration()
    return
  }
  void sendMessage()
}

const pollGenerationState = async () => {
  if (!appId.value || refreshingGenerationState.value) {
    return
  }
  refreshingGenerationState.value = true
  try {
    const wasGenerating = Boolean(appInfo.value?.isGenerating)
    const latestAppInfo = await fetchAppStateOnly()
    if (latestAppInfo.isGenerating) {
      syncGeneratingMessageFromAppInfo()
      return
    }
    if (wasGenerating) {
      stopGenerationPolling()
      await refreshAfterGeneration()
    }
  } catch (error) {
    console.error('同步生成状态失败：', error)
  } finally {
    refreshingGenerationState.value = false
  }
}

const startGenerationPolling = () => {
  if (generationPollingTimer !== null) {
    return
  }
  generationPollingTimer = window.setInterval(() => {
    void pollGenerationState()
  }, 1500)
}

const focusAppNameInput = async () => {
  await chatPageHeaderRef.value?.focusInput?.()
}

const startEditAppName = async () => {
  if (!canEditAppName.value || isEditingAppName.value) {
    return
  }
  appNameDraft.value = appInfo.value?.appName?.trim() || ''
  isEditingAppName.value = true
  await focusAppNameInput()
}

const cancelAppNameEdit = () => {
  if (savingAppName.value) {
    return
  }
  appNameDraft.value = appInfo.value?.appName?.trim() || ''
  isEditingAppName.value = false
}

const saveAppName = async () => {
  if (!isEditingAppName.value || savingAppName.value || !appInfo.value?.id) {
    return
  }
  const nextAppName = appNameDraft.value.trim()
  if (!nextAppName) {
    message.warning('应用名称不能为空')
    await focusAppNameInput()
    return
  }
  if (nextAppName.length > 50) {
    message.warning('应用名称不能超过 50 个字符')
    await focusAppNameInput()
    return
  }
  if (nextAppName === (appInfo.value.appName?.trim() || '')) {
    isEditingAppName.value = false
    return
  }

  savingAppName.value = true
  try {
    const res = await updateApp({
      id: appInfo.value.id,
      appName: nextAppName,
    })
    if (res.data.code === 0) {
      appInfo.value = {
        ...appInfo.value,
        appName: nextAppName,
      }
      isEditingAppName.value = false
      return
    }
    message.error(res.data.message || '应用名称保存失败')
  } catch (error) {
    console.error('保存应用名称失败：', error)
    message.error('应用名称保存失败')
  } finally {
    savingAppName.value = false
  }

  await focusAppNameInput()
}

// 加载对话历史
const loadChatHistory = async (isLoadMore = false) => {
  if (!appId.value || loadingHistory.value) return
  loadingHistory.value = true
  const previousContainer = isLoadMore ? getMessagesContainer() : null
  const previousScrollHeight = previousContainer?.scrollHeight ?? 0
  const previousScrollTop = previousContainer?.scrollTop ?? 0
  try {
    const params: API.listAppChatHistoryParams = {
      appId: appId.value,
      pageSize: 10,
    }
    // 如果是加载更多，传递最后一条消息的创建时间作为游标
    if (isLoadMore && lastCreateTime.value) {
      params.lastCreateTime = lastCreateTime.value
    }
    const res = await listAppChatHistory(params)
    if (res.data.code === 0 && res.data.data) {
      const chatHistories = res.data.data.records || []
      if (chatHistories.length > 0) {
        // 将对话历史转换为消息格式，并按时间正序排列（老消息在前）
        const historyMessages: ChatMessage[] = chatHistories
            .map((chat) => ({
              type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
              content: chat.message || '',
              createTime: chat.createTime,
            }))
            .map((historyMessage) => {
              if (historyMessage.type === 'ai') {
                decorateMessageWithBuildResult(historyMessage)
              }
              return historyMessage
            })
            .reverse() // 反转数组，让老消息在前
        if (isLoadMore) {
          // 加载更多时，将历史消息添加到开头
          messages.value.unshift(...historyMessages)
        } else {
          // 初始加载，直接设置消息列表
          messages.value = historyMessages
        }
        // 更新游标
        lastCreateTime.value = chatHistories[chatHistories.length - 1]?.createTime
        // 检查是否还有更多历史
        hasMoreHistory.value = chatHistories.length === 10
      } else {
        hasMoreHistory.value = false
      }
      historyLoaded.value = true
      await nextTick()
      if (isLoadMore) {
        const container = getMessagesContainer()
        if (!container) {
          return
        }
        const scrollOffset = container.scrollHeight - previousScrollHeight
        container.scrollTop = previousScrollTop + scrollOffset
        updateMessageScrollState()
      } else {
        scrollToBottom('auto')
      }
    }
  } catch (error) {
    console.error('加载对话历史失败：', error)
    message.error('加载对话历史失败')
  } finally {
    loadingHistory.value = false
  }
}

// 加载更多历史消息
const loadMoreHistory = async () => {
  await loadChatHistory(true)
}

// 判断是否为 Vue 项目
const isVueProject = (app: API.AppVO) => {
  return app?.codeGenType === CodeGenTypeEnum.VUE_PROJECT || app?.codeGenType === CodeGenTypeEnum.FULL_STACK_PROJECT
}

// 启动 dev server
const startDevServerForApp = async () => {
  if (!appId.value || !isOwner.value) return

  try {
    const res = await startDevServer({ appId: appId.value })
    if (res.data.code === 0 && res.data.data) {
      devServerRunning.value = true
      // 更新 appInfo 中的端口信息
      if (appInfo.value && res.data.data.port) {
        appInfo.value = {
          ...appInfo.value,
          devServerPort: res.data.data.port,
        }
      }
      console.log('Dev server 已启动，端口:', res.data.data.port)
    }
  } catch (error) {
    console.error('启动 dev server 失败:', error)
    // 不阻断流程，降级使用静态预览
  }
}

// 停止 dev server
const stopDevServerForApp = async () => {
  if (!appId.value || !devServerRunning.value) return

  try {
    await stopDevServer({ appId: appId.value })
    devServerRunning.value = false
    console.log('Dev server 已停止')
  } catch (error) {
    console.error('停止 dev server 失败:', error)
  }
}

// 获取应用信息
const fetchAppInfo = async () => {
  const id = route.params.id as string
  if (!id) {
    message.error('应用ID不存在')
    router.push('/')
    return
  }

  appId.value = id

  try {
    const latestAppInfo = await fetchAppStateOnly()

    // 为 Vue 项目启动 dev server
    if (isVueProject(latestAppInfo) && isOwner.value) {
      await startDevServerForApp()
    }

    // 更新预览（无论消息数量）
    updatePreview()

    await loadChatHistory()
    const restoredGeneratingState = syncGeneratingMessageFromAppInfo()
    if (restoredGeneratingState) {
      startGenerationPolling()
      return
    }
    stopGenerationPolling()
    if (
      latestAppInfo.initPrompt &&
      isOwner.value &&
      messages.value.length === 0 &&
      historyLoaded.value
    ) {
      await sendInitialMessage(latestAppInfo.initPrompt)
    }
  } catch (error) {
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

const getCurrentMessageTime = () => dayjs().format('YYYY-MM-DD HH:mm:ss')

const formatMessageTime = (time?: string) => {
  if (!time) {
    return ''
  }
  const targetTime = dayjs(time)
  if (!targetTime.isValid()) {
    return ''
  }
  const hour = targetTime.hour()
  const period = hour < 6 ? '凌晨' : hour < 12 ? '上午' : hour < 18 ? '下午' : '晚上'
  const clockText = targetTime.format('HH:mm')
  if (targetTime.isSame(dayjs(), 'day')) {
    return `今天${period}${clockText}`
  }
  if (targetTime.isSame(dayjs().subtract(1, 'day'), 'day')) {
    return `昨天${period}${clockText}`
  }
  return targetTime.format('YYYY-MM-DD HH:mm')
}

const clearMessagesAfter = (index: number) => {
  messages.value.splice(index + 1)
}

const createLoadingAiMessage = (): ChatMessage => ({
  type: 'ai',
  content: '',
  loading: true,
  thinkingActive: true,
  thinkingCollapsed: false,
  createTime: getCurrentMessageTime(),
})

const prepareGenerationState = (stage: 'create' | 'update') => {
  isGenerating.value = true
  generationPhase.value = 'codegen'
  activeWorkspaceTab.value = 'files'
  if (!fileTreeData.value.length) {
    void loadCodeFiles()
  }
  if (appInfo.value) {
    appInfo.value = {
      ...appInfo.value,
      isGenerating: 1,
      generatingMessage: '',
      generatingStage: stage,
    }
  }
}

const startGenerationFromMessage = async (
  prompt: string,
  aiMessageIndex: number,
  stage: 'create' | 'update',
) => {
  await nextTick()
  scrollToBottom('smooth')
  prepareGenerationState(stage)
  await generateCode(prompt, aiMessageIndex)
}

// 发送初始消息
const sendInitialMessage = async (prompt: string) => {
  stopGenerationPolling()
  messages.value.push({
    type: 'user',
    content: prompt,
    createTime: getCurrentMessageTime(),
  })

  const aiMessageIndex = messages.value.length
  messages.value.push(createLoadingAiMessage())
  await startGenerationFromMessage(prompt, aiMessageIndex, 'create')
}

// 发送消息
const sendMessage = async (presetMessage?: string) => {
  const rawMessage = presetMessage ?? userInput.value
  if (!rawMessage.trim() || isGenerating.value) {
    return
  }
  stopGenerationPolling()

  let message = rawMessage.trim()
  // 如果有选中的元素，将元素信息添加到提示词中
  if (selectedElementInfo.value) {
    let elementContext = `\n\n选中元素信息：`
    if (selectedElementInfo.value.pagePath) {
      elementContext += `\n- 页面路径: ${selectedElementInfo.value.pagePath}`
    }
    elementContext += `\n- 标签: ${selectedElementInfo.value.tagName.toLowerCase()}\n- 选择器: ${selectedElementInfo.value.selector}`
    if (selectedElementInfo.value.textContent) {
      elementContext += `\n- 当前内容: ${selectedElementInfo.value.textContent.substring(0, 100)}`
    }
    message += elementContext
  }
  if (!presetMessage) {
    userInput.value = ''
  }

  const editingIndex = presetMessage ? null : editingMessageIndex.value
  let aiMessageIndex: number
  if (editingIndex !== null && messages.value[editingIndex]?.type === 'user') {
    messages.value[editingIndex] = {
      type: 'user',
      content: message,
      createTime: getCurrentMessageTime(),
    }
    clearMessagesAfter(editingIndex)
    aiMessageIndex = messages.value.length
  } else {
    messages.value.push({
      type: 'user',
      content: message,
      createTime: getCurrentMessageTime(),
    })
    aiMessageIndex = messages.value.length
  }
  editingMessageIndex.value = null

  // 发送消息后，清除选中元素并退出编辑模式
  if (selectedElementInfo.value) {
    clearSelectedElement()
    if (isEditMode.value) {
      toggleEditMode()
    }
  }

  // 添加AI消息占位符
  messages.value.push(createLoadingAiMessage())
  await startGenerationFromMessage(message, aiMessageIndex, 'update')
}

const editUserMessage = (index: number) => {
  const targetMessage = messages.value[index]
  if (!targetMessage || targetMessage.type !== 'user' || isGenerating.value || !isOwner.value) {
    return
  }
  editingMessageIndex.value = index
  userInput.value = targetMessage.content
  message.info('已回填到输入框，发送后将清空该节点后的对话')
}

const regenerateAiMessage = async (index: number) => {
  const targetMessage = messages.value[index]
  if (!targetMessage || targetMessage.type !== 'ai' || isGenerating.value || !isOwner.value) {
    return
  }
  const previousUserMessage = [...messages.value]
    .slice(0, index)
    .reverse()
    .find((currentMessage) => currentMessage.type === 'user')
  if (!previousUserMessage?.content.trim()) {
    message.warning('未找到可重新生成的用户消息')
    return
  }
  stopGenerationPolling()
  messages.value.splice(index)
  const aiMessageIndex = messages.value.length
  messages.value.push(createLoadingAiMessage())
  await startGenerationFromMessage(previousUserMessage.content, aiMessageIndex, 'update')
}

const toggleAiFeedback = (index: number, feedback: 'like' | 'dislike') => {
  const targetMessage = messages.value[index]
  if (!targetMessage || targetMessage.type !== 'ai') {
    return
  }
  targetMessage.feedback = targetMessage.feedback === feedback ? undefined : feedback
}

const handleDatabaseEnabled = async (resource: API.AppDatabaseResourceVO) => {
  if (appInfo.value) {
    appInfo.value = {
      ...appInfo.value,
      databaseResource: resource,
    }
  }
  activeWorkspaceTab.value = 'database'
  await sendMessage('请帮我接入 Database')
}

const optimizeUserPrompt = async () => {
  const prompt = userInput.value.trim()
  if (!prompt || isGenerating.value || isOptimizingPrompt.value || !isOwner.value) {
    return
  }

  isOptimizingPrompt.value = true
  try {
    const res = await optimizePrompt({
      prompt,
    })
    if (res.data.code === 0 && res.data.data) {
      userInput.value = res.data.data
      message.success('提示词已优化')
      return
    }
    message.error('优化失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('优化提示词失败：', error)
    message.error('优化失败，请重试')
  } finally {
    isOptimizingPrompt.value = false
  }
}

// 生成代码 - 使用 EventSource 处理流式响应
const generateCode = async (userMessage: string, aiMessageIndex: number) => {
  let eventSource: EventSource | null = null
  let streamCompleted = false
  lastGenerationPrompt.value = userMessage

  try {
    const startRes = await chatToGenCode({
      appId: appId.value,
      message: userMessage,
    })
    if (startRes.data.code !== 0) {
      // 直接显示后端返回的错误信息，并停止生成流程
      const errorMsg = startRes.data.message || '启动生成失败'
      messages.value[aiMessageIndex].content = errorMsg
      messages.value[aiMessageIndex].loading = false
      messages.value[aiMessageIndex].generationFailed = true
      isGenerating.value = false
      message.error(errorMsg)
      return
    }

    // 获取 axios 配置的 baseURL
    const baseURL = request.defaults.baseURL || API_BASE_URL

    const params = new URLSearchParams({
      appId: appId.value || '',
    })

    const url = `${baseURL}/app/chat/gen/code?${params}`

    // 创建 EventSource 连接
    eventSource = new EventSource(url, {
      withCredentials: true,
    })
    activeEventSource = eventSource

    let fullContent = ''

    const appendThinkingText = (text: string | undefined) => {
      if (!text || streamCompleted) {
        return
      }
      const shouldStickToBottom = isNearBottom.value
      const targetMessage = messages.value[aiMessageIndex]
      targetMessage.thinkingContent = `${targetMessage.thinkingContent || ''}${text}`
      targetMessage.thinkingActive = true
      targetMessage.thinkingCollapsed = false
      targetMessage.loading = false
      void syncMessagesViewport(shouldStickToBottom)
    }

    const appendGeneratedText = (text: string | undefined) => {
      if (!text || streamCompleted) {
        return
      }
      const shouldStickToBottom = isNearBottom.value
      fullContent += text
      const targetMessage = messages.value[aiMessageIndex]
      targetMessage.content = fullContent
      targetMessage.loading = false
      if (targetMessage.thinkingContent) {
        targetMessage.thinkingActive = false
        targetMessage.thinkingCollapsed = true
      }
      void syncMessagesViewport(shouldStickToBottom)
    }

const handleGenerationEvent = (event: MessageEvent) => {
      if (streamCompleted) return
      const streamEvent = parseGenerationStreamEvent(event)
      if (!streamEvent) {
        return
      }
      if (streamEvent.type === 'ai_thinking_delta') {
        appendThinkingText(streamEvent.text)
      } else {
        appendGeneratedText(streamEvent.text)
      }
      void handleFileOperationStreamEvent(streamEvent)
      if (streamEvent.type === 'agent_event') {
        upsertAgentEvent(messages.value[aiMessageIndex], streamEvent)
        generationPhase.value = 'codegen'
        const agentName = String(streamEvent.data?.agent || '智能体')
        currentAgentStageText.value = `${agentName}处理中`
        messages.value[aiMessageIndex].loading = true
        return
      }
      if (streamEvent.type === 'generation_stage') {
        const stage = String(streamEvent.data?.stage || 'unknown')
        if (stage === 'codegen_done') {
          generationPhase.value = 'build'
          currentAgentStageText.value = ''
          if (appInfo.value) {
            appInfo.value = {
              ...appInfo.value,
              isGenerating: 1,
              generatingStage: 'build',
              generatingMessage: String(streamEvent.data?.summary || '代码已生成，后台正在执行构建校验'),
            }
          }
          startGenerationPolling()
          messages.value[aiMessageIndex].generationFailed = false
          messages.value[aiMessageIndex].loading = true
          void showGeneratedResultBeforeValidation()
        }
        return
      }
      if (streamEvent.type === 'build_result') {
        const success = Boolean(streamEvent.data?.success)
        const stage = String(streamEvent.data?.stage || 'unknown')
        if (stage === 'codegen_done') {
          generationPhase.value = 'build'
          currentAgentStageText.value = ''
          if (appInfo.value) {
            appInfo.value = {
              ...appInfo.value,
              isGenerating: 1,
              generatingStage: 'build',
              generatingMessage: String(streamEvent.data?.summary || '代码已生成，后台正在执行构建校验'),
            }
          }
          startGenerationPolling()
          messages.value[aiMessageIndex].generationFailed = false
          messages.value[aiMessageIndex].loading = true
          void showGeneratedResultBeforeValidation()
          return
        }
        messages.value[aiMessageIndex].buildResult = {
          status: success ? 'success' : 'failed',
          stage,
          summary: String(streamEvent.data?.summary || (success ? '项目构建成功' : '项目构建失败')),
          report: String(streamEvent.data?.report || ''),
        }
        messages.value[aiMessageIndex].generationFailed = !success
        if (!success) {
          if (streamEvent.data?.willAutoRepair) {
            generationPhase.value = 'repair'
            message.error('项目构建失败，系统将尝试自动修复')
          } else {
            generationPhase.value = 'failed'
            message.error('项目构建失败，请查看诊断结果')
          }
        }
        return
      }
      if (streamEvent.type === 'repair_start') {
        generationPhase.value = 'repair'
        currentAgentStageText.value = ''
        message.info(`自动修复第 ${streamEvent.data?.round || 1} 轮开始`)
        return
      }
      if (streamEvent.type === 'generation_error') {
        generationPhase.value = 'failed'
        currentAgentStageText.value = ''
        messages.value[aiMessageIndex].generationFailed = true
        messages.value[aiMessageIndex].loading = false
        const errorMessage = String(streamEvent.data?.message || streamEvent.text || '生成失败')
        const errorDisplay = getGenerationErrorDisplay(String(streamEvent.data?.category || ''), errorMessage)
        messages.value[aiMessageIndex].content = `${messages.value[aiMessageIndex].content || ''}\n\n❌ ${errorDisplay.detail}`.trim()
        message.error(errorDisplay.toast)
        return
      }
      if (streamEvent.type === 'generation_stopped') {
        markCurrentGenerationStopped()
        message.info('本次生成已停止')
      }
    }

    eventSource.addEventListener('ai_delta', handleGenerationEvent)
    eventSource.addEventListener('ai_thinking_delta', handleGenerationEvent)
    eventSource.addEventListener('tool_call', handleGenerationEvent)
    eventSource.addEventListener('tool_result', handleGenerationEvent)
    eventSource.addEventListener('agent_event', handleGenerationEvent)
    eventSource.addEventListener('generation_stage', handleGenerationEvent)
    eventSource.addEventListener('build_result', handleGenerationEvent)
    eventSource.addEventListener('repair_start', handleGenerationEvent)
    eventSource.addEventListener('generation_error', handleGenerationEvent)
    eventSource.addEventListener('generation_stopped', handleGenerationEvent)

    // 兼容旧协议
    eventSource.onmessage = function (event) {
      if (streamCompleted) return
      const streamEvent = parseGenerationStreamEvent(event)
      if (streamEvent?.type) {
        handleGenerationEvent(event)
        return
      }
      try {
        const parsed = JSON.parse(event.data)
        appendGeneratedText(parsed.d)
        decorateMessageWithBuildResult(messages.value[aiMessageIndex])
      } catch (error) {
        console.error('解析消息失败:', error)
        handleError(error, aiMessageIndex)
      }
    }

    // 处理done事件
    eventSource.addEventListener('done', function () {
      if (streamCompleted) return

      streamCompleted = true
      stopGenerationPolling()
      isGenerating.value = false
      stoppingGeneration.value = false
      if (messages.value[aiMessageIndex]?.thinkingContent) {
        messages.value[aiMessageIndex].thinkingActive = false
        messages.value[aiMessageIndex].thinkingCollapsed = true
      }
      if (messages.value[aiMessageIndex]) {
        messages.value[aiMessageIndex].createTime = getCurrentMessageTime()
      }
      eventSource?.close()
      if (activeEventSource === eventSource) {
        activeEventSource = null
      }

      setTimeout(async () => {
        await fetchAppInfo()
        if (appInfo.value?.isGenerating) {
          isGenerating.value = true
          startGenerationPolling()
          syncGeneratingMessageFromAppInfo()
          if (!messages.value[aiMessageIndex]?.generationFailed) {
            await showGeneratedResultBeforeValidation()
          }
          return
        }
        if (!messages.value[aiMessageIndex]?.generationFailed) {
          generationPhase.value = 'done'
          await showGeneratedResultBeforeValidation()
        }
      }, 300)
    })

    // 处理business-error事件（后端限流等错误）
    eventSource.addEventListener('business-error', function (event: MessageEvent) {
      if (streamCompleted) return

      try {
        const errorData = JSON.parse(event.data)
        console.error('SSE业务错误事件:', errorData)

        // 显示具体的错误信息
        const errorMessage = errorData.message || '生成过程中出现错误'
        messages.value[aiMessageIndex].content = `${messages.value[aiMessageIndex].content || ''}\n\n❌ ${errorMessage}`.trim()
        messages.value[aiMessageIndex].loading = false
        messages.value[aiMessageIndex].thinkingActive = false
        if (messages.value[aiMessageIndex].thinkingContent) {
          messages.value[aiMessageIndex].thinkingCollapsed = true
        }
        messages.value[aiMessageIndex].generationFailed = true
        generationPhase.value = 'failed'
        decorateMessageWithBuildResult(messages.value[aiMessageIndex])
        message.error(errorMessage)

        streamCompleted = true
        isGenerating.value = false
        stoppingGeneration.value = false
        messages.value[aiMessageIndex].thinkingActive = false
        if (messages.value[aiMessageIndex].thinkingContent) {
          messages.value[aiMessageIndex].thinkingCollapsed = true
        }
        stopStreamingFilePreview(true)
        eventSource?.close()
        if (activeEventSource === eventSource) {
          activeEventSource = null
        }
      } catch (parseError) {
        console.error('解析错误事件失败:', parseError, '原始数据:', event.data)
        handleError(new Error('服务器返回错误'), aiMessageIndex)
      }
    })

    // 处理错误
    eventSource.onerror = function () {
      if (streamCompleted || !isGenerating.value) return
      if (messages.value[aiMessageIndex]?.generationFailed) {
        streamCompleted = true
        isGenerating.value = false
        stoppingGeneration.value = false
        generationPhase.value = 'failed'
        stopStreamingFilePreview(true)
        eventSource?.close()
        if (activeEventSource === eventSource) {
          activeEventSource = null
        }
        return
      }
      streamCompleted = true
      stopStreamingFilePreview(true)
      eventSource?.close()
      if (activeEventSource === eventSource) {
        activeEventSource = null
      }
      message.warning('实时连接已断开，正在同步生成状态')
      startGenerationPolling()
      void pollGenerationState()
    }
  } catch (error) {
    console.error('创建 EventSource 失败：', error)
    handleError(error, aiMessageIndex)
  } finally {
    if (activeEventSource === eventSource && streamCompleted) {
      activeEventSource = null
    }
  }
}

// 错误处理函数
const handleError = (error: unknown, aiMessageIndex: number) => {
  console.error('生成代码失败：', error)
  stopGenerationPolling()
  stopStreamingFilePreview(true)
  generationPhase.value = 'failed'
  const currentContent = messages.value[aiMessageIndex]?.content || ''
  messages.value[aiMessageIndex].content = currentContent
    ? `${currentContent}\n\n抱歉，生成过程中出现了错误，请重试。`
    : '抱歉，生成过程中出现了错误，请重试。'
  messages.value[aiMessageIndex].loading = false
  messages.value[aiMessageIndex].generationFailed = true
  decorateMessageWithBuildResult(messages.value[aiMessageIndex])
  message.error('生成失败，请重试')
  isGenerating.value = false
  stoppingGeneration.value = false
  closeActiveEventSource()
}

const refreshPreview = () => {
  if (!appId.value) {
    return
  }
  const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
  const devServerPort = appInfo.value?.devServerPort
  // Vue 项目且 dev server 运行中时，使用 dev server URL（通过后端代理）
  if ((codeGenType === CodeGenTypeEnum.VUE_PROJECT || codeGenType === CodeGenTypeEnum.FULL_STACK_PROJECT) && devServerRunning.value && devServerPort) {
    previewUrl.value = getDevServerPreviewUrl(appId.value, devServerPort)
  } else {
    previewUrl.value = appendPreviewCacheBuster(getStaticPreviewUrl(codeGenType, appId.value))
  }
  previewReady.value = false
  previewRefreshKey.value += 1
}

// 更新预览
const updatePreview = () => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const devServerPort = appInfo.value?.devServerPort
    // Vue 项目且 dev server 运行中时，使用 dev server URL
    if ((codeGenType === CodeGenTypeEnum.VUE_PROJECT || codeGenType === CodeGenTypeEnum.FULL_STACK_PROJECT) && devServerRunning.value && devServerPort) {
      previewUrl.value = getDevServerPreviewUrl(appId.value, devServerPort)
    } else {
      previewUrl.value = getStaticPreviewUrl(codeGenType, appId.value)
    }
    previewReady.value = true
  }
}

const showGeneratedResultBeforeValidation = async () => {
  updatePreview()
  activeWorkspaceTab.value = 'preview'
  await loadCodeFiles()
  stopStreamingFilePreview(true)
}

const expandDirectoriesForFilePath = (filePath: string) => {
  const normalizedPath = normalizeFilePath(filePath)
  const segments = normalizedPath.split('/').filter(Boolean)
  if (segments.length <= 1) {
    return
  }
  const nextExpandedKeys = new Set(expandedFileTreeKeys.value)
  let currentPath = ''
  for (let index = 0; index < segments.length - 1; index += 1) {
    currentPath = currentPath ? `${currentPath}/${segments[index]}` : segments[index]
    nextExpandedKeys.add(currentPath)
  }
  expandedFileTreeKeys.value = Array.from(nextExpandedKeys)
}

const ensureFileTreePath = (filePath: string) => {
  const normalizedPath = normalizeFilePath(filePath)
  const segments = normalizedPath.split('/').filter(Boolean)
  if (!segments.length) {
    return
  }

  const nextTree = [...fileTreeData.value]
  let currentLevel = nextTree
  let currentPath = ''

  segments.forEach((segment, index) => {
    currentPath = currentPath ? `${currentPath}/${segment}` : segment
    const isFile = index === segments.length - 1
    let currentNode = currentLevel.find((node) => node.key === currentPath)

    if (!currentNode) {
      currentNode = createFileTreeNode(currentPath, segment, !isFile)
      currentLevel.push(currentNode)
    }

    if (!isFile) {
      if (!currentNode.children) {
        currentNode.children = []
      }
      currentLevel = currentNode.children
    }
  })

  fileTreeData.value = nextTree
  expandDirectoriesForFilePath(normalizedPath)
}

const loadCodeFiles = async () => {
  if (!appId.value || !isOwner.value || loadingFiles.value) {
    return
  }
  loadingFiles.value = true
  try {
    const res = await listAppCodeFiles({
      appId: appId.value as unknown as number,
    })
    if (res.data.code === 0 && res.data.data) {
      fileTreeData.value = mapFileTreeNodes(res.data.data)
      expandedFileTreeKeys.value = collectDirectoryKeys(fileTreeData.value)
      if (selectedFilePath.value) {
        ensureFileTreePath(selectedFilePath.value)
      }
      return
    }
    message.error('加载文件失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('加载文件失败：', error)
    message.error('加载文件失败，请重试')
  } finally {
    loadingFiles.value = false
  }
}

const handleFileTreeExpand = (expandedKeys: Array<string | number>) => {
  expandedFileTreeKeys.value = expandedKeys
}

const loadFileContent = async (filePath: string) => {
  if (!appId.value || !filePath) {
    return
  }
  if (!isFrontendEditableFile(filePath)) {
    message.info('文件树已展示该文件，但当前编辑器仅支持前端文件')
    return
  }
  if (isStreamingFilePreview.value && streamingFilePath.value === filePath) {
    return
  }
  loadingFileContent.value = true
  try {
    const res = await getAppCodeFileContent({
      appId: appId.value as unknown as number,
      filePath,
    })
    if (res.data.code === 0 && res.data.data) {
      const file = res.data.data
      selectedFilePath.value = file.path || filePath
      selectedFileName.value = file.name || filePath.split('/').pop() || filePath
      fileContent.value = file.content || ''
      savedFileContent.value = file.content || ''
      loadedFileContentCache.set(normalizeFilePath(selectedFilePath.value), file.content || '')
      rememberOpenFileTab(selectedFilePath.value, selectedFileName.value)
      return
    }
    message.error('读取文件失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('读取文件失败：', error)
    message.error('读取文件失败，请重试')
  } finally {
    loadingFileContent.value = false
  }
}

const handleFileSelect = async (selectedKeys: Array<string | number>) => {
  const filePath = String(selectedKeys[0] || '')
  if (!filePath || filePath === selectedFilePath.value) {
    return
  }
  if (isStreamingFilePreview.value) {
    message.warning('AI 正在流式写入当前文件，请等待生成完成')
    return
  }
  if (isFileDirty.value) {
    message.warning('当前文件有未保存修改，请先保存后再切换')
    return
  }
  await loadFileContent(filePath)
}

const openEditorTab = async (filePath: string) => {
  const normalizedFilePath = normalizeFilePath(filePath)
  if (!normalizedFilePath || normalizedFilePath === normalizeFilePath(selectedFilePath.value)) {
    return
  }
  await handleFileSelect([normalizedFilePath])
}

const handleWorkspaceTabChange = async (activeKey: string | number) => {
  if (activeKey === 'files' && !fileTreeData.value.length) {
    await loadCodeFiles()
  }
  if (activeKey === 'preview' && isEditMode.value) {
    isEditMode.value = false
    visualEditor.disableEditMode()
  }
}

const syncCodeEditorToBottom = async () => {
  await nextTick()
  const textarea = document.querySelector('.code-editor') as HTMLTextAreaElement | null
  if (!textarea) {
    return
  }
  textarea.scrollTop = textarea.scrollHeight
}

const syncCodeEditorToCharIndex = async (charIndex: number) => {
  await nextTick()
  const textarea = document.querySelector('.code-editor') as HTMLTextAreaElement | null
  if (!textarea) {
    return
  }
  const textBeforeCursor = fileContent.value.slice(0, Math.max(charIndex, 0))
  const lineIndex = textBeforeCursor.split('\n').length - 1
  const lineHeight = Number.parseFloat(window.getComputedStyle(textarea).lineHeight) || 22
  const targetTop = Math.max(lineIndex * lineHeight - textarea.clientHeight * 0.45, 0)
  textarea.scrollTop = targetTop
}

const focusCodeEditor = () => {
  const textarea = document.querySelector('.code-editor') as HTMLTextAreaElement | null
  textarea?.focus()
}

const stopStreamingFilePreview = (keepCurrentContent = false) => {
  if (streamingFileTimer !== null) {
    window.clearInterval(streamingFileTimer)
    streamingFileTimer = null
  }
  isStreamingFilePreview.value = false
  streamingFilePath.value = ''
  streamingFileFinalContent.value = ''
  streamingFileDisplayContent.value = ''
  if (!keepCurrentContent) {
    return
  }
  savedFileContent.value = fileContent.value
}

const ensureStreamingFileTimer = (filePath: string) => {
  const normalizedFilePath = normalizeFilePath(filePath)
  if (streamingFileTimer !== null) {
    return
  }

  const tick = () => {
    if (!isStreamingFilePreview.value || streamingFilePath.value !== normalizedFilePath) {
      return
    }
    const target = streamingFileFinalContent.value
    if (streamingFileDisplayContent.value === target) {
      return
    }
    const remaining = target.length - streamingFileDisplayContent.value.length
    const chunkSize = Math.max(1, Math.min(remaining, Math.ceil(remaining / 12)))
    streamingFileDisplayContent.value += target.slice(
      streamingFileDisplayContent.value.length,
      streamingFileDisplayContent.value.length + chunkSize,
    )
    fileContent.value = streamingFileDisplayContent.value
    void syncCodeEditorToCharIndex(streamingFileDisplayContent.value.length)
  }

  tick()
  streamingFileTimer = window.setInterval(tick, 24)
}

const startStreamingFilePreview = async (filePath: string, targetContent: string, initialContent = '') => {
  if (!filePath) {
    return
  }
  const normalizedFilePath = normalizeFilePath(filePath)
  if (
    isFileDirty.value &&
    selectedFilePath.value &&
    normalizeFilePath(selectedFilePath.value) !== normalizedFilePath &&
    !isStreamingFilePreview.value
  ) {
    return
  }
  if (isStreamingFilePreview.value && streamingFilePath.value === normalizedFilePath) {
    streamingFileFinalContent.value = targetContent || ''
    ensureStreamingFileTimer(normalizedFilePath)
    return
  }
  if (isStreamingFilePreview.value) {
    savedFileContent.value = fileContent.value
  }
  if (!fileTreeData.value.length) {
    await loadCodeFiles()
  }
  ensureFileTreePath(normalizedFilePath)
  activeWorkspaceTab.value = 'files'
  await nextTick()
  const fileName = normalizedFilePath.split('/').pop() || normalizedFilePath
  selectedFilePath.value = normalizedFilePath
  selectedFileName.value = fileName
  rememberOpenFileTab(normalizedFilePath, fileName)
  loadingFileContent.value = false
  isStreamingFilePreview.value = true
  streamingFilePath.value = normalizedFilePath
  streamingFileFinalContent.value = targetContent || ''
  streamingFileDisplayContent.value = initialContent
  fileContent.value = initialContent
  savedFileContent.value = initialContent

  if (streamingFileTimer !== null) {
    window.clearInterval(streamingFileTimer)
    streamingFileTimer = null
  }

  ensureStreamingFileTimer(normalizedFilePath)
}

const getStringFromEventData = (data: Record<string, any> | undefined, key: string) => {
  const value = data?.[key]
  return typeof value === 'string' ? value : ''
}

const getLoadedFileContent = (filePath: string) => {
  const normalizedFilePath = normalizeFilePath(filePath)
  if (normalizeFilePath(selectedFilePath.value) === normalizedFilePath) {
    return savedFileContent.value || fileContent.value
  }
  return loadedFileContentCache.get(normalizedFilePath) || ''
}

const handleFileOperationStreamEvent = async (streamEvent: GenerationStreamEvent) => {
  const filePath = getStringFromEventData(streamEvent.data, 'filePath')
  if (!filePath) {
    return
  }
  ensureFileTreePath(filePath)
  const normalizedFilePath = normalizeFilePath(filePath)
  const toolName = getStringFromEventData(streamEvent.data, 'toolName')
  if (!isFrontendEditableFile(normalizedFilePath)) {
    if (['writeFile', 'modifyFile'].includes(toolName)) {
      await nextTick()
    }
    return
  }
  if (!['writeFile', 'modifyFile'].includes(toolName)) {
    return
  }
  const generatedContent = getStringFromEventData(streamEvent.data, 'content')
  if (toolName === 'writeFile' && (streamEvent.type === 'tool_call' || generatedContent)) {
    if (!generatedContent && streamingFilePath.value !== normalizedFilePath) {
      activeWorkspaceTab.value = 'files'
      return
    }
    await startStreamingFilePreview(filePath, generatedContent)
    return
  }
  if (streamEvent.type === 'tool_call') {
    if (toolName === 'modifyFile') {
      const oldContent = getStringFromEventData(streamEvent.data, 'oldContent')
      const newContent = getStringFromEventData(streamEvent.data, 'newContent')
      if (!loadedFileContentCache.has(normalizedFilePath) && normalizeFilePath(selectedFilePath.value) !== normalizedFilePath) {
        await loadFileContent(filePath)
      }
      if (newContent || streamingFilePath.value === normalizedFilePath) {
        const preview = buildModifiedFilePreview(getLoadedFileContent(filePath), oldContent, newContent)
        await startStreamingFilePreview(filePath, preview.targetContent, preview.initialContent)
      }
      return
    }
    activeWorkspaceTab.value = 'files'
    await loadFileContent(filePath)
    await syncCodeEditorToBottom()
    return
  }
  if (streamEvent.type !== 'tool_result') {
    return
  }
  try {
    const res = await getAppCodeFileContent({
      appId: appId.value as unknown as number,
      filePath,
    })
    if (res.data.code === 0 && res.data.data) {
      const file = res.data.data
      if (toolName === 'modifyFile') {
        const oldContent = getStringFromEventData(streamEvent.data, 'oldContent')
        const newContent = getStringFromEventData(streamEvent.data, 'newContent')
        const preview = buildModifiedFilePreview(getLoadedFileContent(filePath) || file.content || '', oldContent, newContent)
        await startStreamingFilePreview(file.path || filePath, preview.targetContent || file.content || '', preview.initialContent)
        return
      }
      await startStreamingFilePreview(file.path || filePath, file.content || '')
    }
  } catch (error) {
    console.error('同步 AI 写入文件内容失败：', error)
  }
}

const showCompileRollbackConfirm = (errorMessage: string) => {
  Modal.confirm({
    title: '代码未通过编译',
    content: `${errorMessage || '本次保存未生效，服务端文件和预览已保持在上一次可用版本。'} 是否将编辑器内容回退到上一次保存的版本？`,
    okText: '确认回退',
    cancelText: '继续编辑',
    onOk: () => {
      fileContent.value = savedFileContent.value
      refreshPreview()
      nextTick(() => {
        focusCodeEditor()
      })
      message.success('已回退到上一次保存的版本')
    },
    onCancel: () => {
      nextTick(() => {
        focusCodeEditor()
      })
      message.info('已保留当前编辑内容，可继续修改后再次保存')
    },
  })
}

const saveCurrentFile = async () => {
  if (!canSaveFile.value) {
    return
  }
  savingFile.value = true
  try {
    const res = await saveAppCodeFile({
      appId: appId.value as unknown as number,
      filePath: selectedFilePath.value,
      content: fileContent.value,
    })
    if (res.data.code === 0 && res.data.data) {
      savedFileContent.value = fileContent.value
      refreshPreview()
      message.success('文件已保存，网页预览已刷新')
      return
    }
    const errorMessage = res.data.message || '保存失败，请检查代码后重试'
    if (isCompileFailureMessage(errorMessage)) {
      showCompileRollbackConfirm(errorMessage)
    } else {
      message.error('保存失败：' + errorMessage)
    }
  } catch (error) {
    console.error('保存文件失败：', error)
    message.error('保存失败，请重试')
  } finally {
    savingFile.value = false
  }
}

const syncDeployment = async () => {
  if (!appId.value || !hasDeployed.value || !isOwner.value) {
    return
  }
  if (isGenerating.value) {
    message.warning('AI 执行过程中暂不支持同步部署')
    return
  }
  if (isFileDirty.value) {
    message.warning('请先保存当前文件后再同步')
    return
  }
  syncingDeploy.value = true
  try {
    const res = await syncAppDeployment({
      appId: appId.value,
    })
    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      const deployKey = extractDeployKey(res.data.data)
      if (appInfo.value && deployKey) {
        appInfo.value = {
          ...appInfo.value,
          deployKey,
          deployedTime: new Date().toISOString(),
        }
      }
      message.success('已同步到部署站点')
      return
    }
    message.error('同步失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('同步部署失败：', error)
    message.error('同步失败，请重试')
  } finally {
    syncingDeploy.value = false
  }
}

const downloadCode = async () => {
  if (isGenerating.value) {
    message.warning('AI 执行过程中暂不支持下载代码')
    return
  }
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }
  downloading.value = true
  try {
    const API_BASE_URL = request.defaults.baseURL || ''
    const url = `${API_BASE_URL}/app/download/${appId.value}`
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`下载失败: ${response.status}`)
    }
    // 获取文件名
    const contentDisposition = response.headers.get('Content-Disposition')
    const fileName = contentDisposition?.match(/filename="(.+)"/)?.[1] || `app-${appId.value}.zip`
    // 下载文件
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    // 清理
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    console.error('下载失败：', error)
    message.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

// 部署应用
const deployApp = async () => {
  if (isGenerating.value) {
    message.warning('AI 执行过程中暂不支持部署')
    return
  }
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }

  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: appId.value,
    })

    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      const deployKey = extractDeployKey(res.data.data)
      if (appInfo.value && deployKey) {
        appInfo.value = {
          ...appInfo.value,
          deployKey,
          deployedTime: new Date().toISOString(),
        }
      }
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } catch (error) {
    console.error('部署失败：', error)
    message.error('部署失败，请重试')
  } finally {
    deploying.value = false
  }
}

// 在新窗口打开预览（dev server 需要用直接 URL，代理在新窗口不可用）
const openInNewTab = () => {
  if (previewUrl.value) {
    // 如果是 dev server 代理路径，转换为直接 URL
    const devServerMatch = previewUrl.value.match(/\/dev-server\/proxy\/\d+\//)
    if (devServerMatch && appInfo.value?.devServerPort) {
      window.open(`http://localhost:${appInfo.value.devServerPort}`, '_blank')
    } else {
      window.open(previewUrl.value, '_blank')
    }
  }
}

// 打开部署的网站
const openDeployedSite = () => {
  const url = currentDeployUrl.value
  if (url) {
    window.open(url, '_blank')
  }
}

// iframe加载完成
const onIframeLoad = () => {
  previewReady.value = true
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (iframe) {
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
  }
}

// 编辑应用
const editApp = () => {
  if (appInfo.value?.id) {
    router.push(`/app/edit/${appInfo.value.id}`)
  }
}

// 删除应用
const deleteApp = async () => {
  if (!appInfo.value?.id) return

  try {
    const res = await deleteAppApi({ id: appInfo.value.id })
    if (res.data.code === 0) {
      message.success('删除成功')
      appDetailVisible.value = false
      router.push('/')
    } else {
      message.error('删除失败：' + res.data.message)
    }
  } catch (error) {
    console.error('删除失败：', error)
    message.error('删除失败')
  }
}

// 可视化编辑相关函数
const toggleEditMode = () => {
  // 检查 iframe 是否已经加载
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (!iframe) {
    message.warning('请等待页面加载完成')
    return
  }
  // 确保 visualEditor 已初始化
  if (!previewReady.value) {
    message.warning('请等待页面加载完成')
    return
  }
  const newEditMode = visualEditor.toggleEditMode()
  isEditMode.value = newEditMode
}

const clearSelectedElement = () => {
  selectedElementInfo.value = null
  visualEditor.clearSelection()
}

const getInputPlaceholder = () => {
  if (selectedElementInfo.value) {
    return `正在编辑 ${selectedElementInfo.value.tagName.toLowerCase()} 元素，描述您想要的修改...`
  }
  return '请描述你想生成的网站，越详细效果越好哦'
}

// 页面加载时获取应用信息
onMounted(() => {
  fetchAppInfo()

  // 监听 iframe 消息
  window.addEventListener('message', handleIframeMessage)
  nextTick(() => {
    updateMessageScrollState()
  })
})

// 清理资源
onUnmounted(() => {
  stopGenerationPolling()
  stopStreamingFilePreview()
  closeActiveEventSource()
  window.removeEventListener('message', handleIframeMessage)
  // 停止 dev server
  stopDevServerForApp()
})

  return {
    activeWorkspaceTab,
    aiAvatar,
    appDetailVisible,
    appDisplayName,
    appInfo,
    appNameDraft,
    canEditAppName,
    canOptimizePrompt,
    canSaveFile,
    cancelAppNameEdit,
    CHAT_PANE_MIN_WIDTH,
    chatLayoutStyle,
    chatPageHeaderRef,
    chatPaneCurrentWidth,
    chatPaneMaxWidth,
    chatPaneWidthRatio,
    chatSplitterRef,
    clearSelectedElement,
    deleteApp,
    deployApp,
    deploying,
    deployModalVisible,
    deployUrl,
    downloadCode,
    downloading,
    editApp,
    editUserMessage,
    editorStatusText,
    expandedFileTreeKeys,
    fileContent,
    fileTreeData,
    formatCodeGenType,
    formatDuration,
    formatMessageTime,
    generationStageDescription,
    generationStatusText,
    generationStepIndex,
    generationSteps,
    getAiMessageSegments,
    getInputPlaceholder,
    getToolCallFileIcon,
    goBack,
    handleChatPaneResizeKeydown,
    handleDatabaseEnabled,
    handleFileSelect,
    handleFileTreeExpand,
    handleMessagesScroll,
    handleSendButtonClick,
    hasDeployed,
    hasMoreHistory,
    highlightedFileContent,
    isAdmin,
    isChatPaneResizing,
    isEditMode,
    isEditingAppName,
    isFileDirty,
    isGenerating,
    isOptimizingPrompt,
    isOwner,
    isStreamingFilePreview,
    isToolCallFileActive,
    latestGenerationFailed,
    loadCodeFiles,
    loadingFileContent,
    loadingFiles,
    loadingHistory,
    loadMoreHistory,
    loginUserAvatar,
    mainContentRef,
    messages,
    messagesPanelRef,
    onIframeLoad,
    openDeployedSite,
    openEditorTab,
    openInNewTab,
    openToolCallFile,
    openFileTabs,
    optimizeUserPrompt,
    previewRefreshKey,
    previewUrl,
    devServerRunning,
    regenerateAiMessage,
    retryLastGeneration,
    saveAppName,
    saveCurrentFile,
    savingAppName,
    savingFile,
    scrollMessagesToBottom,
    selectedElementInfo,
    selectedFileName,
    selectedFilePath,
    sendMessage,
    showAppDetail,
    showScrollToBottom,
    startChatPaneResize,
    startEditAppName,
    stoppingGeneration,
    syncDeployment,
    syncingDeploy,
    toggleAiFeedback,
    toggleEditMode,
    updateAppNameDraft,
    userInput,
  }
  },
})
