<template>
<div id="appChatPage" :class="{ 'is-resizing': isChatPaneResizing }">
    <div ref="mainContentRef" class="main-content" :style="chatLayoutStyle">
      <div class="chat-section">
        <ChatPageHeader
            ref="chatPageHeaderRef"
            :app-display-name="appDisplayName"
            :app-name-draft="appNameDraft"
            :can-edit-app-name="canEditAppName"
            :code-gen-type-text="appInfo?.codeGenType ? formatCodeGenType(appInfo.codeGenType) : undefined"
            :generation-status-text="generationStatusText"
            :is-editing-app-name="isEditingAppName"
            :is-generating="isGenerating"
            :latest-generation-failed="latestGenerationFailed"
            :saving-app-name="savingAppName"
            @back="goBack"
            @cancel-app-name-edit="cancelAppNameEdit"
            @save-app-name="saveAppName"
            @start-edit-app-name="startEditAppName"
            @update:app-name-draft="updateAppNameDraft"
        />

        <ChatMessagesPanel
            ref="messagesPanelRef"
            :ai-avatar="aiAvatar"
            :format-duration="formatDuration"
            :get-ai-message-segments="getAiMessageSegments"
            :get-tool-call-file-icon="getToolCallFileIcon"
            :has-more-history="hasMoreHistory"
            :is-owner="isOwner"
            :is-tool-call-file-active="isToolCallFileActive"
            :loading-history="loadingHistory"
            :login-user-avatar="loginUserAvatar"
            :messages="messages"
            :show-scroll-to-bottom="showScrollToBottom"
            @load-more-history="loadMoreHistory"
            @open-tool-call-file="openToolCallFile"
            @retry-last-generation="retryLastGeneration"
            @scroll="handleMessagesScroll"
            @scroll-to-bottom="scrollMessagesToBottom"
        />

        <SelectedElementAlert
            :selected-element-info="selectedElementInfo"
            @close="clearSelectedElement"
        />

        <ChatInputPanel
            v-model="userInput"
            :can-optimize-prompt="canOptimizePrompt"
            :is-generating="isGenerating"
            :is-optimizing-prompt="isOptimizingPrompt"
            :is-owner="isOwner"
            :placeholder="getInputPlaceholder()"
            :stopping-generation="stoppingGeneration"
            @optimize="optimizeUserPrompt"
            @send="sendMessage()"
            @send-button-click="handleSendButtonClick"
        />
      </div>

      <div
          ref="chatSplitterRef"
          class="chat-splitter"
          role="separator"
          tabindex="0"
          aria-orientation="vertical"
          aria-label="拖拽调整左右面板宽度"
          :aria-valuemin="CHAT_PANE_MIN_WIDTH"
          :aria-valuemax="chatPaneMaxWidth"
          :aria-valuenow="chatPaneCurrentWidth"
          :aria-valuetext="`${Math.round(chatPaneWidthRatio * 100)}%`"
          title="拖拽调整左右面板宽度"
          @keydown="handleChatPaneResizeKeydown"
          @pointerdown="startChatPaneResize"
      />

      <AppWorkspacePanel
          class="workspace-section"
          v-model:activeWorkspaceTab="activeWorkspaceTab"
          v-model:fileContent="fileContent"
          :app-info="appInfo"
          :can-save-file="canSaveFile"
          :deploying="deploying"
          :downloading="downloading"
          :editor-status-text="editorStatusText"
          :expanded-file-tree-keys="expandedFileTreeKeys"
          :file-tree-data="fileTreeData"
          :generation-stage-description="generationStageDescription"
          :generation-status-text="generationStatusText"
          :generation-step-index="generationStepIndex"
          :generation-steps="generationSteps"
          :has-deployed="hasDeployed"
          :highlighted-file-content="highlightedFileContent"
          :is-edit-mode="isEditMode"
          :is-file-dirty="Boolean(isFileDirty)"
          :is-generating="isGenerating"
          :is-owner="isOwner"
          :is-streaming-file-preview="isStreamingFilePreview"
          :loading-file-content="loadingFileContent"
          :loading-files="loadingFiles"
          :preview-refresh-key="previewRefreshKey"
          :preview-url="previewUrl"
          :selected-file-name="selectedFileName"
          :selected-file-path="selectedFilePath"
          :saving-file="savingFile"
          :syncing-deploy="syncingDeploy"
          @deploy-app="deployApp"
          @database-enabled="handleDatabaseEnabled"
          @download-code="downloadCode"
          @handle-file-select="handleFileSelect"
          @handle-file-tree-expand="handleFileTreeExpand"
          @iframe-load="onIframeLoad"
          @load-code-files="loadCodeFiles"
          @open-deployed-site="openDeployedSite"
          @open-in-new-tab="openInNewTab"
          @save-current-file="saveCurrentFile"
          @show-app-detail="showAppDetail"
          @sync-code-editor-scroll="syncCodeEditorScroll"
          @sync-deployment="syncDeployment"
          @toggle-edit-mode="toggleEditMode"
      />
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

<script lang="ts" src="./AppChatPageImpl.setup.ts"></script>

<style scoped src="./AppChatPageImpl.css"></style>
