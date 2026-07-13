<template>
  <div class="app-card" :class="{ 'app-card--featured': featured }">
    <div class="app-preview">
      <img
        :key="appCover"
        :src="appCover"
        :alt="app.appName || '应用封面'"
        loading="lazy"
        decoding="async"
        @error="handleCoverError"
      />
      <div v-if="statusMeta" class="generation-badge" :class="`generation-badge--${statusMeta.variant}`">
        <span class="generation-badge__dot"></span>
        <span>{{ statusMeta.label }}</span>
      </div>
      <div class="app-overlay">
        <a-space>
          <a-button
            v-if="canViewChat"
            class="overlay-button primary"
            type="primary"
            @click="handleViewChat"
          >
            {{ chatButtonText }}
          </a-button>
          <a-button
            v-else
            class="overlay-button primary"
            type="primary"
            :loading="copying"
            @click="handleCopy"
          >
            复制
          </a-button>
          <a-button
            v-if="showWorkButton || app.deployKey"
            class="overlay-button"
            type="default"
            :disabled="!app.deployKey"
            @click="handleViewWork"
          >
            {{ workButtonText }}
          </a-button>
        </a-space>
      </div>
    </div>
    <div class="app-info">
      <div class="app-info-left">
        <a-avatar :src="userAvatar" :size="40">
          {{ app.user?.userName?.charAt(0) || 'U' }}
        </a-avatar>
      </div>
      <div class="app-info-right">
        <h3 class="app-title">{{ app.appName || '未命名应用' }}</h3>
        <p class="app-author">
          {{ app.user?.userName || (featured ? '官方' : '未知用户') }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DEFAULT_APP_COVER, DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import { normalizeImageUrl } from '@/utils/url'

interface Props {
  app: API.AppVO
  featured?: boolean
  canViewChat?: boolean
  chatButtonText?: string
  showWorkButton?: boolean
  workButtonText?: string
  copying?: boolean
}

interface Emits {
  (e: 'view-chat', appId: string | number | undefined): void
  (e: 'view-work', app: API.AppVO): void
  (e: 'copy', app: API.AppVO): void
}

const props = withDefaults(defineProps<Props>(), {
  featured: false,
  canViewChat: true,
  chatButtonText: '查看对话',
  showWorkButton: false,
  workButtonText: '预览',
  copying: false,
})

const emit = defineEmits<Emits>()

const appCover = computed(() => normalizeImageUrl(props.app.cover) || DEFAULT_APP_COVER)
const userAvatar = computed(() => props.app.user?.userAvatar || DEFAULT_USER_AVATAR)
const statusMeta = computed(() => {
  if (!props.app.isGenerating) {
    return null
  }
  if (props.app.generatingStage === 'build') {
    return {
      label: '构建中',
      variant: 'build',
    }
  }
  if (props.app.generatingStage === 'repair') {
    return {
      label: '修复中',
      variant: 'repair',
    }
  }
  if (props.app.generatingStage === 'agent') {
    return {
      label: '编排中',
      variant: 'agent',
    }
  }
  if (props.app.generatingStage === 'update') {
    return {
      label: '改修中',
      variant: 'update',
    }
  }
  return {
    label: '创建中',
    variant: 'create',
  }
})

const handleViewChat = () => {
  emit('view-chat', props.app.id)
}

const handleViewWork = () => {
  emit('view-work', props.app)
}

const handleCopy = () => {
  emit('copy', props.app)
}

const handleCoverError = (event: Event) => {
  const image = event.currentTarget
  if (!(image instanceof HTMLImageElement) || image.dataset.fallbackApplied === 'true') {
    return
  }
  image.dataset.fallbackApplied = 'true'
  image.src = DEFAULT_APP_COVER
}
</script>

<style scoped>
.app-card {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 251, 255, 0.9)),
    rgba(255, 255, 255, 0.92);
  border-radius: 24px;
  overflow: hidden;
  box-shadow:
    0 18px 44px rgba(114, 137, 170, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(104, 132, 175, 0.14);
  transition:
    transform 0.28s ease,
    box-shadow 0.28s ease,
    border-color 0.28s ease;
  cursor: pointer;
}

.app-card:hover {
  transform: translateY(-6px);
  border-color: rgba(47, 128, 255, 0.24);
  box-shadow:
    0 28px 58px rgba(114, 137, 170, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.app-preview {
  height: 180px;
  background:
    radial-gradient(circle at 30% 20%, rgba(47, 128, 255, 0.18), transparent 34%),
    radial-gradient(circle at 80% 70%, rgba(44, 192, 210, 0.16), transparent 28%),
    linear-gradient(135deg, #f7fbff 0%, #edf4fb 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  position: relative;
}

.generation-badge {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.44);
  background: rgba(246, 249, 253, 0.76);
  backdrop-filter: blur(16px);
  color: #42576f;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  box-shadow:
    0 10px 24px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.64);
}

.generation-badge__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.78;
  animation: generationPulse 1.9s ease-in-out infinite;
}

.generation-badge--create {
  color: #53687f;
  background: rgba(248, 250, 252, 0.8);
}

.generation-badge--update {
  color: #3f627b;
  background: rgba(241, 246, 250, 0.82);
}

.generation-badge--build {
  color: #1d4ed8;
  background: rgba(239, 246, 255, 0.9);
}

.generation-badge--repair {
  color: #b45309;
  background: rgba(255, 251, 235, 0.92);
}

.generation-badge--agent {
  color: #4f46e5;
  background: rgba(238, 242, 255, 0.92);
}

.app-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.42s ease;
}

.app-card:hover .app-preview img {
  transform: scale(1.04);
}

.app-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(13, 27, 42, 0.46);
  backdrop-filter: blur(10px);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.28s ease;
}

.app-card:hover .app-overlay {
  opacity: 1;
}

:deep(.overlay-button.ant-btn) {
  border-radius: 999px;
  border-color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.92);
  color: #0d1b2a;
}

:deep(.overlay-button.ant-btn:hover),
:deep(.overlay-button.ant-btn:focus) {
  border-color: rgba(255, 255, 255, 0.9);
  background: #ffffff;
  color: #0d1b2a;
}

:deep(.overlay-button.primary.ant-btn) {
  border: none;
  color: #ffffff;
  background: linear-gradient(135deg, #1f6fff 0%, #4dbbff 100%);
}

:deep(.overlay-button.primary.ant-btn:hover),
:deep(.overlay-button.primary.ant-btn:focus) {
  color: #ffffff;
  background: linear-gradient(135deg, #2b79ff 0%, #62c4ff 100%);
}

.app-info {
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-info-left {
  flex-shrink: 0;
}

.app-info-right {
  flex: 1;
  min-width: 0;
}

.app-title {
  font-size: 16px;
  font-weight: 700;
  margin: 0 0 4px;
  color: #0d1b2a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-author {
  font-size: 14px;
  color: #73859c;
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@keyframes generationPulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.62;
  }
  50% {
    transform: scale(1.15);
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-card:hover,
  .app-card:hover .app-preview img {
    transform: none;
  }

  .generation-badge__dot {
    animation: none;
  }
}

</style>
