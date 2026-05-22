<template>
  <div class="preview-section" :class="{ 'is-editing': isEditMode }">
    <a-tabs
        :active-key="activeWorkspaceTab"
        class="workspace-tabs"
        @change="$emit('update:activeWorkspaceTab', $event as WorkspaceTabKey)"
    >
      <template #rightExtra>
        <div class="workspace-tabs-actions">
          <ChatToolbarButton type="text" @click="$emit('showAppDetail')">
            <template #icon>
              <InfoCircleOutlined />
            </template>
            应用详情
          </ChatToolbarButton>
          <ChatToolbarButton
              type="text"
              :loading="downloading"
              :disabled="!isOwner || isGenerating"
              @click="$emit('downloadCode')"
          >
            <template #icon>
              <DownloadOutlined />
            </template>
            下载代码
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="hasDeployed"
              class="deploy-button"
              type="text"
              @click="$emit('openDeployedSite')"
          >
            <template #icon>
              <ExportOutlined />
            </template>
            查看作品
          </ChatToolbarButton>
          <ChatToolbarButton
              v-else
              class="deploy-button"
              type="text"
              :loading="deploying"
              :disabled="!isOwner || isGenerating"
              @click="$emit('deployApp')"
          >
            <template #icon>
              <CloudUploadOutlined />
            </template>
            部署
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="activeWorkspaceTab === 'preview' && isOwner && previewUrl"
              type="text"
              :danger="isEditMode"
              :class="{ 'edit-mode-active': isEditMode }"
              @click="$emit('toggleEditMode')"
          >
            <template #icon>
              <EditOutlined />
            </template>
            {{ isEditMode ? '退出编辑' : '编辑模式' }}
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="activeWorkspaceTab === 'preview' && previewUrl"
              type="text"
              @click="$emit('openInNewTab')"
          >
            <template #icon>
              <ExportOutlined />
            </template>
            新窗口打开
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="activeWorkspaceTab === 'files'"
              type="text"
              :loading="loadingFiles"
              :disabled="!isOwner"
              @click="$emit('loadCodeFiles')"
          >
            <template #icon>
              <ReloadOutlined />
            </template>
            刷新文件
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="activeWorkspaceTab === 'files'"
              type="text"
              :disabled="!canSaveFile"
              @click="$emit('saveCurrentFile')"
          >
            <template #icon>
              <SaveOutlined />
            </template>
            保存
          </ChatToolbarButton>
          <ChatToolbarButton
              v-if="activeWorkspaceTab === 'files'"
              class="deploy-button"
              type="text"
              :loading="syncingDeploy"
              :disabled="!isOwner || !hasDeployed || isGenerating"
              @click="$emit('syncDeployment')"
          >
            <template #icon>
              <CloudSyncOutlined />
            </template>
            同步
          </ChatToolbarButton>
        </div>
      </template>
      <a-tab-pane key="preview">
        <template #tab>
          <span class="workspace-tab-label">
            <GlobalOutlined />
            <span class="workspace-tab-text">网页预览</span>
          </span>
        </template>
        <div class="preview-content">
          <div v-if="!previewUrl && !isGenerating" class="preview-placeholder">
            <div class="placeholder-icon">
              <GlobalOutlined />
            </div>
            <p>网站文件生成完成后将在这里展示</p>
          </div>
          <div v-if="isGenerating" class="preview-progress-overlay" :class="{ 'has-preview': Boolean(previewUrl) }">
            <div class="preview-progress-panel">
              <div class="preview-progress-head">
                <a-spin size="small" />
                <div>
                  <strong>{{ generationStatusText }}</strong>
                  <span>{{ generationStageDescription }}</span>
                </div>
              </div>
              <div class="generation-steps">
                <span
                    v-for="step in generationSteps"
                    :key="step.key"
                    class="generation-step"
                    :class="{ active: generationStepIndex === step.index, done: generationStepIndex > step.index }"
                >
                  {{ step.label }}
                </span>
              </div>
            </div>
          </div>
          <iframe
              v-if="previewUrl"
              :key="previewRefreshKey"
              ref="previewIframeRef"
              class="preview-iframe"
              frameborder="0"
              @load="$emit('iframeLoad')"
          ></iframe>
          <div v-else-if="isGenerating" class="preview-loading">
            <a-spin size="large" />
            <p>{{ generationStageDescription }}</p>
          </div>
        </div>
      </a-tab-pane>
      <a-tab-pane key="files">
        <template #tab>
          <span class="workspace-tab-label">
            <FolderOpenOutlined />
            <span class="workspace-tab-text">文件编辑</span>
          </span>
        </template>
        <div ref="fileWorkspaceRef" class="file-workspace" :style="fileWorkspaceStyle">
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
              <InspiraFileTree
                  :nodes="fileTreeData"
                  :selected-key="selectedFilePath"
                  :expanded-keys="expandedFileTreeKeys"
                  @select="$emit('handleFileSelect', $event)"
                  @expand="$emit('handleFileTreeExpand', $event)"
              >
                <template #empty>生成代码后可在这里查看文件</template>
              </InspiraFileTree>
            </div>
            <div v-else class="file-empty-state">
              生成代码后可在这里查看文件
            </div>
          </aside>
          <div
              class="file-sidebar-resizer"
              role="separator"
              aria-orientation="vertical"
              tabindex="0"
              :aria-label="`调整文件资源管理器宽度，当前 ${fileSidebarWidth}px`"
              @pointerdown="startFileSidebarResize"
              @keydown="handleFileSidebarResizeKeydown"
          >
            <span class="file-sidebar-resizer-handle" />
          </div>
          <section class="file-editor-panel">
            <div v-if="!selectedFilePath" class="editor-placeholder">
              <div class="placeholder-icon">
                <FileTextOutlined />
              </div>
              <p>从左侧选择文件进行预览和编辑</p>
            </div>
            <div v-else class="editor-shell">
              <div class="editor-tabbar">
                <div class="editor-tabs">
                  <button
                      v-for="tab in openFileTabs"
                      :key="tab.path"
                      type="button"
                      class="editor-tab"
                      :class="{ active: tab.path === selectedFilePath }"
                      :title="tab.path"
                      @click="$emit('openEditorTab', tab.path)"
                  >
                    <FileTextOutlined />
                    <span class="editor-tab-name">{{ tab.name }}</span>
                    <span
                        v-if="tab.path === selectedFilePath && isFileDirty"
                        class="editor-tab-dirty"
                        aria-label="未保存修改"
                    />
                  </button>
                </div>
                <span class="editor-status" :class="{ streaming: isStreamingFilePreview }">
                  {{ editorStatusText }}
                </span>
              </div>
              <div v-if="loadingFileContent" class="editor-loading">
                <a-spin />
                <span>正在读取文件...</span>
              </div>
              <div v-else class="code-editor-shell">
                <div class="code-pathbar">{{ selectedFilePath }}</div>
                <div class="code-editor-frame">
                  <div ref="lineNumberGutterRef" class="code-line-numbers" aria-hidden="true">
                    <span v-for="lineNumber in editorLineNumbers" :key="lineNumber">{{ lineNumber }}</span>
                  </div>
                  <div class="code-editor-stage">
                    <pre ref="codeHighlightRef" class="code-highlight-layer" aria-hidden="true"><code v-html="highlightedFileContent"></code></pre>
                    <textarea
                        :value="fileContent"
                        class="code-editor"
                        wrap="off"
                        spellcheck="false"
                        autocomplete="off"
                        autocorrect="off"
                        autocapitalize="off"
                        :readonly="savingFile || isStreamingFilePreview"
                        @input="$emit('update:fileContent', ($event.target as HTMLTextAreaElement).value)"
                        @scroll="handleCodeEditorScroll"
                    />
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>
      </a-tab-pane>
      <a-tab-pane key="database">
        <template #tab>
          <span class="workspace-tab-label">
            <DatabaseOutlined />
            <span class="workspace-tab-text">数据库服务</span>
          </span>
        </template>
        <DatabaseWorkspace
            :app="appInfo"
            :is-owner="isOwner"
            :is-generating="isGenerating"
            @enabled="$emit('databaseEnabled', $event)"
        />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import {
  CloudSyncOutlined,
  CloudUploadOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  EditOutlined,
  ExportOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  GlobalOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons-vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import DatabaseWorkspace from '@/components/app/DatabaseWorkspace.vue'
import InspiraFileTree from './InspiraFileTree.vue'
import ChatToolbarButton from './ChatToolbarButton.vue'
import type { FileTreeNode, WorkspaceTabKey } from './types'

const props = defineProps<{
  activeWorkspaceTab: WorkspaceTabKey
  appInfo?: API.AppVO
  canSaveFile: boolean
  deploying: boolean
  downloading: boolean
  editorStatusText: string
  expandedFileTreeKeys: Array<string | number>
  fileContent: string
  fileTreeData: FileTreeNode[]
  generationStageDescription: string
  generationStatusText: string
  generationStepIndex: number
  generationSteps: Array<{ key: string; label: string; index: number }>
  hasDeployed: boolean
  highlightedFileContent: string
  isEditMode: boolean
  isFileDirty: boolean
  isGenerating: boolean
  isOwner: boolean
  isStreamingFilePreview: boolean
  loadingFileContent: boolean
  loadingFiles: boolean
  previewRefreshKey: number
  previewUrl: string
  openFileTabs: Array<{ path: string; name: string }>
  selectedFileName: string
  selectedFilePath: string
  savingFile: boolean
  syncingDeploy: boolean
}>()

defineEmits<{
  deployApp: []
  databaseEnabled: [resource: API.AppDatabaseResourceVO]
  downloadCode: []
  handleFileSelect: [selectedKeys: Array<string | number>]
  handleFileTreeExpand: [expandedKeys: Array<string | number>]
  iframeLoad: []
  loadCodeFiles: []
  openDeployedSite: []
  openEditorTab: [filePath: string]
  openInNewTab: []
  saveCurrentFile: []
  showAppDetail: []
  syncDeployment: []
  toggleEditMode: []
  'update:activeWorkspaceTab': [value: WorkspaceTabKey]
  'update:fileContent': [value: string]
}>()

// iframe ref，用于直接设置 src（绕过 Vue Router）
const previewIframeRef = ref<HTMLIFrameElement | null>(null)
const fileWorkspaceRef = ref<HTMLElement | null>(null)
const lineNumberGutterRef = ref<HTMLElement | null>(null)
const codeHighlightRef = ref<HTMLElement | null>(null)
const fileSidebarWidth = ref(260)
const fileSidebarResizeMin = 220
const fileSidebarResizeMax = 520
let fileSidebarResizePointerId: number | null = null
let fileSidebarResizeHandle: HTMLElement | null = null

const fileWorkspaceStyle = computed(() => ({
  '--file-sidebar-width': `${fileSidebarWidth.value}px`,
}))

const editorLineNumbers = computed(() => {
  const lineCount = Math.max(props.fileContent.split('\n').length, 1)
  return Array.from({ length: lineCount }, (_, index) => index + 1)
})

const handleCodeEditorScroll = (event: Event) => {
  const target = event.target as HTMLTextAreaElement
  if (!lineNumberGutterRef.value) {
    return
  }
  lineNumberGutterRef.value.scrollTop = target.scrollTop
  if (codeHighlightRef.value) {
    codeHighlightRef.value.scrollTop = target.scrollTop
    codeHighlightRef.value.scrollLeft = target.scrollLeft
  }
}

const clampFileSidebarWidth = (width: number) => {
  return Math.min(fileSidebarResizeMax, Math.max(fileSidebarResizeMin, width))
}

const stopFileSidebarResize = () => {
  if (fileSidebarResizePointerId === null) {
    return
  }
  fileSidebarResizeHandle?.releasePointerCapture?.(fileSidebarResizePointerId)
  fileSidebarResizePointerId = null
  fileSidebarResizeHandle = null
  window.removeEventListener('pointermove', handleFileSidebarResizeMove)
  window.removeEventListener('pointerup', stopFileSidebarResize)
  window.removeEventListener('pointercancel', stopFileSidebarResize)
}

const handleFileSidebarResizeMove = (event: PointerEvent) => {
  if (fileSidebarResizePointerId === null || event.pointerId !== fileSidebarResizePointerId) {
    return
  }
  const workspaceRect = fileWorkspaceRef.value?.getBoundingClientRect()
  if (!workspaceRect) {
    return
  }
  fileSidebarWidth.value = clampFileSidebarWidth(event.clientX - workspaceRect.left)
}

const startFileSidebarResize = (event: PointerEvent) => {
  if (event.button !== 0) {
    return
  }
  event.preventDefault()
  fileSidebarResizePointerId = event.pointerId
  fileSidebarResizeHandle = event.currentTarget as HTMLElement | null
  fileSidebarResizeHandle?.setPointerCapture?.(event.pointerId)
  window.addEventListener('pointermove', handleFileSidebarResizeMove)
  window.addEventListener('pointerup', stopFileSidebarResize)
  window.addEventListener('pointercancel', stopFileSidebarResize)
  handleFileSidebarResizeMove(event)
}

const handleFileSidebarResizeKeydown = (event: KeyboardEvent) => {
  const step = event.shiftKey ? 48 : 24
  if (event.key === 'ArrowLeft') {
    fileSidebarWidth.value = clampFileSidebarWidth(fileSidebarWidth.value - step)
    event.preventDefault()
    return
  }
  if (event.key === 'ArrowRight') {
    fileSidebarWidth.value = clampFileSidebarWidth(fileSidebarWidth.value + step)
    event.preventDefault()
    return
  }
  if (event.key === 'Home') {
    fileSidebarWidth.value = fileSidebarResizeMin
    event.preventDefault()
    return
  }
  if (event.key === 'End') {
    fileSidebarWidth.value = fileSidebarResizeMax
    event.preventDefault()
  }
}

// 设置 iframe src 的函数
const updateIframeSrc = async () => {
  await nextTick()
  if (previewIframeRef.value && props.previewUrl) {
    previewIframeRef.value.src = props.previewUrl
  }
}

// 监听 previewUrl 变化
watch(() => props.previewUrl, updateIframeSrc)

// 监听 previewRefreshKey 变化，强制刷新 iframe
watch(() => props.previewRefreshKey, updateIframeSrc)

// 组件挂载后设置初始 src
onMounted(updateIframeSrc)
onUnmounted(stopFileSidebarResize)
</script>

<style scoped>
.preview-section {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: rgba(248, 250, 252, 0.9);
}

.preview-section.is-editing {
  box-shadow: inset 0 0 0 1px rgba(22, 119, 255, 0.18);
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
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

:deep(.workspace-tabs .ant-tabs-nav-wrap) {
  min-width: 0;
}

:deep(.workspace-tabs .ant-tabs-extra-content) {
  display: flex;
  align-items: center;
  margin-left: auto;
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
  gap: 0;
}

.workspace-tab-text {
  max-width: 0;
  margin-left: 0;
  overflow: hidden;
  opacity: 0;
  white-space: nowrap;
  transform: translateX(-4px);
  transition:
    max-width 0.24s ease,
    margin-left 0.24s ease,
    opacity 0.2s ease,
    transform 0.24s ease;
}

:deep(.workspace-tabs .ant-tabs-tab:hover .workspace-tab-text),
:deep(.workspace-tabs .ant-tabs-tab:focus-visible .workspace-tab-text) {
  max-width: 96px;
  margin-left: 8px;
  opacity: 1;
  transform: translateX(0);
}

.workspace-tabs-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.preview-content {
  flex: 1;
  position: relative;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.72) 0%, rgba(241, 245, 249, 0.56) 100%);
}

