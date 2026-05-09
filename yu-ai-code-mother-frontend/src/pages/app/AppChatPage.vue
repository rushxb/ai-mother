<template>
  <div id="appChatPage">
    <!-- 顶部栏 -->
    <div class="header-bar">
      <div class="header-left">
        <div class="app-title-block">
          <div class="app-title-row">
            <span class="workspace-label">AI Workspace</span>
            <a-tag v-if="appInfo?.codeGenType" color="blue" class="code-gen-type-tag">
              {{ formatCodeGenType(appInfo.codeGenType) }}
            </a-tag>
          </div>
          <h1 class="app-name">{{ appInfo?.appName || '网站生成器' }}</h1>
        </div>
      </div>
      <div class="header-right">
        <a-button class="toolbar-button" type="default" @click="showAppDetail">
          <template #icon>
            <InfoCircleOutlined />
          </template>
          应用详情
        </a-button>
        <a-button
            class="toolbar-button"
            type="primary"
            ghost
            @click="downloadCode"
            :loading="downloading"
            :disabled="!isOwner"
        >
          <template #icon>
            <DownloadOutlined />
          </template>
          下载代码
        </a-button>
        <a-button
            v-if="hasDeployed"
            class="toolbar-button deploy-button"
            type="primary"
            @click="openDeployedSite"
        >
          <template #icon>
            <ExportOutlined />
          </template>
          查看作品
        </a-button>
        <a-button
            v-else
            class="toolbar-button deploy-button"
            type="primary"
            @click="deployApp"
            :loading="deploying"
            :disabled="!isOwner"
        >
          <template #icon>
            <CloudUploadOutlined />
          </template>
          部署
        </a-button>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="main-content">
      <!-- 左侧对话区域 -->
      <div class="chat-section">
        <div class="section-header chat-header">
          <div>
            <span class="section-kicker">Conversation</span>
            <h2>AI 对话</h2>
          </div>
          <div class="generation-status" :class="{ active: isGenerating }">
            <span class="status-dot"></span>
            {{ isGenerating ? '生成中' : '就绪' }}
          </div>
        </div>

        <!-- 消息区域 -->
        <div class="messages-container" ref="messagesContainer">
          <!-- 加载更多按钮 -->
          <div v-if="hasMoreHistory" class="load-more-container">
            <a-button type="link" @click="loadMoreHistory" :loading="loadingHistory" size="small">
              加载更多历史消息
            </a-button>
          </div>
          <div
              v-for="(message, index) in messages"
              :key="index"
              class="message-item"
              :class="`message-item-${message.type}`"
          >
            <div v-if="message.type === 'user'" class="user-message">
              <div class="message-content">{{ message.content }}</div>
              <div class="message-avatar">
                <a-avatar :src="loginUserAvatar" />
              </div>
            </div>
            <div v-else class="ai-message">
              <div class="message-avatar">
                <a-avatar :src="aiAvatar" />
              </div>
              <div class="message-content">
                <MarkdownRenderer v-if="message.content" :content="message.content" />
                <div v-if="message.loading" class="loading-indicator">
                  <a-spin size="small" />
                  <span>AI 正在思考...</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 选中元素信息展示 -->
        <a-alert
            v-if="selectedElementInfo"
            class="selected-element-alert"
            type="info"
            closable
            @close="clearSelectedElement"
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

        <!-- 用户消息输入框 -->
        <div class="input-container">
          <div class="input-wrapper">
            <a-tooltip v-if="!isOwner" title="无法在别人的作品下对话哦~" placement="top">
              <a-textarea
                  v-model:value="userInput"
                  :placeholder="getInputPlaceholder()"
                  :rows="4"
                  :maxlength="1000"
                  @keydown.enter.prevent="sendMessage"
                  :disabled="isGenerating || isOptimizingPrompt || !isOwner"
              />
            </a-tooltip>
            <a-textarea
                v-else
                v-model:value="userInput"
                :placeholder="getInputPlaceholder()"
                :rows="4"
                :maxlength="1000"
                @keydown.enter.prevent="sendMessage"
                :disabled="isGenerating || isOptimizingPrompt"
            />
            <div class="input-actions">
              <a-tooltip title="优化提示词" placement="top">
                <a-button
                    class="optimize-button"
                    @click="optimizeUserPrompt"
                    :loading="isOptimizingPrompt"
                    :disabled="!canOptimizePrompt"
                >
                  <template #icon>
                    <BulbOutlined />
                  </template>
                  优化
                </a-button>
              </a-tooltip>
              <a-button
                  class="send-button"
                  type="primary"
                  @click="sendMessage"
                  :loading="isGenerating"
                  :disabled="!isOwner || isOptimizingPrompt"
              >
                <template #icon>
                  <SendOutlined />
                </template>
              </a-button>
            </div>
          </div>
        </div>
      </div>
      <!-- 右侧网页展示 / 文件编辑区域 -->
      <div class="preview-section" :class="{ 'is-editing': isEditMode }">
        <div class="preview-header">
          <div class="preview-title">
            <div class="browser-dots">
              <span></span>
              <span></span>
              <span></span>
            </div>
            <h3>{{ activeWorkspaceTab === 'preview' ? '生成后的网页展示' : '生成文件内容' }}</h3>
          </div>
          <div class="preview-actions">
            <a-button
                v-if="activeWorkspaceTab === 'preview' && isOwner && previewUrl"
                type="link"
                :danger="isEditMode"
                @click="toggleEditMode"
                :class="{ 'edit-mode-active': isEditMode }"
                style="padding: 0; height: auto; margin-right: 12px"
            >
              <template #icon>
                <EditOutlined />
              </template>
              {{ isEditMode ? '退出编辑' : '编辑模式' }}
            </a-button>
            <a-button v-if="activeWorkspaceTab === 'preview' && previewUrl" type="link" @click="openInNewTab">
              <template #icon>
                <ExportOutlined />
              </template>
              新窗口打开
            </a-button>
            <a-button
                v-if="activeWorkspaceTab === 'files'"
                type="link"
                @click="loadCodeFiles"
                :loading="loadingFiles"
                :disabled="!isOwner"
            >
              <template #icon>
                <ReloadOutlined />
              </template>
              刷新文件
            </a-button>
            <a-button
                v-if="activeWorkspaceTab === 'files'"
                type="link"
                @click="saveCurrentFile"
                :loading="savingFile"
                :disabled="!canSaveFile"
            >
              <template #icon>
                <SaveOutlined />
              </template>
              保存
            </a-button>
            <a-button
                v-if="activeWorkspaceTab === 'files'"
                type="primary"
                ghost
                @click="syncDeployment"
                :loading="syncingDeploy"
                :disabled="!isOwner || !hasDeployed"
            >
              <template #icon>
                <CloudSyncOutlined />
              </template>
              同步
            </a-button>
          </div>
        </div>
        <a-tabs v-model:activeKey="activeWorkspaceTab" class="workspace-tabs" @change="handleWorkspaceTabChange">
          <a-tab-pane key="preview">
            <template #tab>
              <span class="workspace-tab-label">
                <GlobalOutlined />
                网页预览
              </span>
            </template>
            <div class="preview-content">
              <div v-if="!previewUrl && !isGenerating" class="preview-placeholder">
                <div class="placeholder-icon">
                  <GlobalOutlined />
                </div>
                <p>网站文件生成完成后将在这里展示</p>
              </div>
              <div v-else-if="isGenerating" class="preview-loading">
                <a-spin size="large" />
                <p>正在生成网站...</p>
              </div>
              <iframe
                  v-else
                  :key="previewRefreshKey"
                  :src="previewUrl"
                  class="preview-iframe"
                  frameborder="0"
                  @load="onIframeLoad"
              ></iframe>
            </div>
          </a-tab-pane>
          <a-tab-pane key="files">
            <template #tab>
              <span class="workspace-tab-label">
                <FolderOpenOutlined />
                文件编辑
              </span>
            </template>
            <div class="file-workspace">
              <aside class="file-sidebar">
                <div class="file-sidebar-header">
                  <span>文件资源管理器</span>
                  <a-tag v-if="isFileDirty" color="warning">未保存</a-tag>
                </div>
                <div v-if="!isOwner" class="file-empty-state">
                  仅应用创建者可以编辑文件
                </div>
                <div v-else-if="loadingFiles" class="file-loading">
                  <a-spin size="small" />
                  <span>正在加载文件...</span>
                </div>
                <div v-else-if="fileTreeData.length" class="file-tree-scroll">
                  <a-tree
                      class="code-file-tree"
                      :tree-data="fileTreeData"
                      :selected-keys="selectedFilePath ? [selectedFilePath] : []"
                      default-expand-all
                      block-node
                      @select="handleFileSelect"
                  />
                </div>
                <div v-else class="file-empty-state">
                  生成代码后可在这里查看文件
                </div>
              </aside>
              <section class="file-editor-panel">
                <div v-if="!selectedFilePath" class="editor-placeholder">
                  <div class="placeholder-icon">
                    <FileTextOutlined />
                  </div>
                  <p>从左侧选择文件进行预览和编辑</p>
                </div>
                <div v-else class="editor-shell">
                  <div class="editor-titlebar">
                    <div class="editor-file-meta">
                      <FileTextOutlined />
                      <span class="editor-file-name">{{ selectedFileName }}</span>
                      <span class="editor-file-path">{{ selectedFilePath }}</span>
                    </div>
                    <span class="editor-status">{{ editorStatusText }}</span>
                  </div>
                  <div v-if="loadingFileContent" class="editor-loading">
                    <a-spin />
                    <span>正在读取文件...</span>
                  </div>
                  <div v-else class="code-editor-shell">
                    <pre class="code-highlight-layer" ref="codeHighlightRef" aria-hidden="true"><code v-html="highlightedFileContent"></code></pre>
                    <textarea
                        v-model="fileContent"
                        ref="codeEditorTextareaRef"
                        class="code-editor"
                        spellcheck="false"
                        :disabled="savingFile"
                        @scroll="syncCodeEditorScroll"
                    />
                  </div>
                </div>
              </section>
            </div>
          </a-tab-pane>
        </a-tabs>
      </div>
    </div>

    <!-- 应用详情弹窗 -->
    <AppDetailModal
        v-model:open="appDetailVisible"
        :app="appInfo"
        :show-actions="isOwner || isAdmin"
        @edit="editApp"
        @delete="deleteApp"
    />

    <!-- 部署成功弹窗 -->
    <DeploySuccessModal
        v-model:open="deployModalVisible"
        :deploy-url="deployUrl"
        @open-site="openDeployedSite"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
  optimizePrompt,
  listAppCodeFiles,
  getAppCodeFileContent,
  saveAppCodeFile,
  syncAppDeployment,
} from '@/api/appController'
import { listAppChatHistory } from '@/api/chatHistoryController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import request from '@/request'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import { API_BASE_URL, getDeployUrl, getStaticPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

