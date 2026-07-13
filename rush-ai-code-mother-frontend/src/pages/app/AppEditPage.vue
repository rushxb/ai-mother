<template>
  <div id="appEditPage">
    <AmbientTechCanvas
      class="edit-tech-canvas"
      :density="52"
      accent="#2f8bff"
      secondary="#46cbbc"
    />
    <div class="page-grid" aria-hidden="true"></div>
    <div class="page-glow page-glow-primary" aria-hidden="true"></div>
    <div class="page-glow page-glow-secondary" aria-hidden="true"></div>

    <main class="edit-shell">
      <Motion
        as="header"
        class="workspace-hero"
        :initial="{ opacity: 0, y: 18 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.56, ease: [0.22, 1, 0.36, 1] }"
      >
        <button type="button" class="back-button" aria-label="返回首页" @click="goHome">
          <ArrowLeftOutlined />
        </button>

        <div class="hero-copy">
          <div class="hero-kicker">
            <span class="live-dot"></span>
            APPLICATION CONTROL
          </div>
          <h1>{{ appInfo?.appName || '未命名应用' }}</h1>
          <p>管理应用身份、生成上下文与展示策略，让每次迭代都保持清晰可控。</p>
        </div>

        <div class="hero-actions">
          <span class="status-chip" :class="appInfo?.deployKey ? 'is-online' : 'is-draft'">
            {{ appInfo?.deployKey ? '已部署' : '草稿' }}
          </span>
          <span v-if="isDirty" class="status-chip is-unsaved">有未保存更改</span>
          <a-button class="hero-action-button" :disabled="!appInfo" @click="goToChat">
            <template #icon><MessageOutlined /></template>
            进入对话
          </a-button>
          <a-button
            class="hero-action-button hero-action-primary"
            :disabled="!appInfo?.deployKey"
            @click="openPreview"
          >
            <template #icon><EyeOutlined /></template>
            线上预览
          </a-button>
        </div>
      </Motion>

      <a-alert v-if="loadError" type="error" show-icon :message="loadError" class="load-error">
        <template #action>
          <a-button size="small" @click="retryLoad">重新加载</a-button>
        </template>
      </a-alert>

      <div class="edit-layout">
        <Motion
          as="section"
          class="edit-panel"
          :initial="{ opacity: 0, y: 22 }"
          :animate="{ opacity: 1, y: 0 }"
          :transition="{ duration: 0.58, delay: 0.08, ease: [0.22, 1, 0.36, 1] }"
        >
          <div class="panel-heading">
            <div class="panel-icon"><SafetyCertificateOutlined /></div>
            <div>
              <span class="panel-kicker">IDENTITY & SETTINGS</span>
              <h2>应用控制台</h2>
              <p>在一个工作台中维护应用的核心资料与生成配置。</p>
            </div>
          </div>

          <div v-if="loading && !appInfo" class="panel-loading">
            <a-skeleton active :paragraph="{ rows: 9 }" />
          </div>

          <a-form
            v-else-if="appInfo"
            ref="formRef"
            class="application-form"
            :model="formData"
            :rules="rules"
            layout="vertical"
            @finish="handleSubmit"
          >
            <div class="form-section">
              <div class="form-section-heading">
                <span>01</span>
                <div>
                  <h3>应用身份</h3>
                  <p>为工作台与公开页面设置清晰、易识别的名称。</p>
                </div>
              </div>
              <a-form-item label="应用名称" name="appName">
                <a-input
                  v-model:value="formData.appName"
                  placeholder="请输入应用名称"
                  :maxlength="50"
                  show-count
                  size="large"
                />
              </a-form-item>
            </div>

            <div v-if="isAdmin" class="form-section">
              <div class="form-section-heading">
                <span>02</span>
                <div>
                  <h3>展示策略</h3>
                  <p>配置应用封面与平台推荐优先级。</p>
                </div>
              </div>
              <div class="admin-field-grid">
                <a-form-item
                  label="应用封面"
                  name="cover"
                  extra="支持 HTTP(S) 图片链接，建议使用 4:3 比例"
                >
                  <a-input v-model:value="formData.cover" placeholder="https://..." size="large" />
                </a-form-item>
                <a-form-item label="推荐优先级" name="priority" extra="99 表示精选应用">
                  <a-input-number
                    v-model:value="formData.priority"
                    :min="0"
                    :max="99"
                    size="large"
                    class="priority-input"
                  />
                </a-form-item>
              </div>
            </div>

            <div class="form-section">
              <div class="form-section-heading">
                <span>{{ isAdmin ? '03' : '02' }}</span>
                <div>
                  <h3>生成上下文</h3>
                  <p>以下信息由创建流程生成，用于保持后续对话与代码生成的一致性。</p>
                </div>
              </div>
              <a-form-item label="初始提示词" name="initPrompt">
                <a-textarea
                  v-model:value="formData.initPrompt"
                  placeholder="暂无初始提示词"
                  :rows="5"
                  :maxlength="1000"
                  show-count
                  disabled
                />
              </a-form-item>
              <div class="readonly-grid">
                <a-form-item label="生成模式" name="codeGenType">
                  <a-input :value="formatCodeGenType(formData.codeGenType)" disabled size="large" />
                </a-form-item>
                <a-form-item v-if="formData.deployKey" label="部署标识" name="deployKey">
                  <a-input v-model:value="formData.deployKey" disabled size="large" />
                </a-form-item>
              </div>
            </div>

            <div class="form-actions">
              <div class="save-state">
                <span class="save-state-dot" :class="{ dirty: isDirty }"></span>
                {{ isDirty ? '有更改待保存' : '所有更改已保存' }}
              </div>
              <div class="action-buttons">
                <a-button size="large" :disabled="loading || !isDirty" @click="resetForm">
                  <template #icon><ReloadOutlined /></template>
                  重置
                </a-button>
                <a-button
                  type="primary"
                  html-type="submit"
                  size="large"
                  :loading="submitting"
                  :disabled="loading || !appInfo || !isDirty"
                >
                  <template #icon><SaveOutlined /></template>
                  保存更改
                </a-button>
              </div>
            </div>
          </a-form>

          <div v-else class="empty-state">
            <CodeOutlined />
            <h3>暂时无法读取应用</h3>
            <p>请检查网络连接或确认应用仍然存在。</p>
            <a-button type="primary" @click="retryLoad">重新加载</a-button>
          </div>
        </Motion>

        <Motion
          v-if="appInfo"
          as="aside"
          class="context-column"
          :initial="{ opacity: 0, x: 22 }"
          :animate="{ opacity: 1, x: 0 }"
          :transition="{ duration: 0.58, delay: 0.14, ease: [0.22, 1, 0.36, 1] }"
        >
          <section class="preview-card">
            <div class="preview-toolbar">
              <span>LIVE IDENTITY</span>
              <span class="preview-status"
                ><i></i>{{ appInfo.deployKey ? 'ONLINE' : 'DRAFT' }}</span
              >
            </div>
            <div class="cover-stage">
              <a-image
                v-if="normalizedCoverPreview"
                :src="normalizedCoverPreview"
                :preview="false"
                class="cover-image"
                fallback="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
              />
              <div v-else class="cover-placeholder" aria-hidden="true">
                <span class="cover-orbit orbit-one"></span>
                <span class="cover-orbit orbit-two"></span>
                <span class="cover-core">{ }</span>
              </div>
            </div>
            <div class="preview-copy">
              <span>{{ formatCodeGenType(appInfo.codeGenType) }}</span>
              <h2>{{ appInfo.appName || '未命名应用' }}</h2>
              <p>{{ appInfo.initPrompt || '暂无生成需求描述' }}</p>
            </div>
            <button type="button" class="preview-link" @click="goToChat">
              进入应用工作台
              <ArrowRightOutlined />
            </button>
          </section>

          <section class="metadata-card">
            <div class="side-heading">
              <span class="panel-kicker">APPLICATION META</span>
              <h2>应用元数据</h2>
            </div>
            <dl class="metadata-list">
              <div>
                <dt>应用 ID</dt>
                <dd>#{{ appInfo.id }}</dd>
              </div>
              <div>
                <dt>创建者</dt>
                <dd><UserInfo :user="appInfo.user" size="small" /></dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd>{{ formatTime(appInfo.createTime) }}</dd>
              </div>
              <div>
                <dt>更新时间</dt>
                <dd>{{ formatTime(appInfo.updateTime) }}</dd>
              </div>
              <div>
                <dt>部署时间</dt>
                <dd :class="appInfo.deployKey ? 'meta-online' : 'meta-muted'">
                  {{
                    appInfo.deployKey ? formatTime(appInfo.deployedTime) || '已部署' : '尚未部署'
                  }}
                </dd>
              </div>
            </dl>
          </section>

          <section class="guidance-card">
            <span class="guidance-index">NOTE / 01</span>
            <h2>保持上下文稳定</h2>
            <p>
              应用名称和封面可随时调整；初始提示词与生成模式用于维持生成上下文，建议通过对话继续迭代需求。
            </p>
          </section>
        </Motion>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { message, type FormInstance } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { getAppVoById, updateApp, updateAppByAdmin } from '@/api/appController'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { formatCodeGenType } from '@/utils/codeGenTypes'