.preview-placeholder,
.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #64748b;
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

.preview-loading p {
  margin-top: 16px;
}

.preview-progress-overlay {
  position: absolute;
  top: 18px;
  left: 18px;
  right: 18px;
  z-index: 2;
  pointer-events: none;
}

.preview-progress-overlay.has-preview {
  right: auto;
  max-width: min(520px, calc(100% - 36px));
}

.preview-progress-panel {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.12);
  backdrop-filter: blur(16px);
}

.preview-progress-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.preview-progress-head strong {
  display: block;
  color: #0f172a;
  font-size: 14px;
  line-height: 1.4;
}

.preview-progress-head span {
  display: block;
  margin-top: 3px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.5;
}

.generation-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.generation-step {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(241, 245, 249, 0.92);
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  transition: background 0.2s ease, color 0.2s ease;
}

.generation-step.active {
  background: rgba(219, 234, 254, 0.96);
  color: #1d4ed8;
}

.generation-step.done {
  background: rgba(220, 252, 231, 0.94);
  color: #15803d;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.file-workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, var(--file-sidebar-width, 260px)) 8px minmax(0, 1fr);
  background: #f8fafc;
}

.file-sidebar {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  width: 100%;
  border-right: 1px solid rgba(148, 163, 184, 0.16);
  background: rgba(255, 255, 255, 0.78);
}

