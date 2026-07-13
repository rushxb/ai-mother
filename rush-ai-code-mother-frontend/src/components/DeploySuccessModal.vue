<template>
  <AppModalShell
    v-model:open="visible"
    eyebrow="Deployment complete"
    title="应用已成功发布"
    description="线上访问地址已经就绪，你可以立即打开站点或复制链接进行分享。"
    :width="620"
    tone="success"
  >
    <template #icon>
      <CheckOutlined />
    </template>

    <div class="deploy-success">
      <div class="deployment-status">
        <div class="deployment-status__signal" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <div>
          <strong>Production</strong>
          <span>站点运行中</span>
        </div>
        <span class="deployment-status__badge">Live</span>
      </div>

      <div class="deploy-url">
        <label for="deployment-url">访问地址</label>
        <div class="deploy-url__field">
          <GlobalOutlined aria-hidden="true" />
          <input id="deployment-url" :value="deployUrl" readonly @focus="selectDeploymentUrl" />
          <button
            type="button"
            class="copy-button"
            :class="{ copied }"
            :aria-label="copied ? '链接已复制' : '复制部署链接'"
            @click="handleCopyUrl"
          >
            <CheckOutlined v-if="copied" />
            <CopyOutlined v-else />
            <span>{{ copied ? '已复制' : '复制' }}</span>
          </button>
        </div>
      </div>

      <p class="deployment-note">
        <SafetyCertificateOutlined />
        外部访问将通过独立窗口打开，并自动启用安全的来源隔离策略。
      </p>
    </div>

    <template #footer>
      <a-button class="secondary-action" @click="handleClose">稍后访问</a-button>
      <a-button type="primary" class="visit-action" :disabled="!deployUrl" @click="handleOpenSite">
        <template #icon>
          <ExportOutlined />
        </template>
        访问线上站点
      </a-button>
    </template>
  </AppModalShell>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  CheckOutlined,
  CopyOutlined,
  ExportOutlined,
  GlobalOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons-vue'
import AppModalShell from '@/components/common/AppModalShell.vue'
import { copyTextToClipboard } from '@/utils/clipboard'

interface Props {
  open: boolean
  deployUrl: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  'open-site': []
}>()

const copied = ref(false)
let copiedResetTimer: ReturnType<typeof setTimeout> | undefined

const visible = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})

const handleCopyUrl = async () => {
  if (!props.deployUrl) {
    message.warning('暂无可复制的部署地址')
    return
  }

  const copySucceeded = await copyTextToClipboard(props.deployUrl)
  if (!copySucceeded) {
    message.error('复制失败，请手动选择链接')
    return
  }

  copied.value = true
  message.success('部署链接已复制')
  window.clearTimeout(copiedResetTimer)
  copiedResetTimer = window.setTimeout(() => {
    copied.value = false
  }, 2200)
}

const handleOpenSite = () => emit('open-site')
const selectDeploymentUrl = (event: FocusEvent) => {
  if (event.currentTarget instanceof HTMLInputElement) {
    event.currentTarget.select()
  }
}
const handleClose = () => {
  visible.value = false
}

onBeforeUnmount(() => {
  window.clearTimeout(copiedResetTimer)
})
</script>

<style scoped>
.deploy-success {
  display: grid;
  gap: 20px;
}

.deployment-status {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 15px 16px;
  border: 1px solid rgba(15, 159, 143, 0.16);
  border-radius: var(--radius-md, 16px);
  background:
    linear-gradient(135deg, rgba(15, 159, 143, 0.08), rgba(255, 255, 255, 0.62)),
    rgba(246, 249, 253, 0.72);
}

.deployment-status__signal {
  position: relative;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
}

.deployment-status__signal span {
  position: absolute;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(15, 159, 143, 0.45);
  border-radius: 50%;
  animation: deploymentSignal 2.4s var(--ease-out, ease) infinite;
}

.deployment-status__signal span:nth-child(2) {
  animation-delay: 0.75s;
}

.deployment-status__signal span:nth-child(3) {
  width: 9px;
  height: 9px;
  border: 0;
  background: #0f9f8f;
  animation: none;
  box-shadow: 0 0 0 5px rgba(15, 159, 143, 0.1);
}

.deployment-status > div:nth-child(2) {
  display: grid;
  gap: 2px;
}

.deployment-status strong {
  color: var(--color-ink-strong, #102033);
  font-size: 14px;
}

.deployment-status > div:nth-child(2) span {
  color: var(--color-ink-soft, #6f8198);
  font-size: 12px;
}

.deployment-status__badge {
  padding: 5px 9px;
  border-radius: 999px;
  background: rgba(15, 159, 143, 0.1);
  color: #0b7d71;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.deploy-url {
  display: grid;
  gap: 8px;
}

.deploy-url label {
  color: var(--color-ink, #2f4158);
  font-size: 12px;
  font-weight: 700;
}

.deploy-url__field {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  padding: 6px 7px 6px 14px;
  border: 1px solid var(--color-line, rgba(112, 140, 175, 0.18));
  border-radius: 14px;
  background: rgba(246, 249, 253, 0.82);
  color: var(--color-primary, #2f8bff);
  transition:
    border-color 0.2s var(--ease-out, ease),
    box-shadow 0.2s var(--ease-out, ease);
}

.deploy-url__field:focus-within {
  border-color: rgba(47, 139, 255, 0.42);
  box-shadow: 0 0 0 4px rgba(47, 139, 255, 0.08);
}

.deploy-url__field input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-ink-strong, #102033);
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace !important;
  font-size: 12px;
}

.copy-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 78px;
  height: 38px;
  border: 1px solid rgba(47, 139, 255, 0.16);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.88);
  color: var(--color-primary-strong, #176fdd);
  font-size: 12px;
  font-weight: 750;
  cursor: pointer;
  transition:
    transform 0.2s var(--ease-out, ease),
    background 0.2s var(--ease-out, ease);
}

.copy-button:hover {
  transform: translateY(-1px);
  background: #fff;
}

.copy-button.copied {
  border-color: rgba(15, 159, 143, 0.2);
  color: #0b7d71;
}

.deployment-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  color: var(--color-ink-soft, #6f8198);
  font-size: 12px;
  line-height: 1.6;
}

.deployment-note :deep(.anticon) {
  margin-top: 3px;
  color: #0f9f8f;
}

:deep(.secondary-action.ant-btn),
:deep(.visit-action.ant-btn) {
  height: 40px;
  border-radius: 11px;
  font-weight: 700;
}

:deep(.visit-action.ant-btn) {
  border-color: #0f9f8f;
  background: #0f9f8f;
  box-shadow: 0 10px 22px rgba(15, 159, 143, 0.2);
}

:deep(.visit-action.ant-btn:hover) {
  border-color: #0b887b;
  background: #0b887b;
}

@keyframes deploymentSignal {
  0% {
    opacity: 0.7;
    transform: scale(0.6);
  }

  100% {
    opacity: 0;
    transform: scale(2.4);
  }
}

@media (max-width: 560px) {
  .deploy-url__field {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .copy-button {
    grid-column: 1 / -1;
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .deployment-status__signal span {
    animation: none;
  }

  .copy-button:hover {
    transform: none;
  }
}
</style>