import { formatTime } from '@/utils/time'
import UserInfo from '@/components/UserInfo.vue'
import { getStaticPreviewUrl } from '@/config/env'
import { normalizeImageUrl } from '@/utils/url'
import { openInNewTab } from '@/utils/browser'
import { Motion } from 'motion-v'
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  CodeOutlined,
  EyeOutlined,
  MessageOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
} from '@ant-design/icons-vue'
import AmbientTechCanvas from '@/components/visual/AmbientTechCanvas.vue'

interface EditableAppForm {
  appName: string
  cover: string
  priority: number
  initPrompt: string
  codeGenType: string
  deployKey: string
}

const UNSAVED_CHANGES_MESSAGE = '当前修改尚未保存，确定要离开吗？'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const { loading, begin, isLatest, end } = useLatestRequest()

const appInfo = ref<API.AppVO>()
const loadError = ref('')
const submitting = ref(false)
const formRef = ref<FormInstance>()
const baseline = ref<EditableAppForm>()

const formData = reactive<EditableAppForm>({
  appName: '',
  cover: '',
  priority: 0,
  initPrompt: '',
  codeGenType: '',
  deployKey: '',
})

const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const normalizedCoverPreview = computed(() => normalizeImageUrl(formData.cover))

const createFormSnapshot = (): EditableAppForm => ({
  appName: formData.appName,
  cover: formData.cover,
  priority: formData.priority,
  initPrompt: formData.initPrompt,
  codeGenType: formData.codeGenType,
  deployKey: formData.deployKey,
})