import {
  CloudUploadOutlined,
  SendOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  DownloadOutlined,
  EditOutlined,
  GlobalOutlined,
  BulbOutlined,
  FolderOpenOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SaveOutlined,
  CloudSyncOutlined,
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)

// 应用信息
const appInfo = ref<API.AppVO>()
const appId = ref<any>()

// 对话相关
interface Message {
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  createTime?: string
}

const messages = ref<Message[]>([])
const userInput = ref('')
const isGenerating = ref(false)
const isOptimizingPrompt = ref(false)
const messagesContainer = ref<HTMLElement>()

// 对话历史相关
const loadingHistory = ref(false)
const hasMoreHistory = ref(false)
const lastCreateTime = ref<string>()
const historyLoaded = ref(false)

// 预览相关
const previewUrl = ref('')
const previewReady = ref(false)
const previewRefreshKey = ref(0)
const activeWorkspaceTab = ref<'preview' | 'files'>('preview')

interface FileTreeNode {
  title: string
  key: string
  isLeaf: boolean
  selectable: boolean
  children?: FileTreeNode[]
  raw: API.AppCodeFileTreeVO
}

const loadingFiles = ref(false)
const loadingFileContent = ref(false)
const savingFile = ref(false)
const syncingDeploy = ref(false)
const fileTreeData = ref<FileTreeNode[]>([])
const selectedFilePath = ref('')
const selectedFileName = ref('')
const fileContent = ref('')
const savedFileContent = ref('')
const codeEditorTextareaRef = ref()
const codeHighlightRef = ref<HTMLElement>()

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