.file-sidebar-resizer {
  position: relative;
  display: flex;
  align-items: stretch;
  justify-content: center;
  background: transparent;
  cursor: col-resize;
  touch-action: none;
}

.file-sidebar-resizer::before {
  content: '';
  width: 1px;
  margin: 0 auto;
  background: rgba(148, 163, 184, 0.2);
}

.file-sidebar-resizer:hover::before,
.file-sidebar-resizer:focus-visible::before,
.file-sidebar-resizer:active::before {
  background: rgba(37, 99, 235, 0.48);
}

.file-sidebar-resizer:focus-visible {
  outline: none;
}

.file-sidebar-resizer-handle {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 16px;
  transform: translateX(-50%);
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
  scrollbar-gutter: stable both-edges;
}

.file-editor-panel {
  min-width: 0;
  min-height: 0;
  display: flex;
  background: #fff;
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

.editor-tabbar {
  min-height: 36px;
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(248, 250, 252, 0.96);
}

.editor-tabs {
  min-width: 0;
  display: flex;
  overflow: hidden;
}

.editor-tab {
  min-width: 0;
  max-width: 180px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid rgba(148, 163, 184, 0.16);
  color: #64748b;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.editor-tab.active {
  background: #ffffff;
  color: #0f172a;
  box-shadow: inset 0 -2px 0 #1677ff;
}

.editor-tab:hover {
  background: rgba(241, 245, 249, 0.9);
}

.editor-tab.active:hover {
  background: #ffffff;
}

.editor-tab-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-tab-dirty {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #64748b;
}

.editor-status {
  flex: none;
  display: inline-flex;
  align-items: center;
  padding-right: 14px;
  color: #64748b;
  font-size: 12px;
}

.editor-status.streaming {
  color: #1677ff;
}

.editor-loading {
  flex: 1;
}

.code-editor-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #ffffff;
  --code-editor-font: Consolas, "SFMono-Regular", "Liberation Mono", monospace;
  --code-editor-line-height: 22px;
  --code-editor-font-size: 13px;
}