const isDirty = computed(() => {
  if (!baseline.value || !appInfo.value) return false
  const current = createFormSnapshot()
  const editableKeys: Array<keyof EditableAppForm> = isAdmin.value
    ? ['appName', 'cover', 'priority']
    : ['appName']
  return editableKeys.some((key) => current[key] !== baseline.value?.[key])
})

const rules = {
  appName: [
    { required: true, message: '请输入应用名称', trigger: 'blur' },
    { min: 1, max: 50, message: '应用名称长度在 1-50 个字符', trigger: 'blur' },
  ],
  cover: [
    {
      validator: async (_rule: unknown, value?: string) => {
        if (value?.trim() && !normalizeImageUrl(value)) {
          throw new Error('请输入有效的 HTTP(S) 图片地址')
        }
      },
      trigger: 'blur',
    },
  ],
  priority: [{ type: 'number', min: 0, max: 99, message: '优先级范围为 0-99', trigger: 'blur' }],
}

const parsePositiveInteger = (value: unknown) => {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const parsedValue = Number(value)
  return Number.isSafeInteger(parsedValue) && parsedValue > 0 ? parsedValue : null
}

const applyAppToForm = (app: API.AppVO) => {
  formData.appName = app.appName || ''
  formData.cover = app.cover || ''
  formData.priority = app.priority ?? 0
  formData.initPrompt = app.initPrompt || ''
  formData.codeGenType = app.codeGenType || ''
  formData.deployKey = app.deployKey || ''
  baseline.value = createFormSnapshot()
  formRef.value?.clearValidate()
}

