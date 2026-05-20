<template>
  <div class="preview-header">
    <div class="preview-title">
      <div class="browser-dots">
        <span></span>
        <span></span>
        <span></span>
      </div>
      <h3>{{ workspaceTitle }}</h3>
    </div>
    <div class="preview-actions">
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
  </div>
</template>

<script setup lang="ts">
import {
  CloudSyncOutlined,
  CloudUploadOutlined,
  DownloadOutlined,
  EditOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons-vue'
import ChatToolbarButton from './ChatToolbarButton.vue'
import type { WorkspaceTabKey } from './types'

defineProps<{
  activeWorkspaceTab: WorkspaceTabKey
  canSaveFile: boolean
  deploying: boolean
  downloading: boolean
  hasDeployed: boolean
  isEditMode: boolean
  isGenerating: boolean
  isOwner: boolean
  loadingFiles: boolean
  previewUrl: string
  syncingDeploy: boolean
  workspaceTitle: string
}>()

defineEmits<{
  deployApp: []
  downloadCode: []
  loadCodeFiles: []
  openDeployedSite: []
  openInNewTab: []
  saveCurrentFile: []
  showAppDetail: []
  syncDeployment: []
  toggleEditMode: []
}>()
</script>

<style scoped>
.preview-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(248, 250, 252, 0.72);
}

.preview-title {
  display: flex;
  align-items: flex-start;
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
  justify-content: flex-end;
}

.edit-mode-active {
  color: #1677ff;
}

@media (max-width: 768px) {
  .preview-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding-left: 16px;
    padding-right: 16px;
  }

  .preview-actions {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
