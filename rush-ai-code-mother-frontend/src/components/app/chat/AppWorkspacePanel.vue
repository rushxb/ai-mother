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
                <span class="editor-status" :class="{ streaming: isStreamingFilePreview }">
                  {{ editorStatusText }}
                </span>
              </div>
              <div v-if="loadingFileContent" class="editor-loading">
                <a-spin />
                <span>正在读取文件...</span>
              </div>
              <div v-else class="code-editor-shell">
                <pre class="code-highlight-layer" aria-hidden="true"><code v-html="highlightedFileContent"></code></pre>
                <textarea
                    :value="fileContent"
                    class="code-editor"
                    wrap="off"
                    spellcheck="false"
                    autocomplete="off"
                    autocorrect="off"
                    autocapitalize="off"
                    :disabled="savingFile || isStreamingFilePreview"
                    @input="$emit('update:fileContent', ($event.target as HTMLTextAreaElement).value)"
                    @scroll="$emit('syncCodeEditorScroll')"
                />
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
import { ref, watch, nextTick, onMounted } from 'vue'
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
  openInNewTab: []
  saveCurrentFile: []
  showAppDetail: []
  syncCodeEditorScroll: []
  syncDeployment: []
  toggleEditMode: []
  'update:activeWorkspaceTab': [value: WorkspaceTabKey]
  'update:fileContent': [value: string]
}>()

// iframe ref，用于直接设置 src（绕过 Vue Router）
const previewIframeRef = ref<HTMLIFrameElement | null>(null)

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

.editor-status.streaming {
  color: #1677ff;
}

.editor-loading {
  flex: 1;
}

.code-editor-shell {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  --code-editor-font: Consolas, "SFMono-Regular", "Liberation Mono", monospace;
}

.code-highlight-layer {
  position: absolute;
  inset: 0;
  z-index: 0;
  margin: 0;
  padding: 16px;
  overflow: auto;
  pointer-events: none;
  background: #fff;
  color: #0f172a;
  font-family: var(--code-editor-font);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  word-wrap: normal;
  tab-size: 2;
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
  resize: none;
  border: 0;
  border-radius: 0;
  padding: 16px;
  overflow: auto;
  background: transparent;
  color: transparent;
  caret-color: #0f172a;
  font-family: var(--code-editor-font) !important;
  font-size: 13px;
  line-height: 1.7;
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