const fetchAppInfo = async (rawId: unknown = route.params.id) => {
  const appId = parsePositiveInteger(rawId)
  if (!appId) {
    message.error('应用 ID 无效')
    await router.replace('/')
    return
  }

  const requestId = begin()
  loadError.value = ''
  appInfo.value = undefined
  baseline.value = undefined

  try {
    const res = await getAppVoById({ id: appId })
    if (!isLatest(requestId)) return

    if (res.data.code !== 0 || !res.data.data) {
      loadError.value = `获取应用信息失败：${res.data.message || '应用不存在'}`
      return
    }

    const app = res.data.data
    if (!isAdmin.value && String(app.userId) !== String(loginUserStore.loginUser.id)) {
      message.error('您没有权限编辑此应用')
      await router.replace('/')
      return
    }

    appInfo.value = app
    applyAppToForm(app)
  } catch (error) {
    if (!isLatest(requestId)) return
    console.error('Failed to load app info', error)
    loadError.value = '获取应用信息失败，请检查网络后重试'
  } finally {
    end(requestId)
  }
}

const handleSubmit = async () => {
  const appId = appInfo.value?.id
  if (!appId || submitting.value) return

  submitting.value = true
  try {
    const res = isAdmin.value
      ? await updateAppByAdmin({
          id: appId,
          appName: formData.appName.trim(),
          cover: formData.cover.trim(),
          priority: formData.priority,
        })
      : await updateApp({
          id: appId,
          appName: formData.appName.trim(),
        })

    if (res.data.code !== 0) {
      message.error(`修改失败：${res.data.message || '服务异常'}`)
      return
    }

    baseline.value = createFormSnapshot()
    message.success('修改成功')
    await fetchAppInfo(String(appId))
  } catch (error) {
    console.error('Failed to update app', error)
    message.error('修改失败，请检查网络后重试')
  } finally {
    submitting.value = false
  }
}

const resetForm = () => {
  if (!baseline.value) return
  Object.assign(formData, baseline.value)
  formRef.value?.clearValidate()
}

const retryLoad = () => {
  void fetchAppInfo(route.params.id)
}

const goHome = () => {
  void router.push('/')
}

const goToChat = () => {
  if (appInfo.value?.id) {
    void router.push({ name: 'app-chat', params: { id: String(appInfo.value.id) } })
  }
}

const openPreview = () => {
  if (!appInfo.value?.codeGenType || !appInfo.value.id) return
  const previewUrl = getStaticPreviewUrl(appInfo.value.codeGenType, String(appInfo.value.id))
  if (!openInNewTab(previewUrl)) {
    message.warning('无法打开预览，请检查浏览器弹窗设置')
  }
}

const confirmDiscardChanges = () => !isDirty.value || window.confirm(UNSAVED_CHANGES_MESSAGE)

onBeforeRouteLeave(() => confirmDiscardChanges())
onBeforeRouteUpdate(() => confirmDiscardChanges())

const handleBeforeUnload = (event: BeforeUnloadEvent) => {
  if (!isDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(
  () => route.params.id,
  (appId) => {
    void fetchAppInfo(appId)
  },
  { immediate: true },
)

onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload))
onUnmounted(() => window.removeEventListener('beforeunload', handleBeforeUnload))
</script>