.code-pathbar {
  min-height: 28px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
  color: #64748b;
  background: #ffffff;
  font-size: 12px;
}

.code-editor-frame {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  overflow: hidden;
  background: #ffffff;
}

.code-line-numbers {
  min-height: 0;
  overflow: hidden;
  padding: 12px 5px 12px 0;
  border-right: 1px solid rgba(148, 163, 184, 0.24);
  background: #f8fafc;
  color: #94a3b8;
  font-family: var(--code-editor-font);
  font-size: var(--code-editor-font-size);
  line-height: var(--code-editor-line-height);
  text-align: right;
  user-select: none;
}

.code-line-numbers span {
  display: block;
  height: var(--code-editor-line-height);
}

.code-editor-stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #ffffff;
}

.code-highlight-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  margin: 0;
  padding: 12px 16px;
  overflow: hidden;
  background: #ffffff;
  color: #0f172a;
  font-family: var(--code-editor-font);
  font-size: var(--code-editor-font-size);
  line-height: var(--code-editor-line-height);
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  word-wrap: normal;
  tab-size: 2;
  box-sizing: border-box;
}

.code-highlight-layer code {
  display: block;
  min-height: 100%;
  padding: 0;
  overflow: visible;
  font: inherit;
  background: transparent;
}

.code-highlight-layer :deep(*) {
  font: inherit !important;
  letter-spacing: inherit !important;
}