const handleIframeMessage = (event: MessageEvent) => {
  visualEditor.handleIframeMessage(event)
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

const isFileDirty = computed(() => {
  return selectedFilePath.value && fileContent.value !== savedFileContent.value
})

const canSaveFile = computed(() => {
  return Boolean(isOwner.value && selectedFilePath.value && isFileDirty.value && !savingFile.value && !loadingFileContent.value)
})

const editorStatusText = computed(() => {
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

const codeLanguageAliasMap: Record<string, string> = {
  html: 'xml',
  vue: 'xml',
  svg: 'xml',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  css: 'css',
  json: 'json',
  md: 'markdown',
  markdown: 'markdown',
  xml: 'xml',
  yml: 'yaml',
  yaml: 'yaml',
}

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

// 应用详情相关
const appDetailVisible = ref(false)

// 显示应用详情
const showAppDetail = () => {
  appDetailVisible.value = true
}

// 加载对话历史
const loadChatHistory = async (isLoadMore = false) => {
  if (!appId.value || loadingHistory.value) return
  loadingHistory.value = true
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
        const historyMessages: Message[] = chatHistories
            .map((chat) => ({
              type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
              content: chat.message || '',
              createTime: chat.createTime,
            }))
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
    const res = await getAppVoById({ id: id as unknown as number })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data

      // 先加载对话历史
      await loadChatHistory()
      // 如果有至少2条对话记录，展示对应的网站
      if (messages.value.length >= 2) {
        updatePreview()
      }
      // 检查是否需要自动发送初始提示词
      // 只有在是自己的应用且没有对话历史时才自动发送
      if (
          appInfo.value.initPrompt &&
          isOwner.value &&
          messages.value.length === 0 &&
          historyLoaded.value
      ) {
        await sendInitialMessage(appInfo.value.initPrompt)
      }
    } else {
      message.error('获取应用信息失败')
      router.push('/')
    }
  } catch (error) {
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

// 发送初始消息
const sendInitialMessage = async (prompt: string) => {
  // 添加用户消息
  messages.value.push({
    type: 'user',
    content: prompt,
  })

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(prompt, aiMessageIndex)
}

// 发送消息
const sendMessage = async () => {
  if (!userInput.value.trim() || isGenerating.value) {
    return
  }

  let message = userInput.value.trim()
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
  userInput.value = ''
  // 添加用户消息（包含元素信息）
  messages.value.push({
    type: 'user',
    content: message,
  })

  // 发送消息后，清除选中元素并退出编辑模式
  if (selectedElementInfo.value) {
    clearSelectedElement()
    if (isEditMode.value) {
      toggleEditMode()
    }
  }

  // 添加AI消息占位符
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(message, aiMessageIndex)
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

  try {
    // 获取 axios 配置的 baseURL
    const baseURL = request.defaults.baseURL || API_BASE_URL

    // 构建URL参数
    const params = new URLSearchParams({
      appId: appId.value || '',
      message: userMessage,
    })

    const url = `${baseURL}/app/chat/gen/code?${params}`

    // 创建 EventSource 连接
    eventSource = new EventSource(url, {
      withCredentials: true,
    })

    let fullContent = ''

    // 处理接收到的消息
    eventSource.onmessage = function (event) {
      if (streamCompleted) return

      try {
        // 解析JSON包装的数据
        const parsed = JSON.parse(event.data)
        const content = parsed.d

        // 拼接内容
        if (content !== undefined && content !== null) {
          fullContent += content
          messages.value[aiMessageIndex].content = fullContent
          messages.value[aiMessageIndex].loading = false
          scrollToBottom()
        }
      } catch (error) {
        console.error('解析消息失败:', error)
        handleError(error, aiMessageIndex)
      }
    }

    // 处理done事件
    eventSource.addEventListener('done', function () {
      if (streamCompleted) return

      streamCompleted = true
      isGenerating.value = false
      eventSource?.close()

      // 延迟更新预览，确保后端已完成处理
      setTimeout(async () => {
        await fetchAppInfo()
        updatePreview()
        if (activeWorkspaceTab.value === 'files') {
          await loadCodeFiles()
        }
      }, 1000)
    })

    // 处理business-error事件（后端限流等错误）
    eventSource.addEventListener('business-error', function (event: MessageEvent) {
      if (streamCompleted) return

      try {
        const errorData = JSON.parse(event.data)
        console.error('SSE业务错误事件:', errorData)

        // 显示具体的错误信息
        const errorMessage = errorData.message || '生成过程中出现错误'
        messages.value[aiMessageIndex].content = `❌ ${errorMessage}`
        messages.value[aiMessageIndex].loading = false
        message.error(errorMessage)

        streamCompleted = true
        isGenerating.value = false
        eventSource?.close()
      } catch (parseError) {
        console.error('解析错误事件失败:', parseError, '原始数据:', event.data)
        handleError(new Error('服务器返回错误'), aiMessageIndex)
      }
    })

    // 处理错误
    eventSource.onerror = function () {
      if (streamCompleted || !isGenerating.value) return
      // 检查是否是正常的连接关闭
      if (eventSource?.readyState === EventSource.CONNECTING) {
        streamCompleted = true
        isGenerating.value = false
        eventSource?.close()

        setTimeout(async () => {
          await fetchAppInfo()
          updatePreview()
          if (activeWorkspaceTab.value === 'files') {
            await loadCodeFiles()
          }
        }, 1000)
      } else {
        handleError(new Error('SSE连接错误'), aiMessageIndex)
      }
    }
  } catch (error) {
    console.error('创建 EventSource 失败：', error)
    handleError(error, aiMessageIndex)
  }
}

// 错误处理函数
const handleError = (error: unknown, aiMessageIndex: number) => {
  console.error('生成代码失败：', error)
  messages.value[aiMessageIndex].content = '抱歉，生成过程中出现了错误，请重试。'
  messages.value[aiMessageIndex].loading = false
  message.error('生成失败，请重试')
  isGenerating.value = false
}

const appendPreviewCacheBuster = (url: string) => {
  if (!url) {
    return ''
  }
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}t=${Date.now()}`
}

const refreshPreview = () => {
  if (!appId.value) {
    return
  }
  const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
  previewUrl.value = appendPreviewCacheBuster(getStaticPreviewUrl(codeGenType, appId.value))
  previewReady.value = false
  previewRefreshKey.value += 1
}

// 更新预览
const updatePreview = () => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const newPreviewUrl = getStaticPreviewUrl(codeGenType, appId.value)
    previewUrl.value = newPreviewUrl
    previewReady.value = true
  }
}

const mapFileTreeNodes = (nodes: API.AppCodeFileTreeVO[] = []): FileTreeNode[] => {
  return nodes.map((node) => {
    const isDirectory = Boolean(node.directory)
    return {
      title: node.name || node.path || '',
      key: node.path || node.name || '',
      isLeaf: !isDirectory,
      selectable: !isDirectory,
      children: isDirectory ? mapFileTreeNodes(node.children || []) : undefined,
      raw: node,
    }
  })
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

const loadFileContent = async (filePath: string) => {
  if (!appId.value || !filePath) {
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
  if (isFileDirty.value) {
    message.warning('当前文件有未保存修改，请先保存后再切换')
    return
  }
  await loadFileContent(filePath)
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

const getCodeEditorTextareaElement = () => {
  const editorRef = codeEditorTextareaRef.value
  if (!editorRef) {
    return null
  }
  if (editorRef instanceof HTMLTextAreaElement) {
    return editorRef
  }
  return editorRef.$el?.querySelector('textarea') || null
}

const syncCodeEditorScroll = () => {
  const textarea = getCodeEditorTextareaElement()
  if (!textarea || !codeHighlightRef.value) {
    return
  }
  codeHighlightRef.value.scrollTop = textarea.scrollTop
  codeHighlightRef.value.scrollLeft = textarea.scrollLeft
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
    message.error('保存失败：' + (res.data.message || '请重试'))
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
  if (isFileDirty.value) {
    message.warning('请先保存当前文件后再同步')
    return
  }
  syncingDeploy.value = true
  try {
    const res = await syncAppDeployment({
      appId: appId.value as unknown as number,
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

// 滚动到底部
const scrollToBottom = () => {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 下载代码
const downloadCode = async () => {
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
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }

  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: appId.value as unknown as number,
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

const extractDeployKey = (url: string) => {
  try {
    const parsedUrl = new URL(url)
    const pathSegments = parsedUrl.pathname.split('/').filter(Boolean)
    return pathSegments[pathSegments.length - 1] || ''
  } catch {
    const pathSegments = url.split('/').filter(Boolean)
    return pathSegments[pathSegments.length - 1] || ''
  }
}

// 在新窗口打开预览
const openInNewTab = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank')
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
})

// 清理资源
onUnmounted(() => {
  window.removeEventListener('message', handleIframeMessage)
})
</script>

<style scoped>
#appChatPage {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 20px;
  gap: 16px;
  background:
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.08), transparent 28%),
    linear-gradient(180deg, #fcfdff 0%, #f5f8fc 100%);
}

.header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 22px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 24px;
  box-shadow:
    0 14px 40px rgba(15, 23, 42, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(18px);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.app-title-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.app-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
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

.app-name {
  margin: 0;
  font-size: 28px;
  line-height: 1.1;
  font-weight: 700;
  color: #0f172a;
}

.header-right {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-button {
  height: 42px;
  border-radius: 999px;
  padding-inline: 18px;
  box-shadow: none;
  transition:
    transform 0.24s ease,
    box-shadow 0.24s ease,
    border-color 0.24s ease;
}

.toolbar-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.08);
}

.deploy-button {
  box-shadow: 0 12px 28px rgba(22, 119, 255, 0.18);
}

.main-content {
  flex: 1;
  display: flex;
  gap: 18px;
  overflow: hidden;
  min-height: 0;
}

.chat-section {
  flex: 2;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(148, 163, 184, 0.14);
  border-radius: 28px;
  box-shadow:
    0 18px 42px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  overflow: hidden;
  backdrop-filter: blur(20px);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 18px;
}

.chat-header {
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

.section-kicker {
  display: inline-block;
  margin-bottom: 8px;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.section-header h2 {
  margin: 0;
  font-size: 22px;
  line-height: 1.1;
  color: #0f172a;
}

.generation-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.04);
  color: #475569;
  font-size: 13px;
  font-weight: 600;
}

.generation-status.active {
  background: rgba(22, 119, 255, 0.1);
  color: #1677ff;
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

.messages-container {
  flex: 1;
  padding: 18px 24px 12px;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.message-item {
  margin-bottom: 16px;
  animation: messageRise 0.28s ease;
}

.message-item-user + .message-item-ai,
.message-item-ai + .message-item-user {
  margin-top: 20px;
}

.user-message {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 8px;
}

.ai-message {
  display: flex;
  justify-content: flex-start;
  align-items: flex-start;
  gap: 8px;
}

.message-content {
  max-width: 70%;
  padding: 14px 18px;
  border-radius: 20px;
  line-height: 1.7;
  word-wrap: break-word;
  transition: transform 0.24s ease, box-shadow 0.24s ease;
}

.message-content:hover {
  transform: translateY(-1px);
}

.user-message .message-content {
  background: linear-gradient(135deg, #1677ff 0%, #3b82f6 100%);
  color: white;
  border-top-right-radius: 8px;
  box-shadow: 0 14px 30px rgba(22, 119, 255, 0.2);
}

.ai-message .message-content {
  background: rgba(255, 255, 255, 0.92);
  color: #0f172a;
  padding: 14px 18px;
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-top-left-radius: 8px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.message-avatar {
  flex-shrink: 0;
  padding-top: 4px;
}

:deep(.message-avatar .ant-avatar) {
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.1);
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
  color: #64748b;
}

.load-more-container {
  text-align: center;
  padding: 4px 0 10px;
  margin-bottom: 12px;
}

.input-container {
  padding: 16px 24px 24px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0) 0%, rgba(248, 250, 252, 0.9) 100%);
}

.input-wrapper {
  position: relative;
  padding: 12px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow:
    0 18px 40px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
}

:deep(.input-wrapper .ant-input) {
  padding: 14px 132px 14px 16px;
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
  right: 14px;
  bottom: 14px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.optimize-button {
  height: 44px;
  border-radius: 14px;
  padding-inline: 14px;
  color: #1677ff;
  border-color: rgba(22, 119, 255, 0.18);
  background: rgba(22, 119, 255, 0.06);
  font-weight: 600;
  box-shadow: none;
}

.send-button {
  width: 44px;
  min-width: 44px;
  height: 44px;
  border-radius: 14px;
  box-shadow: 0 14px 28px rgba(22, 119, 255, 0.22);
}

.preview-section {
  flex: 3;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.14);
  border-radius: 28px;
  box-shadow:
    0 18px 42px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  overflow: hidden;
  backdrop-filter: blur(20px);
  transition: box-shadow 0.24s ease, transform 0.24s ease;
}

.preview-section.is-editing {
  box-shadow:
    0 22px 50px rgba(22, 119, 255, 0.12),
    inset 0 0 0 1px rgba(22, 119, 255, 0.18);
}

.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(248, 250, 252, 0.72);
}

.preview-title {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.browser-dots {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.browser-dots span {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.browser-dots span:nth-child(1) {
  background: #fb7185;
}

.browser-dots span:nth-child(2) {
  background: #fbbf24;
}

.browser-dots span:nth-child(3) {
  background: #34d399;
}

.preview-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.preview-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.workspace-tabs {
  flex: 1;
  display: flex;
  min-height: 0;
  flex-direction: column;
}

:deep(.workspace-tabs .ant-tabs-nav) {
  margin: 0;
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.72);
}

:deep(.workspace-tabs .ant-tabs-content-holder),
:deep(.workspace-tabs .ant-tabs-content),
:deep(.workspace-tabs .ant-tabs-tabpane) {
  flex: 1;
  min-height: 0;
  height: 100%;
}

:deep(.workspace-tabs .ant-tabs-content-holder),
:deep(.workspace-tabs .ant-tabs-content) {
  display: flex;
  overflow: hidden;
}

:deep(.workspace-tabs .ant-tabs-tabpane) {
  overflow: hidden;
}

:deep(.workspace-tabs .ant-tabs-tabpane-active) {
  display: flex;
  flex-direction: column;
}

.workspace-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.preview-content {
  flex: 1;
  height: 100%;
  position: relative;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72) 0%, rgba(241, 245, 249, 0.56) 100%);
}

.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #64748b;
  gap: 14px;
  text-align: center;
}

.placeholder-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.08);
  font-size: 30px;
}

.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #64748b;
}

.preview-loading p {
  margin-top: 16px;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.file-workspace {
  flex: 1;
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  background: #f8fafc;
}

.file-sidebar {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(148, 163, 184, 0.16);
  background: rgba(255, 255, 255, 0.78);
}

.file-sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 0 14px;
  color: #334155;
  font-size: 13px;
  font-weight: 700;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
}

.file-loading,
.file-empty-state,
.editor-placeholder,
.editor-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #64748b;
  font-size: 13px;
}

.file-loading,
.file-empty-state {
  flex: 1;
  padding: 18px;
  text-align: center;
}

.file-tree-scroll {
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 10px 8px;
}

.code-file-tree {
  min-width: max-content;
  background: transparent;
}

:deep(.code-file-tree .ant-tree-node-content-wrapper) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  border-radius: 8px;
}

:deep(.code-file-tree .ant-tree-title) {
  font-size: 13px;
}

.file-editor-panel {
  min-width: 0;
  min-height: 0;
  display: flex;
  background: #ffffff;
}

.editor-placeholder {
  flex: 1;
  flex-direction: column;
}

.editor-shell {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.editor-titlebar {
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
  background: rgba(248, 250, 252, 0.92);
}

.editor-file-meta {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.editor-file-name {
  flex: none;
  color: #0f172a;
  font-size: 13px;
  font-weight: 700;
}

.editor-file-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #64748b;
  font-size: 12px;
}

.editor-status {
  flex: none;
  color: #64748b;
  font-size: 12px;
}

.editor-loading {
  flex: 1;
}

.code-editor-shell {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: #ffffff;
}

.code-highlight-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  margin: 0;
  padding: 16px;
  overflow: auto;
  pointer-events: none;
  background: #ffffff;
  color: #0f172a;
  font-family: Consolas, "SFMono-Regular", "Liberation Mono", monospace;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre;
  word-break: normal;
  tab-size: 2;
}

.code-highlight-layer code {
  display: block;
  min-height: 100%;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
}

.code-editor {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  resize: none;
  border: 0;
  border-radius: 0;
  padding: 16px;
  overflow: auto;
  background: transparent;
  color: transparent;
  caret-color: #0f172a;
  font-family: Consolas, "SFMono-Regular", "Liberation Mono", monospace;
  font-size: 13px;
  line-height: 1.7;
  tab-size: 2;
  white-space: pre;
  word-break: normal;
  outline: none;
}

.code-editor:focus {
  box-shadow: none;
}

.code-editor::selection {
  background: rgba(59, 130, 246, 0.35);
}

.selected-element-alert {
  margin: 0 24px;
  border-radius: 18px;
  border: 1px solid rgba(22, 119, 255, 0.14);
  background: rgba(240, 247, 255, 0.92);
}

.selected-element-info {
  line-height: 1.6;
}

.element-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.element-details {
  display: grid;
  gap: 8px;
}

.element-item {
  font-size: 13px;
  color: #334155;
}

.element-tag,
.element-id,
.element-class {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.element-tag {
  background: rgba(22, 119, 255, 0.12);
  color: #1677ff;
}

.element-id {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}

.element-class {
  background: rgba(245, 158, 11, 0.14);
  color: #d97706;
}

.element-selector-code {
  display: inline-block;
  margin-top: 4px;
  padding: 6px 10px;
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.06);
  border: 1px solid rgba(148, 163, 184, 0.18);
  color: #0f172a;
  font-size: 12px;
  word-break: break-all;
}

.edit-mode-active {
  background: rgba(22, 119, 255, 0.12) !important;
  border-color: transparent !important;
  color: #1677ff !important;
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

@media (max-width: 1024px) {
  #appChatPage {
    height: auto;
    min-height: 100vh;
  }

  .header-bar {
    align-items: flex-start;
    flex-direction: column;
  }

  .main-content {
    flex-direction: column;
  }

  .chat-section,
  .preview-section {
    flex: none;
    min-height: 480px;
  }
}

@media (max-width: 768px) {
  #appChatPage {
    padding: 12px;
    gap: 12px;
  }

  .header-bar {
    padding: 16px;
  }

  .app-name {
    font-size: 22px;
  }

  .main-content {
    gap: 12px;
  }

  .message-content {
    max-width: 85%;
  }

  .selected-element-alert {
    margin: 0 16px;
  }

  .section-header,
  .preview-header,
  .messages-container,
  .input-container {
    padding-left: 16px;
    padding-right: 16px;
  }

  .section-header,
  .preview-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .preview-actions,
  .header-right {
    width: 100%;
  }

  .preview-actions {
    gap: 4px;
  }

  .toolbar-button {
    flex: 1 1 auto;
    justify-content: center;
  }

  .file-workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 220px minmax(0, 1fr);
  }

  .file-sidebar {
    border-right: 0;
    border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  }

  .input-container {
    padding-top: 12px;
    padding-bottom: 16px;
  }

  .preview-title {
    width: 100%;
  }

  .preview-header h3,
  .section-header h2 {
    font-size: 18px;
  }

  .messages-container {
    padding-top: 12px;
  }
}
</style>