<style scoped>
#appEditPage {
  position: relative;
  min-height: calc(100vh - 72px);
  overflow: hidden;
  background:
    radial-gradient(circle at 9% 4%, rgba(182, 220, 255, 0.56), transparent 30%),
    radial-gradient(circle at 91% 18%, rgba(197, 242, 232, 0.5), transparent 28%),
    linear-gradient(145deg, #fbfdff 0%, #f5f9fd 52%, #f3f9f8 100%);
}

.edit-tech-canvas {
  position: fixed;
  inset: 72px 0 0;
  z-index: 0;
  opacity: 0.58;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.82), rgba(0, 0, 0, 0.1) 82%, transparent);
}

.page-grid {
  position: absolute;
  inset: 0;
  z-index: 0;
  opacity: 0.33;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(105, 139, 176, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(105, 139, 176, 0.08) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: linear-gradient(180deg, #000, transparent 72%);
}

.page-glow {
  position: absolute;
  z-index: 0;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  filter: blur(34px);
  pointer-events: none;
}

.page-glow-primary {
  top: 80px;
  left: -230px;
  background: rgba(82, 157, 255, 0.11);
}

.page-glow-secondary {
  top: 420px;
  right: -260px;
  background: rgba(70, 203, 188, 0.12);
}

.edit-shell {
  position: relative;
  z-index: 1;
  width: min(var(--page-max-width), calc(100% - 48px));
  margin: 0 auto;
  padding: 32px 0 72px;
}

.workspace-hero {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 20px;
  min-height: 132px;
  padding: 26px 30px;
  border: 1px solid rgba(124, 155, 192, 0.18);
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.9), rgba(246, 251, 255, 0.76)),
    rgba(255, 255, 255, 0.74);
  box-shadow:
    0 24px 64px rgba(70, 102, 143, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(22px);
}

.back-button {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border: 1px solid rgba(113, 145, 183, 0.18);
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-ink);
  font-size: 17px;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(63, 91, 128, 0.08);
  transition:
    transform 0.24s var(--ease-out),
    border-color 0.24s ease,
    box-shadow 0.24s ease;
}

.back-button:hover {
  transform: translateX(-3px);
  border-color: rgba(47, 139, 255, 0.32);
  box-shadow: 0 14px 30px rgba(63, 91, 128, 0.13);
}

.hero-copy {
  min-width: 0;
}

.hero-kicker,
.panel-kicker {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.18em;
}

.hero-kicker {
  display: flex;
  align-items: center;
  gap: 9px;
}

.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-secondary);
  box-shadow: 0 0 0 6px rgba(60, 201, 187, 0.12);
}

.hero-copy h1 {
  margin: 9px 0 0;
  overflow: hidden;
  color: var(--color-ink-strong);
  font-family: var(--font-display);
  font-size: clamp(27px, 3vw, 40px);
  font-weight: 720;
  line-height: 1.05;
  letter-spacing: -0.035em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-copy p {
  margin: 9px 0 0;
  color: var(--color-ink-soft);
  font-size: 14px;
  line-height: 1.6;
}

.hero-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 9px;
  max-width: 520px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 750;
}