.code-editor {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  resize: none;
  border: 0;
  border-radius: 0;
  padding: 12px 16px;
  overflow: auto;
  background: transparent;
  color: transparent;
  caret-color: #0f172a;
  font-family: var(--code-editor-font) !important;
  font-size: var(--code-editor-font-size);
  line-height: var(--code-editor-line-height);
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  word-wrap: normal;
  box-sizing: border-box;
  outline: none;
}

.code-editor:focus {
  box-shadow: none;
}

.code-editor::selection {
  background: rgba(59, 130, 246, 0.35);
}

.code-editor:read-only {
  opacity: 1;
}

@media (max-width: 1024px) {
  .main-content {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(280px, 0.95fr) minmax(320px, 1.05fr);
  }

  .chat-section {
    border-right: 0;
    border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  }
}

@media (max-width: 768px) {
  :deep(.workspace-tabs .ant-tabs-nav) {
    padding: 0 16px;
  }

  :deep(.workspace-tabs .ant-tabs-nav) {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
    padding-top: 12px;
    padding-bottom: 12px;
  }

  :deep(.workspace-tabs .ant-tabs-extra-content) {
    margin-left: 0;
  }

  .workspace-tabs-actions {
    justify-content: flex-start;
  }

  .workspace-tab-text {
    max-width: 120px;
    margin-left: 8px;
    opacity: 1;
    transform: translateX(0);
  }

  .file-workspace {
    grid-template-columns: 1fr;
    grid-template-rows: 220px minmax(0, 1fr);
  }

  .file-sidebar {
    border-right: 0;
    border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  }
}
</style>