.status-chip::before {
  content: '';
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-chip.is-online {
  color: #07987f;
  background: rgba(16, 185, 129, 0.1);
}

.status-chip.is-draft {
  color: #6b7f96;
  background: rgba(113, 131, 151, 0.1);
}

.status-chip.is-unsaved {
  color: #d8810a;
  background: rgba(245, 158, 11, 0.11);
}

:deep(.hero-action-button.ant-btn) {
  height: 40px;
  padding-inline: 16px;
  border-radius: 13px;
  border-color: rgba(112, 143, 179, 0.18);
  background: rgba(255, 255, 255, 0.72);
  color: var(--color-ink);
  font-weight: 650;
  box-shadow: none;
}

:deep(.hero-action-button.ant-btn:hover:not(:disabled)) {
  color: var(--color-primary-strong);
  border-color: rgba(47, 139, 255, 0.3);
  transform: translateY(-1px);
}

:deep(.hero-action-primary.ant-btn:not(:disabled)) {
  border-color: transparent;
  background: linear-gradient(135deg, #267ff1, #49b6ee);
  color: #fff;
  box-shadow: 0 12px 26px rgba(47, 139, 255, 0.2);
}

.load-error {
  margin-top: 18px;
  border-radius: 16px;
  border-color: rgba(229, 72, 104, 0.18);
}

.edit-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(320px, 0.72fr);
  gap: 22px;
  align-items: start;
  margin-top: 22px;
}

.edit-panel,
.preview-card,
.metadata-card,
.guidance-card {
  border: 1px solid rgba(121, 151, 187, 0.17);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(247, 251, 255, 0.8)),
    rgba(255, 255, 255, 0.78);
  box-shadow:
    0 20px 62px rgba(70, 101, 140, 0.09),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(20px);
}

.edit-panel {
  overflow: hidden;
  border-radius: 28px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 26px 28px 22px;
  border-bottom: 1px solid rgba(120, 150, 185, 0.14);
  background: linear-gradient(110deg, rgba(239, 247, 255, 0.68), rgba(255, 255, 255, 0.38));
}

.panel-icon {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: var(--color-primary);
  font-size: 19px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgba(226, 240, 255, 0.84));
  box-shadow: 0 12px 26px rgba(60, 102, 157, 0.12);
}

.panel-heading h2,
.side-heading h2 {
  margin: 5px 0 0;
  color: var(--color-ink-strong);
  font-size: 22px;
  line-height: 1.25;
}

.panel-heading p {
  margin: 7px 0 0;
  color: var(--color-ink-soft);
  font-size: 13px;
  line-height: 1.55;
}

.panel-loading,
.empty-state {
  padding: 32px 30px;
}

.application-form {
  padding: 2px 28px 0;
}

.form-section {
  padding: 27px 0 10px;
  border-bottom: 1px solid rgba(122, 151, 185, 0.13);
}

.form-section:last-of-type {
  border-bottom: 0;
}

.form-section-heading {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}

.form-section-heading > span {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 10px;
  color: var(--color-primary-strong);
  background: rgba(47, 139, 255, 0.09);
  font-size: 11px;
  font-weight: 800;
}

.form-section-heading h3 {
  margin: 0;
  color: var(--color-ink-strong);
  font-size: 16px;
  line-height: 1.35;
}

.form-section-heading p {
  margin: 4px 0 0;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.55;
}

.admin-field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 16px;
}

.readonly-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.priority-input {
  width: 100%;
}

:deep(.application-form .ant-form-item-label > label) {
  color: var(--color-ink);
  font-size: 13px;
  font-weight: 680;
}

:deep(.application-form .ant-form-item-extra) {
  margin-top: 7px;
  color: #8b9bae;
  font-size: 11px;
}

:deep(.application-form .ant-input),
:deep(.application-form .ant-input-affix-wrapper),
:deep(.application-form .ant-input-number) {
  border-radius: 13px;
  border-color: rgba(116, 146, 181, 0.18);
  background: rgba(250, 252, 255, 0.86);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

:deep(.application-form .ant-input:hover),
:deep(.application-form .ant-input:focus),
:deep(.application-form .ant-input-number:hover),
:deep(.application-form .ant-input-number-focused) {
  border-color: rgba(47, 139, 255, 0.42);
  box-shadow: 0 0 0 4px rgba(47, 139, 255, 0.07);
}

:deep(.application-form textarea.ant-input) {
  line-height: 1.7;
  resize: vertical;
}

:deep(.application-form .ant-input-disabled),
:deep(.application-form .ant-input[disabled]),
:deep(.application-form textarea.ant-input-disabled) {
  color: #6f8198;
  background: rgba(239, 244, 249, 0.72);
}

.form-actions {
  position: sticky;
  bottom: 12px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 18px -10px 0;
  padding: 16px 10px 20px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), rgba(255, 255, 255, 0.92) 22%);
  backdrop-filter: blur(10px);
}

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-ink-soft);
  font-size: 12px;
}

.save-state-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #23b59d;
  box-shadow: 0 0 0 5px rgba(35, 181, 157, 0.1);
}

.save-state-dot.dirty {
  background: #f59e0b;
  box-shadow: 0 0 0 5px rgba(245, 158, 11, 0.11);
}

.action-buttons {
  display: flex;
  gap: 10px;
}

:deep(.action-buttons .ant-btn) {
  height: 44px;
  border-radius: 13px;
  padding-inline: 18px;
  font-weight: 680;
}

:deep(.action-buttons .ant-btn-primary) {
  border-color: transparent;
  background: linear-gradient(135deg, #247df0, #46b5ee);
  box-shadow: 0 12px 28px rgba(47, 139, 255, 0.2);
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 10px;
  min-height: 320px;
  align-content: center;
  color: var(--color-ink-soft);
  text-align: center;
}

.empty-state > :deep(.anticon) {
  color: var(--color-primary);
  font-size: 34px;
}

.empty-state h3,
.empty-state p {
  margin: 0;
}

.empty-state h3 {
  color: var(--color-ink-strong);
}

.context-column {
  display: grid;
  gap: 18px;
  position: sticky;
  top: 94px;
}

.preview-card,
.metadata-card,
.guidance-card {
  overflow: hidden;
  border-radius: 24px;
}

.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 18px;
  color: #71849b;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.15em;
}

.preview-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #1b927d;
}

.preview-status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #28bea3;
  box-shadow: 0 0 0 5px rgba(40, 190, 163, 0.11);
}

.cover-stage {
  position: relative;
  height: 210px;
  margin: 0 12px;
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(145deg, #ecf5ff, #effbf9);
}

.cover-stage::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.64);
  border-radius: inherit;
}

.cover-image,
.cover-image :deep(img) {
  width: 100%;
  height: 210px;
  object-fit: cover;
}

.cover-placeholder {
  position: relative;
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 24% 22%, rgba(63, 151, 255, 0.22), transparent 27%),
    radial-gradient(circle at 78% 78%, rgba(65, 207, 188, 0.22), transparent 31%),
    linear-gradient(145deg, #eef6ff, #f2fbfa);
}

.cover-placeholder::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.45;
  background-image:
    linear-gradient(rgba(100, 139, 183, 0.13) 1px, transparent 1px),
    linear-gradient(90deg, rgba(100, 139, 183, 0.13) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: radial-gradient(circle, #000, transparent 72%);
}

.cover-core {
  position: relative;
  z-index: 2;
  display: grid;
  width: 66px;
  height: 66px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.84);
  border-radius: 22px;
  color: #fff;
  font-family: Consolas, monospace;
  font-weight: 800;
  background: linear-gradient(135deg, #2f8bff, #43cbbb);
  box-shadow:
    0 18px 38px rgba(47, 139, 255, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.36);
}

.cover-orbit {
  position: absolute;
  border: 1px solid rgba(47, 139, 255, 0.24);
  border-radius: 50%;
  animation: coverOrbit 12s linear infinite;
}

.orbit-one {
  width: 132px;
  height: 132px;
}

.orbit-two {
  width: 180px;
  height: 74px;
  transform: rotate(-24deg);
  animation-direction: reverse;
  animation-duration: 9s;
}

.preview-copy {
  padding: 20px 20px 15px;
}

.preview-copy > span {
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.preview-copy h2 {
  margin: 7px 0 0;
  color: var(--color-ink-strong);
  font-size: 22px;
  line-height: 1.2;
}

.preview-copy p {
  display: -webkit-box;
  margin: 10px 0 0;
  overflow: hidden;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.preview-link {
  display: flex;
  width: calc(100% - 24px);
  align-items: center;
  justify-content: space-between;
  margin: 0 12px 12px;
  padding: 14px 15px;
  border: 0;
  border-radius: 14px;
  background: rgba(47, 139, 255, 0.08);
  color: var(--color-primary-strong);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition:
    background 0.22s ease,
    transform 0.22s var(--ease-out);
}

.preview-link:hover {
  transform: translateY(-1px);
  background: rgba(47, 139, 255, 0.13);
}

.metadata-card,
.guidance-card {
  padding: 22px;
}

.side-heading h2 {
  font-size: 20px;
}

.metadata-list {
  display: grid;
  gap: 0;
  margin: 18px 0 0;
}

.metadata-list > div {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  min-height: 48px;
  border-top: 1px solid rgba(121, 151, 187, 0.12);
}

.metadata-list dt {
  color: #8a9aad;
  font-size: 12px;
}

.metadata-list dd {
  min-width: 0;
  margin: 0;
  color: var(--color-ink);
  font-size: 12px;
  text-align: right;
  word-break: break-word;
}

.metadata-list .meta-online {
  color: #08947e;
  font-weight: 680;
}

.metadata-list .meta-muted {
  color: #9aa8b8;
}

.guidance-card {
  position: relative;
  background:
    radial-gradient(circle at 100% 0%, rgba(68, 197, 183, 0.18), transparent 34%),
    linear-gradient(145deg, rgba(240, 248, 255, 0.95), rgba(241, 251, 248, 0.88));
}

.guidance-index {
  color: var(--color-secondary);
  font-size: 10px;
  font-weight: 850;
  letter-spacing: 0.16em;
}

.guidance-card h2 {
  margin: 10px 0 0;
  color: var(--color-ink-strong);
  font-size: 17px;
}

.guidance-card p {
  margin: 9px 0 0;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.7;
}

@keyframes coverOrbit {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .workspace-hero {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .hero-actions {
    grid-column: 2;
    justify-content: flex-start;
    max-width: none;
  }

  .edit-layout {
    grid-template-columns: minmax(0, 1fr) 320px;
  }
}

@media (max-width: 960px) {
  .edit-shell {
    width: min(100% - 32px, 840px);
  }

  .edit-layout {
    grid-template-columns: 1fr;
  }

  .context-column {
    position: static;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .preview-card {
    grid-row: span 2;
  }
}

@media (max-width: 720px) {
  #appEditPage {
    min-height: calc(100vh - 118px);
  }

  .edit-tech-canvas {
    inset: 118px 0 0;
  }

  .edit-shell {
    width: min(100% - 24px, 640px);
    padding-top: 18px;
  }

  .workspace-hero {
    grid-template-columns: auto minmax(0, 1fr);
    gap: 14px;
    padding: 20px;
    border-radius: 22px;
  }

  .back-button {
    width: 42px;
    height: 42px;
  }

  .hero-copy h1 {
    font-size: 26px;
  }

  .hero-copy p {
    display: none;
  }

  .hero-actions {
    grid-column: 1 / -1;
  }

  .edit-panel {
    border-radius: 22px;
  }

  .panel-heading,
  .application-form {
    padding-left: 20px;
    padding-right: 20px;
  }

  .admin-field-grid,
  .readonly-grid,
  .context-column {
    grid-template-columns: 1fr;
  }

  .form-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .action-buttons {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .preview-card {
    grid-row: auto;
  }
}

@media (max-width: 460px) {
  .hero-action-button {
    flex: 1;
  }

  .status-chip {
    min-height: 30px;
  }

  .action-buttons {
    grid-template-columns: 1fr;
  }
}
</style>
