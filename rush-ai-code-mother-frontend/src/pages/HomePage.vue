<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Motion } from 'motion-v'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  addApp,
  copyApp,
  deleteApp,
  listMyAppVoByPage,
  listGoodAppVoByPage,
  optimizePrompt,
} from '@/api/appController'
import { getDeployUrl } from '@/config/env'
import { normalizeImageUrl } from '@/utils/url'
import { DEFAULT_APP_COVER, DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import { BulbOutlined } from '@ant-design/icons-vue'
import { fadeUp, staggerChild, hoverLift } from '@/composables/useMotionPresets'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { openInNewTab } from '@/utils/browser'
import AmbientTechCanvas from '@/components/visual/AmbientTechCanvas.vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const userPrompt = ref('')
const promptInputRef = ref()
const creating = ref(false)
const optimizingPrompt = ref(false)
const animatedPlaceholder =
  '例如：帮我创建一个面向设计师的作品集网站，包含项目展示、个人介绍和联系表单...'
const shouldShowAnimatedPlaceholder = computed(
  () => !userPrompt.value && !creating.value && !optimizingPrompt.value,
)
const composerBusy = computed(() => creating.value || optimizingPrompt.value)
const composerStatusText = computed(() => {
  if (optimizingPrompt.value) {
    return 'Optimizing'
  }
  if (creating.value) {
    return 'Launching'
  }
  return 'Ready'
})
const composerFeedbackTitle = computed(() => {
  if (optimizingPrompt.value) {
    return '正在优化提示词'
  }
  if (creating.value) {
    return '正在创建应用'
  }
  return ''
})
const composerFeedbackDescription = computed(() => {
  if (optimizingPrompt.value) {
    return 'AI 正在梳理需求结构、补齐页面模块与交互细节'
  }
  if (creating.value) {
    return '正在建立应用骨架，完成后会自动进入对话与代码编辑器'
  }
  return ''
})

const myApps = ref<API.OwnerAppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

type AppCardView = API.OwnerAppVO | API.PublicAppVO

const featuredApps = ref<API.PublicAppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

const {
  loading: myAppsLoading,
  begin: beginMyAppsRequest,
  isLatest: isLatestMyAppsRequest,
  end: endMyAppsRequest,
} = useLatestRequest()
const {
  loading: featuredAppsLoading,
  begin: beginFeaturedAppsRequest,
  isLatest: isLatestFeaturedAppsRequest,
  end: endFeaturedAppsRequest,
} = useLatestRequest()
const myAppsError = ref('')
const featuredAppsError = ref('')

const copyingAppIds = ref<Set<string>>(new Set())
const deletingAppIds = ref<Set<string>>(new Set())
const retiringAppIds = ref<Set<string>>(new Set())
const deleteModalOpen = ref(false)
const pendingDeleteApp = ref<API.OwnerAppVO | null>(null)
const deletePhase = ref<'confirm' | 'deleting' | 'done'>('confirm')

const showcasePalette = [
  '运营工作台',
  '可部署 Demo',
  '交互案例',
  '业务流程',
  '团队模板',
  '上线预览',
]

const promptTemplates = [
  {
    title: '个人博客',
    tag: 'Content',
    summary: '文章、搜索、分类与个人主页',
    prompt:
      '创建一个现代化的个人博客网站，包含文章列表、详情页、分类标签、搜索功能、评论系统和个人简介页面。采用简洁的设计风格，支持响应式布局，文章支持Markdown格式，首页展示最新文章和热门推荐。',
  },
  {
    title: '企业官网',
    tag: 'Business',
    summary: '公司介绍、服务展示与客户案例',
    prompt:
      '设计一个专业的企业官网，包含公司介绍、产品服务展示、新闻资讯、联系我们等页面。采用商务风格的设计，包含轮播图、产品展示卡片、团队介绍、客户案例展示，支持多语言切换和在线客服功能。',
  },
  {
    title: '在线商城',
    tag: 'Commerce',
    summary: '商品、订单、支付与会员体系',
    prompt:
      '构建一个功能完整的在线商城，包含商品展示、购物车、用户注册登录、订单管理、支付结算等功能。设计现代化的商品卡片布局，支持商品搜索筛选、用户评价、优惠券系统和会员积分功能。',
  },
  {
    title: '作品展示',
    tag: 'Portfolio',
    summary: '画廊、项目详情与分类筛选',
    prompt:
      '制作一个精美的作品展示网站，适合设计师、摄影师、艺术家等创作者。包含作品画廊、项目详情页、个人简历、联系方式等模块。采用瀑布流或网格布局展示作品，支持图片放大预览和作品分类筛选。',
  },
]

const setPrompt = (prompt: string) => {
  userPrompt.value = prompt
}

const focusPromptInput = () => {
  promptInputRef.value?.focus?.()
}

/**
 * 响应全局命令中心的“新建应用”动作。
 * 使用浏览器事件解耦顶栏与首页，避免共享组件直接依赖页面实例。
 */
const focusComposerFromCommand = () => {
  focusPromptInput()
  promptInputRef.value?.$el?.scrollIntoView?.({
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: 'center',
  })
}

const createApp = async () => {
  const prompt = userPrompt.value.trim()
  if (!prompt) {
    message.warning('请输入应用描述')
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }

  creating.value = true
  try {
    const res = await addApp({
      initPrompt: prompt,
    })

    if (res.data.code === 0 && res.data.data) {
      message.success('应用创建成功')
      const appId = String(res.data.data)
      await router.push(`/app/chat/${appId}?autoStart=1`)
    } else {
      message.error('创建失败：' + res.data.message)
    }
  } catch (error) {
    console.error('创建应用失败：', error)
    message.error('创建失败，请重试')
  } finally {
    creating.value = false
  }
}

const optimizeUserPrompt = async () => {
  const sourcePrompt = userPrompt.value
  const prompt = sourcePrompt.trim()
  if (!prompt || creating.value || optimizingPrompt.value) {
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }

  optimizingPrompt.value = true
  try {
    const res = await optimizePrompt({ prompt })
    if (res.data.code === 0 && res.data.data) {
      if (userPrompt.value !== sourcePrompt) {
        message.info('你已修改输入，本次优化结果未覆盖当前内容')
        return
      }
      userPrompt.value = res.data.data
      message.success('提示词已优化')
      return
    }
    message.error('优化失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('Failed to optimize prompt', error)
    message.error('优化失败，请检查网络后重试')
  } finally {
    optimizingPrompt.value = false
  }
}

const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    myApps.value = []
    myAppsPage.total = 0
    myAppsError.value = ''
    return
  }

  const requestId = beginMyAppsRequest()
  myAppsError.value = ''
  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })
    if (!isLatestMyAppsRequest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records ?? []
      myAppsPage.total = res.data.data.totalRow ?? 0
      return
    }
    myAppsError.value = `加载我的作品失败：${res.data.message || '服务异常'}`
  } catch (error) {
    if (!isLatestMyAppsRequest(requestId)) return
    console.error('Failed to load my apps', error)
    myAppsError.value = '加载我的作品失败，请检查网络后重试'
  } finally {
    endMyAppsRequest(requestId)
  }
}

const loadFeaturedApps = async () => {
  const requestId = beginFeaturedAppsRequest()
  featuredAppsError.value = ''
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })
    if (!isLatestFeaturedAppsRequest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records ?? []
      featuredAppsPage.total = res.data.data.totalRow ?? 0
      return
    }
    featuredAppsError.value = `加载精选案例失败：${res.data.message || '服务异常'}`
  } catch (error) {
    if (!isLatestFeaturedAppsRequest(requestId)) return
    console.error('Failed to load featured apps', error)
    featuredAppsError.value = '加载精选案例失败，请检查网络后重试'
  } finally {
    endFeaturedAppsRequest(requestId)
  }
}

const viewChat = (appId: string | number | undefined) => {
  if (appId) {
    router.push(`/app/chat/${appId}?view=1`)
  }
}

const viewWork = (app: AppCardView) => {
  if (!app.deployKey) {
    message.warning('该应用尚未部署')
    return
  }

  if (!openInNewTab(getDeployUrl(app.deployKey))) {
    message.warning('无法打开预览，请检查浏览器弹窗设置')
  }
}

const isOwnApp = (app: AppCardView) => {
  if (!app.userId || !loginUserStore.loginUser.id) {
    return false
  }
  return String(app.userId) === String(loginUserStore.loginUser.id)
}

const isCopyingApp = (app: AppCardView) => {
  return Boolean(app.id && copyingAppIds.value.has(String(app.id)))
}

const isDeletingApp = (app: AppCardView) => {
  return Boolean(app.id && deletingAppIds.value.has(String(app.id)))
}

const isRetiringApp = (app: AppCardView) => {
  return Boolean(app.id && retiringAppIds.value.has(String(app.id)))
}

const getAppImage = (app: AppCardView) => normalizeImageUrl(app.cover) || DEFAULT_APP_COVER

const getAppAuthor = (app: AppCardView, fallback = '未知用户') =>
  app.user?.userName || fallback

const getAppSummary = (app: AppCardView) => {
  const source = ('initPrompt' in app ? app.initPrompt || '' : '').trim()
  if (source) {
    return source.length > 54 ? `${source.slice(0, 54)}...` : source
  }
  return `围绕 ${app.appName || '当前应用'} 构建页面、流程与交互，可继续对话改修并快速预览。`
}

const featuredCarouselItems = computed(() =>
  featuredApps.value.slice(0, 6).map((app, index) => ({
    id: String(app.id ?? index),
    title: app.appName || '未命名案例',
    category: showcasePalette[index % showcasePalette.length],
    src: getAppImage(app),
    summary: getAppSummary(app),
    app,
  })),
)

const workspaceCards = computed(() =>
  myApps.value.map((app, index) => ({
    id: String(app.id ?? index),
    title: app.appName || '未命名应用',
    author: getAppAuthor(app),
    summary: getAppSummary(app),
    imageUrl: getAppImage(app),
    avatar: app.user?.userAvatar || DEFAULT_USER_AVATAR,
    status: app.isGenerating ? '生成中' : app.deployKey ? '已部署' : '草稿',
    app,
  })),
)

const pendingDeleteSummary = computed(() => {
  if (!pendingDeleteApp.value) {
    return ''
  }
  return getAppSummary(pendingDeleteApp.value)
})

let disposed = false
const pendingTimeouts = new Map<number, () => void>()

const scheduleTimeout = (callback: () => void, delayMs: number) => {
  const timerId = window.setTimeout(() => {
    pendingTimeouts.delete(timerId)
    callback()
  }, delayMs)
  pendingTimeouts.set(timerId, () => undefined)
  return timerId
}

const waitForTimeout = (delayMs: number) =>
  new Promise<void>((resolve) => {
    const timerId = window.setTimeout(() => {
      pendingTimeouts.delete(timerId)
      resolve()
    }, delayMs)
    pendingTimeouts.set(timerId, resolve)
  })

const openDeleteModal = (app: API.OwnerAppVO) => {
  if (!app.id || isDeletingApp(app)) {
    return
  }
  pendingDeleteApp.value = app
  deletePhase.value = 'confirm'
  deleteModalOpen.value = true
}

const closeDeleteModal = () => {
  if (deletePhase.value === 'deleting') {
    return
  }
  deleteModalOpen.value = false
  scheduleTimeout(() => {
    if (!deleteModalOpen.value) {
      pendingDeleteApp.value = null
      deletePhase.value = 'confirm'
    }
  }, 240)
}

const copyFeaturedApp = async (app: API.PublicAppVO) => {
  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }
  if (!app.id) {
    message.error('应用ID不存在')
    return
  }
  const appId = String(app.id)
  if (copyingAppIds.value.has(appId)) {
    return
  }
  copyingAppIds.value = new Set(copyingAppIds.value).add(appId)
  try {
    const res = await copyApp({
      sourceAppId: app.id,
    })
    if (res.data.code === 0 && res.data.data) {
      message.success('复制成功，已加入我的作品')
      await loadMyApps()
      await router.push(`/app/chat/${res.data.data}?view=1`)
    } else {
      message.error('复制失败：' + res.data.message)
    }
  } catch (error) {
    console.error('复制应用失败：', error)
    message.error('复制失败，请重试')
  } finally {
    const nextIds = new Set(copyingAppIds.value)
    nextIds.delete(appId)
    copyingAppIds.value = nextIds
  }
}

const deleteMyApp = async (app = pendingDeleteApp.value) => {
  if (!app?.id) {
    message.error('应用ID不存在')
    return
  }
  const appId = String(app.id)
  deletePhase.value = 'deleting'
  deletingAppIds.value = new Set(deletingAppIds.value).add(appId)
  try {
    const res = await deleteApp({ id: app.id })
    if (res.data.code === 0 && res.data.data) {
      deletePhase.value = 'done'
      retiringAppIds.value = new Set(retiringAppIds.value).add(appId)
      if (myApps.value.length === 1 && myAppsPage.current > 1) {
        myAppsPage.current -= 1
      }
      await waitForTimeout(420)
      if (disposed) return
      myApps.value = myApps.value.filter((item) => String(item.id) !== appId)
      await loadMyApps()
      message.success('作品和本地文件已删除')
      scheduleTimeout(closeDeleteModal, 360)
    } else {
      deletePhase.value = 'confirm'
      message.error('删除失败：' + (res.data.message || '请重试'))
    }
  } catch (error) {
    console.error('删除应用失败：', error)
    deletePhase.value = 'confirm'
    message.error('删除失败，请重试')
  } finally {
    const nextIds = new Set(deletingAppIds.value)
    nextIds.delete(appId)
    deletingAppIds.value = nextIds
    const nextRetiringIds = new Set(retiringAppIds.value)
    nextRetiringIds.delete(appId)
    retiringAppIds.value = nextRetiringIds
  }
}

const handleMouseMove = (e: MouseEvent) => {
  const { clientX, clientY } = e
  const { innerWidth, innerHeight } = window
  const x = (clientX / innerWidth) * 100
  const y = (clientY / innerHeight) * 100

  document.documentElement.style.setProperty('--mouse-x', `${x}%`)
  document.documentElement.style.setProperty('--mouse-y', `${y}%`)
}

onMounted(() => {
  void loadMyApps()
  void loadFeaturedApps()
  document.addEventListener('mousemove', handleMouseMove)
  window.addEventListener('rush:focus-composer', focusComposerFromCommand)
})

onUnmounted(() => {
  disposed = true
  document.removeEventListener('mousemove', handleMouseMove)
  window.removeEventListener('rush:focus-composer', focusComposerFromCommand)
  pendingTimeouts.forEach((onCancel, timerId) => {
    window.clearTimeout(timerId)
    onCancel()
  })
  pendingTimeouts.clear()
})
</script>

<template>
  <div id="homePage">
    <AmbientTechCanvas class="home-tech-canvas" :density="72" />
    <div class="ambient-grid"></div>
    <div class="ambient-glow glow-one"></div>
    <div class="ambient-glow glow-two"></div>

    <main class="home-shell">
      <section class="hero-section">
        <Motion class="hero-copy" as="div" v-bind="fadeUp(0)">
          <Motion as="div" v-bind="staggerChild(0, 0.08)">
            <div class="eyebrow">
              <span class="eyebrow-dot"></span>
              AI APP GENERATION CONSOLE
            </div>
          </Motion>
          <Motion as="div" v-bind="staggerChild(1, 0.08)">
            <h1 class="hero-title">把一句需求，推进成可对话、可部署的应用。</h1>
          </Motion>
          <Motion as="div" v-bind="staggerChild(2, 0.08)">
            <p class="hero-description">
              用自然语言描述目标，系统会为你创建应用骨架并进入对话调试流程，适合快速验证
              MVP、内部工具和演示项目。
            </p>
          </Motion>
          <Motion as="div" v-bind="staggerChild(3, 0.08)">
            <div class="hero-metrics">
              <Motion v-for="(m, i) in 3" :key="i" as="div" v-bind="staggerChild(i, 0.36)">
                <div class="metric-pill">
                  <strong>{{ ['Prompt', 'Chat', 'Deploy'][i] }}</strong>
                  <span>{{ ['需求驱动', '持续迭代', '在线预览'][i] }}</span>
                </div>
              </Motion>
            </div>
          </Motion>
        </Motion>

        <Motion class="composer-panel" as="div" v-bind="fadeUp(0.1)">
          <div class="composer-head">
            <div>
              <span class="panel-kicker">New Application</span>
              <h2>描述你要生成的应用</h2>
            </div>
            <span class="status-chip" :class="{ active: composerBusy }">{{
              composerStatusText
            }}</span>
          </div>
          <div class="input-section" :class="{ busy: composerBusy }">
            <button
              v-if="shouldShowAnimatedPlaceholder"
              type="button"
              class="prompt-placeholder"
              @click="focusPromptInput"
            >
              <TextGenerateEffect :words="animatedPlaceholder" class="prompt-placeholder-text" />
            </button>
            <a-textarea
              ref="promptInputRef"
              v-model:value="userPrompt"
              placeholder=""
              :rows="5"
              :maxlength="1000"
              class="prompt-input"
              :disabled="creating || optimizingPrompt"
            />
            <Transition name="composer-feedback">
              <div v-if="composerBusy" class="composer-feedback">
                <div class="composer-feedback-visual" aria-hidden="true">
                  <span class="feedback-ring"></span>
                  <span class="feedback-bracket">{ }</span>
                  <span class="feedback-line feedback-line-one"></span>
                  <span class="feedback-line feedback-line-two"></span>
                  <span class="feedback-line feedback-line-three"></span>
                </div>
                <div class="composer-feedback-copy">
                  <strong>{{ composerFeedbackTitle }}</strong>
                  <span>{{ composerFeedbackDescription }}</span>
                </div>
                <div class="composer-feedback-rail" aria-hidden="true">
                  <span></span>
                </div>
              </div>
            </Transition>
            <div class="input-footer">
              <span>{{ userPrompt.length }}/1000</span>
              <div class="input-tools">
                <a-tooltip title="优化提示词" placement="top">
                  <a-button
                    size="large"
                    class="optimize-button"
                    :loading="optimizingPrompt"
                    :disabled="!userPrompt.trim() || creating"
                    @click="optimizeUserPrompt"
                  >
                    <template #icon>
                      <BulbOutlined />
                    </template>
                    优化
                  </a-button>
                </a-tooltip>
                <ShimmerButton
                  class="create-button"
                  :loading="creating"
                  :disabled="optimizingPrompt || creating"
                  @click="createApp"
                >
                  {{ creating ? '生成中...' : '开始生成' }}
                </ShimmerButton>
              </div>
            </div>
          </div>
        </Motion>
      </section>

      <section class="template-section">
        <Motion
          v-for="(item, index) in promptTemplates"
          :key="item.title"
          as="div"
          v-bind="{ ...staggerChild(index, 0), ...hoverLift }"
          class="template-card"
          @click="setPrompt(item.prompt)"
        >
          <span>{{ item.tag }}</span>
          <strong>{{ item.title }}</strong>
          <p class="template-summary">
            <TextScrollReveal :text="item.summary" :stagger="0.05" :duration="0.48" />
          </p>
        </Motion>
      </section>

      <Motion as="section" v-bind="fadeUp(0)" class="section">
        <Motion as="div" v-bind="fadeUp(0.05)">
          <div class="section-head">
            <div>
              <span class="section-kicker">My Workspace</span>
              <h2 class="section-title">我的作品</h2>
            </div>
            <p>继续编辑最近创建的应用，或进入只读对话查看生成过程。</p>
          </div>
        </Motion>
        <div v-if="myAppsLoading" class="empty-panel" role="status">
          <strong>正在加载作品</strong>
          <span>请稍候...</span>
        </div>
        <div v-else-if="myAppsError" class="empty-panel">
          <strong>我的作品加载失败</strong>
          <span>{{ myAppsError }}</span>
          <a-button type="primary" @click="loadMyApps">重试</a-button>
        </div>
        <TransitionGroup
          v-else-if="workspaceCards.length"
          name="workspace-card-flow"
          tag="div"
          class="workspace-grid"
        >
          <Motion
            v-for="(item, index) in workspaceCards"
            :key="item.id"
            as="article"
            v-bind="{ ...staggerChild(index, 0.1), ...hoverLift }"
            class="workspace-card"
            :class="{ 'is-retiring': isRetiringApp(item.app), 'is-busy': isDeletingApp(item.app) }"
          >
            <GlowingEffect class="workspace-glow">
              <DirectionAwareHover
                :image-url="item.imageUrl"
                :image-alt="item.title"
                class="workspace-direction-card"
                image-class="workspace-direction-image"
                children-class="workspace-direction-content"
              >
                <div class="workspace-hover-copy">
                  <span>{{ item.status }}</span>
                  <strong>{{ item.title }}</strong>
                  <p>{{ item.author }}</p>
                  <div class="workspace-card-actions">
                    <button
                      type="button"
                      class="workspace-action workspace-action--primary"
                      @click="viewChat(item.app.id)"
                    >
                      查看对话
                    </button>
                    <button
                      type="button"
                      class="workspace-action"
                      :disabled="!item.app.deployKey"
                      @click="viewWork(item.app)"
                    >
                      在线预览
                    </button>
                    <button
                      type="button"
                      class="workspace-action workspace-action--danger"
                      :disabled="isDeletingApp(item.app)"
                      @click="openDeleteModal(item.app)"
                    >
                      {{ isDeletingApp(item.app) ? '清理中' : '删除' }}
                    </button>
                  </div>
                </div>
              </DirectionAwareHover>
            </GlowingEffect>
          </Motion>
        </TransitionGroup>
        <div v-else class="empty-panel">
          <strong>还没有作品</strong>
          <span>在上方输入需求并生成第一个应用。</span>
        </div>
        <div
          v-if="!myAppsLoading && !myAppsError && myAppsPage.total > myAppsPage.pageSize"
          class="pagination-wrapper"
        >
          <a-pagination
            v-model:current="myAppsPage.current"
            v-model:page-size="myAppsPage.pageSize"
            :total="myAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个应用`"
            @change="loadMyApps"
          />
        </div>
      </Motion>

      <Motion as="section" v-bind="fadeUp(0)" class="section">
        <Motion as="div" v-bind="fadeUp(0.05)">
          <div class="section-head">
            <div>
              <span class="section-kicker">Featured Gallery</span>
              <h2 class="section-title">精选案例</h2>
            </div>
            <p>参考优秀案例的结构、交互和页面组织方式。</p>
          </div>
        </Motion>
        <div v-if="featuredAppsLoading" class="empty-panel" role="status">
          <strong>正在加载精选案例</strong>
          <span>请稍候...</span>
        </div>
        <div v-else-if="featuredAppsError" class="empty-panel">
          <strong>精选案例加载失败</strong>
          <span>{{ featuredAppsError }}</span>
          <a-button type="primary" @click="loadFeaturedApps">重试</a-button>
        </div>
        <div v-else-if="featuredCarouselItems.length" class="featured-carousel-wrap">
          <AppleCardCarousel>
            <AppleCarouselItem
              v-for="(item, index) in featuredCarouselItems"
              :key="item.id"
              :index="index"
            >
              <AppleCard :card="item" :index="index">
                <div
                  class="featured-modal-body"
                  :class="{ 'is-copying': isCopyingApp(item.app) }"
                  :aria-busy="isCopyingApp(item.app)"
                >
                  <Lens class="featured-modal-lens" :lens-size="180" :zoom-factor="1.65">
                    <img :src="item.src" :alt="item.title" class="featured-modal-image" />
                  </Lens>
                  <div class="featured-modal-copy">
                    <span>{{ item.category }}</span>
                    <p>{{ item.summary }}</p>
                    <div class="featured-modal-actions">
                      <button
                        type="button"
                        class="workspace-action workspace-action--primary"
                        @click="viewWork(item.app)"
                      >
                        预览
                      </button>
                      <button
                        type="button"
                        class="workspace-action copy-case-action"
                        :class="{ 'is-loading': !isOwnApp(item.app) && isCopyingApp(item.app) }"
                        :disabled="!isOwnApp(item.app) && isCopyingApp(item.app)"
                        @click="
                          isOwnApp(item.app) ? viewChat(item.app.id) : copyFeaturedApp(item.app)
                        "
                      >
                        <span
                          v-if="!isOwnApp(item.app) && isCopyingApp(item.app)"
                          class="copy-action-orbit"
                          aria-hidden="true"
                        >
                          <i></i>
                        </span>
                        <span>{{
                          isOwnApp(item.app)
                            ? '查看对话'
                            : isCopyingApp(item.app)
                              ? '复制中'
                              : '复制案例'
                        }}</span>
                      </button>
                    </div>
                  </div>
                  <Transition name="copy-stage">
                    <div
                      v-if="isCopyingApp(item.app)"
                      class="copy-stage-overlay"
                      role="status"
                      aria-live="polite"
                    >
                      <div class="copy-stage-card">
                        <div class="copy-stage-visual" aria-hidden="true">
                          <span class="copy-stage-ring"></span>
                          <span class="copy-stage-core"></span>
                          <span class="copy-stage-pulse copy-stage-pulse-one"></span>
                          <span class="copy-stage-pulse copy-stage-pulse-two"></span>
                        </div>
                        <div class="copy-stage-copy">
                          <strong>正在复制案例</strong>
                          <span>正在整理代码、对话记录与作品空间</span>
                        </div>
                        <div class="copy-stage-progress" aria-hidden="true">
                          <span></span>
                        </div>
                      </div>
                    </div>
                  </Transition>
                </div>
              </AppleCard>
            </AppleCarouselItem>
          </AppleCardCarousel>
        </div>
        <div v-else class="empty-panel">
          <strong>暂无精选案例</strong>
          <span>精选内容发布后会展示在这里。</span>
        </div>
        <div
          v-if="
            !featuredAppsLoading &&
            !featuredAppsError &&
            featuredAppsPage.total > featuredAppsPage.pageSize
          "
          class="pagination-wrapper"
        >
          <a-pagination
            v-model:current="featuredAppsPage.current"
            v-model:page-size="featuredAppsPage.pageSize"
            :total="featuredAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个案例`"
            @change="loadFeaturedApps"
          />
        </div>
      </Motion>
    </main>

    <Teleport to="body">
      <Transition name="delete-modal">
        <div
          v-if="deleteModalOpen && pendingDeleteApp"
          class="delete-modal-layer"
          @click.self="closeDeleteModal"
        >
          <Motion as="section" class="delete-modal-card" v-bind="fadeUp(0)">
            <GlowingEffect class="delete-modal-glow">
              <div class="delete-modal-inner" :class="`phase-${deletePhase}`">
                <div class="delete-modal-visual" aria-hidden="true">
                  <BubblesBackground
                    class="delete-bubbles"
                    :bubble-count="18"
                    :interactive="false"
                    :speed="0.72"
                    color1="#ff8aa3"
                    color2="#ffc6d2"
                    color3="#7dd3fc"
                  />
                  <div class="delete-orbit">
                    <span></span>
                    <span></span>
                  </div>
                  <div class="delete-symbol">
                    <span v-if="deletePhase === 'done'">✓</span>
                    <span v-else>!</span>
                  </div>
                </div>
                <div class="delete-modal-copy">
                  <span class="delete-kicker">DELETE WORKSPACE</span>
                  <h3>{{ deletePhase === 'done' ? '作品已清理完成' : '删除这个作品？' }}</h3>
                  <p v-if="deletePhase === 'confirm'">
                    将永久删除「{{
                      pendingDeleteApp.appName || '未命名应用'
                    }}」，并同步清理本地生成文件与部署目录。
                  </p>
                  <p v-else-if="deletePhase === 'deleting'">
                    正在清理数据库记录、本地生成文件和部署产物，请稍候。
                  </p>
                  <p v-else>
                    「{{ pendingDeleteApp.appName || '未命名应用' }}」已经从我的作品中移除。
                  </p>
                  <div v-if="deletePhase === 'confirm'" class="delete-target">
                    <img
                      :src="getAppImage(pendingDeleteApp)"
                      :alt="pendingDeleteApp.appName || '待删除作品'"
                    />
                    <div>
                      <strong>{{ pendingDeleteApp.appName || '未命名应用' }}</strong>
                      <span>{{ pendingDeleteSummary }}</span>
                    </div>
                  </div>
                  <div v-else class="delete-progress" :class="{ done: deletePhase === 'done' }">
                    <span></span>
                  </div>
                </div>
                <div class="delete-modal-actions">
                  <button
                    type="button"
                    class="delete-modal-button delete-modal-button--ghost"
                    :disabled="deletePhase === 'deleting'"
                    @click="closeDeleteModal"
                  >
                    {{ deletePhase === 'done' ? '关闭' : '取消' }}
                  </button>
                  <ShimmerButton
                    v-if="deletePhase !== 'done'"
                    class="delete-modal-button delete-modal-button--danger"
                    :loading="deletePhase === 'deleting'"
                    :disabled="deletePhase === 'deleting'"
                    @click="deleteMyApp()"
                  >
                    {{ deletePhase === 'deleting' ? '清理中...' : '确认删除' }}
                  </ShimmerButton>
                </div>
              </div>
            </GlowingEffect>
          </Motion>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
#homePage {
  --strong-text: #0d1b2a;
  --soft-text: #62748b;
  --muted-text: #8a9bb0;
  --accent: #2f80ff;
  --accent-strong: #1858c8;
  --panel-border: rgba(104, 132, 175, 0.16);
  width: 100%;
  min-height: 100vh;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 10%, rgba(47, 128, 255, 0.12), transparent 28%),
    radial-gradient(circle at 90% 16%, rgba(44, 192, 210, 0.14), transparent 24%),
    linear-gradient(180deg, #fbfdff 0%, #f4f8fc 48%, #edf3fa 100%);
}

.home-tech-canvas {
  position: fixed;
  inset: 72px 0 0;
  z-index: 0;
  opacity: 0.72;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.92), rgba(0, 0, 0, 0.2) 78%, transparent);
}

.ambient-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(122, 146, 184, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(122, 146, 184, 0.08) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(circle at center, black 35%, transparent 88%);
  opacity: 0.72;
  pointer-events: none;
  animation: gridDrift 18s linear infinite;
}

.ambient-glow {
  position: absolute;
  border-radius: 999px;
  filter: blur(18px);
  pointer-events: none;
}

.glow-one {
  width: 360px;
  height: 360px;
  top: 90px;
  right: 10%;
  background: rgba(47, 128, 255, 0.12);
  animation: floatGlow 10s ease-in-out infinite;
}

.glow-two {
  width: 420px;
  height: 420px;
  left: -120px;
  top: 380px;
  background: rgba(44, 192, 210, 0.1);
  animation: floatGlow 12s ease-in-out infinite reverse;
}

#homePage::after {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(
    520px circle at var(--mouse-x, 50%) var(--mouse-y, 30%),
    rgba(47, 128, 255, 0.09),
    transparent 70%
  );
  pointer-events: none;
}

.home-shell {
  position: relative;
  z-index: 1;
  width: min(1240px, calc(100% - 48px));
  margin: 0 auto;
  padding: 46px 0 72px;
}

.hero-section {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(420px, 520px);
  gap: 32px;
  align-items: stretch;
  margin-bottom: 22px;
}

.hero-copy,
.composer-panel,
.section,
.template-card,
.empty-panel {
  border: 1px solid var(--panel-border);
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.92), rgba(247, 250, 255, 0.78)),
    rgba(255, 255, 255, 0.84);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px rgba(114, 137, 170, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

.hero-copy {
  position: relative;
  overflow: hidden;
  border-radius: 34px;
  padding: 42px;
  min-height: 440px;
}

.hero-copy::after {
  content: '';
  position: absolute;
  width: 320px;
  height: 320px;
  right: -100px;
  bottom: -120px;
  border-radius: 999px;
  background: rgba(47, 128, 255, 0.08);
  pointer-events: none;
  z-index: 0;
}

.hero-copy > * {
  position: relative;
  z-index: 1;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  margin-bottom: 24px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(104, 132, 175, 0.16);
  color: var(--accent);
  font-size: 12px;
  letter-spacing: 0.22em;
}

.eyebrow-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 0 7px rgba(47, 128, 255, 0.1);
}

.hero-title {
  max-width: 720px;
  margin: 0;
  color: var(--strong-text);
  font-size: clamp(42px, 5vw, 68px);
  line-height: 1.03;
  letter-spacing: -0.05em;
  text-wrap: balance;
}

.hero-description {
  max-width: 620px;
  margin: 24px 0 0;
  color: var(--soft-text);
  font-size: 16px;
  line-height: 1.9;
}

.hero-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 38px;
}

.metric-pill {
  min-width: 142px;
  padding: 16px 18px;
  border-radius: 22px;
  border: 1px solid rgba(104, 132, 175, 0.14);
  background: rgba(255, 255, 255, 0.68);
}

.metric-pill strong {
  display: block;
  color: var(--strong-text);
  font-size: 18px;
}

.metric-pill span {
  display: block;
  margin-top: 6px;
  color: var(--muted-text);
  font-size: 13px;
}

.composer-panel {
  border-radius: 30px;
  padding: 30px;
}

.composer-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 22px;
}

.panel-kicker,
.section-kicker {
  display: block;
  color: var(--accent);
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  margin-bottom: 10px;
}

.composer-head h2 {
  margin: 0;
  color: var(--strong-text);
  font-size: 28px;
  line-height: 1.2;
}

.status-chip {
  padding: 7px 12px;
  border-radius: 999px;
  color: var(--accent-strong);
  background: rgba(47, 128, 255, 0.1);
  font-size: 12px;
  font-weight: 600;
  transition:
    background 0.24s ease,
    box-shadow 0.24s ease,
    color 0.24s ease;
}

.status-chip.active {
  background: rgba(47, 128, 255, 0.14);
  box-shadow: 0 0 0 6px rgba(47, 128, 255, 0.08);
}

.input-section {
  position: relative;
}

.input-section::after {
  content: '';
  position: absolute;
  left: 18px;
  right: 18px;
  top: 1px;
  height: 1px;
  opacity: 0;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(47, 128, 255, 0.58),
    rgba(44, 192, 210, 0.42),
    transparent
  );
  transform: scaleX(0.42);
  transition:
    opacity 0.24s ease,
    transform 0.24s ease;
  pointer-events: none;
}

.input-section.busy::after {
  opacity: 1;
  transform: scaleX(1);
  animation: inputLightSweep 1.8s ease-in-out infinite;
}

.prompt-placeholder {
  position: absolute;
  top: 18px;
  left: 19px;
  right: 24px;
  z-index: 2;
  padding: 0;
  border: 0;
  background: transparent;
  color: #93a5bc;
  font-size: 15px;
  line-height: 1.8;
  text-align: left;
  pointer-events: auto;
  cursor: text;
}

.prompt-placeholder-text {
  display: block;
  max-width: 100%;
}

:deep(.prompt-input.ant-input) {
  border-radius: 22px;
  border: 1px solid rgba(104, 132, 175, 0.14);
  font-size: 15px;
  line-height: 1.8;
  padding: 18px 18px 76px;
  color: var(--strong-text);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.86);
  resize: none;
  transition:
    border-color 0.24s ease,
    box-shadow 0.24s ease,
    transform 0.24s ease;
}

:deep(.prompt-input.ant-input:hover),
:deep(.prompt-input.ant-input:focus) {
  border-color: rgba(47, 128, 255, 0.42);
  box-shadow: 0 0 0 4px rgba(47, 128, 255, 0.08);
  transform: translateY(-1px);
}

.input-section.busy :deep(.prompt-input.ant-input) {
  border-color: rgba(47, 128, 255, 0.28);
  box-shadow:
    0 0 0 4px rgba(47, 128, 255, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

:deep(.prompt-input.ant-input::placeholder) {
  color: transparent;
}

.input-footer {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  color: var(--muted-text);
  font-size: 13px;
}

.input-tools {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.composer-feedback {
  position: absolute;
  left: 16px;
  right: 16px;
  top: 16px;
  z-index: 3;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 12px 14px;
  align-items: center;
  padding: 14px 14px 12px;
  border: 1px solid rgba(104, 132, 175, 0.16);
  border-radius: 18px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(247, 250, 255, 0.9)),
    radial-gradient(circle at 8% 0%, rgba(47, 128, 255, 0.12), transparent 38%);
  box-shadow:
    0 22px 46px rgba(80, 106, 145, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(16px);
}

.composer-feedback-visual {
  position: relative;
  width: 54px;
  height: 54px;
  border-radius: 18px;
  overflow: hidden;
  display: grid;
  place-items: center;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.95), rgba(235, 244, 255, 0.86)),
    radial-gradient(circle at 72% 22%, rgba(44, 192, 210, 0.2), transparent 42%);
  border: 1px solid rgba(104, 132, 175, 0.16);
}

.composer-feedback-visual::before {
  content: '';
  position: absolute;
  inset: 8px;
  border-radius: 15px;
  background-image:
    linear-gradient(rgba(104, 132, 175, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(104, 132, 175, 0.12) 1px, transparent 1px);
  background-size: 11px 11px;
  mask-image: radial-gradient(circle, #000 35%, transparent 76%);
}

.feedback-ring {
  position: absolute;
  inset: 11px;
  border-radius: 999px;
  border: 1px solid rgba(47, 128, 255, 0.18);
}

.feedback-ring::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  border: 2px solid transparent;
  border-top-color: var(--accent);
  border-right-color: rgba(44, 192, 210, 0.82);
  animation: feedbackSpin 1.25s linear infinite;
}

.feedback-bracket {
  position: relative;
  z-index: 1;
  color: rgba(13, 27, 42, 0.74);
  font-family: Consolas, 'SFMono-Regular', 'Liberation Mono', monospace;
  font-size: 14px;
  font-weight: 800;
}

.feedback-line {
  position: absolute;
  z-index: 1;
  left: 17px;
  right: 17px;
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    rgba(47, 128, 255, 0.2),
    rgba(47, 128, 255, 0.86),
    rgba(44, 192, 210, 0.48)
  );
  transform-origin: left center;
  animation: feedbackLine 1.3s ease-in-out infinite;
}

.feedback-line-one {
  top: 17px;
}

.feedback-line-two {
  top: 26px;
  right: 23px;
  animation-delay: 0.12s;
}

.feedback-line-three {
  top: 35px;
  right: 20px;
  animation-delay: 0.24s;
}

.composer-feedback-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.composer-feedback-copy strong {
  color: var(--strong-text);
  font-size: 15px;
  line-height: 1.35;
}

.composer-feedback-copy span {
  color: var(--soft-text);
  font-size: 12px;
  line-height: 1.65;
}

.composer-feedback-rail {
  grid-column: 1 / -1;
  height: 3px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(104, 132, 175, 0.12);
}

.composer-feedback-rail span {
  display: block;
  width: 44%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(
    90deg,
    rgba(47, 128, 255, 0.12),
    rgba(47, 128, 255, 0.86),
    rgba(44, 192, 210, 0.58)
  );
  animation: feedbackRail 1.42s ease-in-out infinite;
}

.composer-feedback-enter-active,
.composer-feedback-leave-active {
  transition:
    opacity 0.22s ease,
    transform 0.22s ease;
}

.composer-feedback-enter-from,
.composer-feedback-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}

:deep(.optimize-button.ant-btn) {
  height: 46px;
  border-radius: 999px;
  padding-inline: 18px;
  color: var(--accent-strong);
  border-color: rgba(47, 128, 255, 0.2);
  background: rgba(47, 128, 255, 0.08);
  font-weight: 600;
}

:deep(.optimize-button.ant-btn:hover),
:deep(.optimize-button.ant-btn:focus) {
  color: var(--accent-strong);
  border-color: rgba(47, 128, 255, 0.34);
  background: rgba(47, 128, 255, 0.12);
}

.create-button {
  flex: 0 0 auto;
}

.template-section {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 44px;
}

.template-card {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  padding: 20px;
  cursor: pointer;
  transition:
    box-shadow 0.28s ease,
    border-color 0.28s ease;
}

.template-card::after {
  content: '';
  position: absolute;
  inset: auto 18px 14px auto;
  width: 88px;
  height: 88px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(47, 128, 255, 0.12), transparent 68%);
  opacity: 0;
  transform: scale(0.8);
  transition:
    opacity 0.28s ease,
    transform 0.28s ease;
  pointer-events: none;
}

.template-card:hover {
  border-color: rgba(47, 128, 255, 0.26);
  box-shadow: 0 26px 54px rgba(114, 137, 170, 0.16);
}

.template-card:hover::after {
  opacity: 1;
  transform: scale(1);
}

.template-card span {
  position: relative;
  z-index: 1;
  display: block;
  color: var(--accent);
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.template-card strong {
  position: relative;
  z-index: 1;
  display: block;
  margin-top: 12px;
  color: var(--strong-text);
  font-size: 18px;
}

.template-summary {
  position: relative;
  z-index: 1;
  margin: 12px 0 0;
  color: var(--soft-text);
  font-size: 14px;
  line-height: 1.75;
}

.section {
  border-radius: 32px;
  padding: 30px;
  margin-bottom: 34px;
}

.section-head {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
  margin-bottom: 26px;
}

.section-title {
  margin: 0;
  color: var(--strong-text);
  font-size: 32px;
  line-height: 1.2;
}

.section-head p {
  max-width: 360px;
  margin: 0;
  color: var(--soft-text);
  line-height: 1.8;
  text-align: right;
}

.workspace-grid {
  position: relative;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 22px;
}

.workspace-card {
  border-radius: 24px;
  padding: 8px;
  background: rgba(255, 255, 255, 0.62);
  box-shadow:
    0 20px 48px rgba(114, 137, 170, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.84);
}

.workspace-glow {
  border-radius: 20px;
  padding: 1px;
  background: rgba(104, 132, 175, 0.14);
}

.workspace-direction-card {
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: 248px;
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.96), rgba(234, 241, 249, 0.92)), #f5f8fc;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.72);
}

:deep(.workspace-direction-card .direction-aware-overlay) {
  background:
    linear-gradient(
      180deg,
      rgba(5, 17, 31, 0.08) 0%,
      rgba(5, 17, 31, 0.56) 48%,
      rgba(5, 17, 31, 0.84) 100%
    ),
    rgba(8, 19, 32, 0.2);
  backdrop-filter: blur(2px);
}

:deep(.workspace-direction-image) {
  object-fit: contain;
  transform: scale(1);
  padding: 12px;
}

:deep(.workspace-direction-content) {
  left: 14px;
  right: 14px;
  bottom: 14px;
}

.workspace-hover-copy {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(13, 27, 42, 0.36), rgba(13, 27, 42, 0.58)), rgba(13, 27, 42, 0.42);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(14px);
  text-shadow: 0 8px 24px rgba(0, 0, 0, 0.44);
}

.workspace-hover-copy span {
  width: fit-content;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  color: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  font-weight: 700;
}

.workspace-hover-copy strong {
  color: #ffffff;
  font-size: 21px;
  line-height: 1.16;
}

.workspace-hover-copy p {
  margin: 0;
  color: rgba(255, 255, 255, 0.78);
}

.workspace-card-actions {
  display: flex;
  gap: 10px;
  padding-top: 6px;
  flex-wrap: wrap;
}

.workspace-card {
  will-change: transform, opacity, filter;
}

.workspace-card.is-busy {
  pointer-events: none;
}

.workspace-card.is-retiring {
  filter: saturate(0.82);
  transform: scale(0.98) translateY(8px);
  opacity: 0.18;
}

.workspace-card-flow-move,
.workspace-card-flow-enter-active,
.workspace-card-flow-leave-active {
  transition:
    opacity 0.34s ease,
    transform 0.34s ease,
    filter 0.34s ease;
}

.workspace-card-flow-enter-from,
.workspace-card-flow-leave-to {
  opacity: 0;
  transform: translateY(18px) scale(0.96);
  filter: blur(4px);
}

.workspace-card-flow-leave-active {
  position: absolute;
}

.workspace-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.workspace-action {
  height: 42px;
  padding: 0 16px;
  border: 1px solid rgba(104, 132, 175, 0.16);
  border-radius: 999px;
  color: var(--strong-text);
  background: rgba(255, 255, 255, 0.82);
  font-weight: 700;
  cursor: pointer;
  transition:
    transform 0.22s ease,
    border-color 0.22s ease,
    background 0.22s ease;
}

.workspace-hover-copy .workspace-action {
  height: 38px;
  color: #ffffff;
  border-color: rgba(255, 255, 255, 0.24);
  background: rgba(255, 255, 255, 0.14);
}

.workspace-action:hover:not(:disabled) {
  transform: translateY(-1px);
}

.workspace-action:disabled {
  cursor: not-allowed;
  opacity: 0.52;
}

.workspace-action--primary {
  border-color: rgba(47, 128, 255, 0.18);
  color: #ffffff;
  background: linear-gradient(135deg, #2a73ff 0%, #58b6ff 100%);
}

.workspace-hover-copy .workspace-action--primary {
  border-color: rgba(114, 176, 255, 0.42);
  background: linear-gradient(135deg, #2a73ff 0%, #4ab5ff 100%);
}

.workspace-action--danger {
  color: #b42318;
  border-color: rgba(244, 63, 94, 0.22);
  background: rgba(254, 226, 226, 0.82);
}

.workspace-hover-copy .workspace-action--danger {
  color: #fff1f2;
  border-color: rgba(255, 255, 255, 0.22);
  background: rgba(244, 63, 94, 0.28);
}

.workspace-hover-copy .workspace-action--danger:hover:not(:disabled) {
  background: rgba(244, 63, 94, 0.4);
}

.delete-modal-layer {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 50% 42%, rgba(255, 255, 255, 0.28), transparent 24%),
    rgba(8, 16, 28, 0.46);
  backdrop-filter: blur(18px);
}

.delete-modal-card {
  width: min(560px, 100%);
  border-radius: 28px;
}

.delete-modal-glow {
  border-radius: inherit;
}

.delete-modal-inner {
  position: relative;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.62);
  border-radius: inherit;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgba(247, 251, 255, 0.9)), #ffffff;
  box-shadow:
    0 34px 92px rgba(15, 23, 42, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.delete-modal-inner::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(244, 63, 94, 0.12), transparent 34%),
    radial-gradient(circle at 86% 12%, rgba(47, 128, 255, 0.14), transparent 28%);
  pointer-events: none;
}

.delete-modal-visual {
  position: relative;
  height: 150px;
  overflow: hidden;
  background:
    linear-gradient(135deg, rgba(255, 241, 242, 0.92), rgba(239, 248, 255, 0.84)), #f8fbff;
}

.delete-bubbles {
  position: absolute;
  inset: -28px -12px;
  opacity: 0.72;
}

.delete-orbit {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 116px;
  height: 116px;
  transform: translate(-50%, -50%);
  border: 1px solid rgba(244, 63, 94, 0.22);
  border-radius: 999px;
  animation: deleteOrbit 7s linear infinite;
}

.delete-orbit span {
  position: absolute;
  width: 14px;
  height: 14px;
  border-radius: 999px;
  background: #fb7185;
  box-shadow: 0 0 26px rgba(244, 63, 94, 0.5);
}

.delete-orbit span:first-child {
  left: 11px;
  top: 11px;
}

.delete-orbit span:last-child {
  right: 8px;
  bottom: 16px;
  width: 10px;
  height: 10px;
  background: #38bdf8;
  box-shadow: 0 0 24px rgba(56, 189, 248, 0.48);
}

.delete-symbol {
  position: absolute;
  left: 50%;
  top: 50%;
  display: grid;
  width: 68px;
  height: 68px;
  place-items: center;
  transform: translate(-50%, -50%);
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 22px;
  color: #fff;
  font-size: 34px;
  font-weight: 900;
  background: linear-gradient(135deg, #e11d48 0%, #fb7185 100%);
  box-shadow:
    0 18px 36px rgba(225, 29, 72, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.34);
}

.phase-done .delete-symbol {
  background: linear-gradient(135deg, #059669 0%, #2dd4bf 100%);
  box-shadow:
    0 18px 36px rgba(5, 150, 105, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.34);
}

.delete-modal-copy {
  position: relative;
  display: grid;
  gap: 12px;
  padding: 26px 28px 0;
}

.delete-kicker {
  color: #e11d48;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0.14em;
}

.delete-modal-copy h3 {
  margin: 0;
  color: var(--strong-text);
  font-size: 28px;
  line-height: 1.18;
}

.delete-modal-copy p {
  margin: 0;
  color: var(--soft-text);
  font-size: 15px;
  line-height: 1.72;
}

.delete-target {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 12px;
  border: 1px solid rgba(104, 132, 175, 0.16);
  border-radius: 20px;
  background: rgba(247, 251, 255, 0.82);
}

.delete-target img {
  width: 76px;
  height: 58px;
  border-radius: 14px;
  object-fit: cover;
  background: #edf4fb;
}

.delete-target div {
  min-width: 0;
}

.delete-target strong {
  display: block;
  overflow: hidden;
  color: var(--strong-text);
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.delete-target span {
  display: -webkit-box;
  margin-top: 5px;
  overflow: hidden;
  color: var(--muted-text);
  font-size: 13px;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.delete-progress {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(226, 232, 240, 0.88);
}

.delete-progress span {
  display: block;
  width: 42%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #fb7185, #38bdf8);
  animation: deleteProgress 1.08s ease-in-out infinite;
}

.delete-progress.done span {
  width: 100%;
  animation: none;
  background: linear-gradient(90deg, #059669, #2dd4bf);
}

.delete-modal-actions {
  position: relative;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 24px 28px 28px;
}

.delete-modal-button {
  min-width: 116px;
  height: 44px;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 800;
}

.delete-modal-button--ghost {
  border: 1px solid rgba(104, 132, 175, 0.18);
  color: var(--strong-text);
  background: rgba(255, 255, 255, 0.74);
  cursor: pointer;
  transition:
    transform 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease;
}

.delete-modal-button--ghost:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: rgba(47, 128, 255, 0.24);
  background: #ffffff;
}

.delete-modal-button--ghost:disabled {
  cursor: not-allowed;
  opacity: 0.52;
}

.delete-modal-button--danger {
  --button-start: #e11d48;
  --button-end: #fb7185;
  --button-shadow: rgba(225, 29, 72, 0.28);
  height: 44px;
  min-width: 128px;
}

.delete-modal-enter-active,
.delete-modal-leave-active {
  transition:
    opacity 0.24s ease,
    backdrop-filter 0.24s ease;
}

.delete-modal-enter-from,
.delete-modal-leave-to {
  opacity: 0;
  backdrop-filter: blur(0);
}

.delete-modal-enter-active .delete-modal-card,
.delete-modal-leave-active .delete-modal-card {
  transition:
    transform 0.28s cubic-bezier(0.2, 0.8, 0.2, 1),
    opacity 0.28s ease;
}

.delete-modal-enter-from .delete-modal-card,
.delete-modal-leave-to .delete-modal-card {
  opacity: 0;
  transform: translateY(18px) scale(0.96);
}

.featured-carousel-wrap {
  position: relative;
}

.featured-modal-body {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(260px, 0.9fr);
  gap: 24px;
  align-items: stretch;
  overflow: hidden;
  border-radius: 26px;
  transition:
    filter 0.28s ease,
    transform 0.28s ease;
}

.featured-modal-body.is-copying {
  filter: saturate(0.96);
}

.featured-modal-lens {
  width: 100%;
  min-height: 340px;
  height: 100%;
  border-radius: 24px;
  background: #edf4fb;
  box-shadow:
    0 18px 48px rgba(15, 23, 42, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.76);
}

.featured-modal-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.featured-modal-copy {
  padding: 8px 0;
}

.featured-modal-copy span {
  color: var(--accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.featured-modal-copy p {
  margin: 18px 0 0;
  color: var(--soft-text);
  font-size: 16px;
  line-height: 1.9;
}

.featured-modal-actions {
  display: flex;
  gap: 10px;
  margin-top: 26px;
}

.copy-case-action {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 104px;
  overflow: hidden;
}

.copy-case-action::before {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  background:
    linear-gradient(120deg, transparent 6%, rgba(255, 255, 255, 0.72) 38%, transparent 68%),
    linear-gradient(135deg, rgba(47, 128, 255, 0.14), rgba(44, 192, 210, 0.12));
  opacity: 0;
  transform: translateX(-115%);
  pointer-events: none;
}

.copy-case-action > span {
  position: relative;
  z-index: 1;
}

.copy-case-action.is-loading {
  color: var(--accent-strong);
  border-color: rgba(47, 128, 255, 0.32);
  background: rgba(255, 255, 255, 0.94);
  opacity: 1;
  box-shadow:
    0 16px 34px rgba(47, 128, 255, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.copy-case-action.is-loading::before {
  opacity: 1;
  animation: copyButtonSweep 1.48s ease-in-out infinite;
}

.copy-action-orbit {
  position: relative;
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  border: 1px solid rgba(47, 128, 255, 0.26);
  border-radius: 999px;
  animation: copyOrbitSpin 1s linear infinite;
}

.copy-action-orbit i {
  position: absolute;
  display: block;
  top: -2px;
  left: 50%;
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: linear-gradient(135deg, #2f80ff, #2cc0d2);
  box-shadow: 0 0 12px rgba(47, 128, 255, 0.58);
  transform: translateX(-50%);
}

.copy-stage-overlay {
  position: absolute;
  inset: 0;
  z-index: 8;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    linear-gradient(135deg, rgba(247, 251, 255, 0.48), rgba(235, 245, 255, 0.36)),
    rgba(13, 27, 42, 0.16);
  backdrop-filter: blur(12px) saturate(1.08);
}

.copy-stage-card {
  width: min(360px, 100%);
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 24px;
  background:
    radial-gradient(circle at 12% 8%, rgba(47, 128, 255, 0.14), transparent 42%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(247, 251, 255, 0.88));
  box-shadow:
    0 26px 68px rgba(15, 23, 42, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.copy-stage-visual {
  position: relative;
  width: 62px;
  height: 62px;
  display: grid;
  place-items: center;
  border-radius: 22px;
  overflow: hidden;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgba(230, 241, 255, 0.88)),
    radial-gradient(circle at 78% 18%, rgba(44, 192, 210, 0.28), transparent 44%);
  border: 1px solid rgba(104, 132, 175, 0.16);
}

.copy-stage-visual::before {
  content: '';
  position: absolute;
  inset: 9px;
  border-radius: 18px;
  background-image:
    linear-gradient(rgba(47, 128, 255, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 128, 255, 0.12) 1px, transparent 1px);
  background-size: 10px 10px;
  mask-image: radial-gradient(circle, #000 44%, transparent 78%);
}

.copy-stage-ring {
  position: absolute;
  inset: 12px;
  border-radius: 999px;
  border: 1px solid rgba(47, 128, 255, 0.18);
}

.copy-stage-ring::after {
  content: '';
  position: absolute;
  inset: -2px;
  border-radius: inherit;
  border: 2px solid transparent;
  border-top-color: #2f80ff;
  border-right-color: rgba(44, 192, 210, 0.86);
  animation: copyOrbitSpin 1.05s linear infinite;
}

.copy-stage-core {
  position: relative;
  z-index: 1;
  width: 14px;
  height: 14px;
  border-radius: 6px;
  background: linear-gradient(135deg, #2f80ff, #2cc0d2);
  box-shadow:
    0 0 18px rgba(47, 128, 255, 0.46),
    inset 0 1px 0 rgba(255, 255, 255, 0.42);
  animation: copyCoreFloat 1.24s ease-in-out infinite;
}

.copy-stage-pulse {
  position: absolute;
  inset: 18px;
  border: 1px solid rgba(47, 128, 255, 0.18);
  border-radius: 999px;
  animation: copyPulse 1.6s ease-out infinite;
}

.copy-stage-pulse-two {
  animation-delay: 0.42s;
}

.copy-stage-copy {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.copy-stage-copy strong {
  color: var(--strong-text);
  font-size: 16px;
  line-height: 1.35;
}

.copy-stage-copy span {
  color: var(--soft-text);
  font-size: 13px;
  line-height: 1.6;
}

.copy-stage-progress {
  grid-column: 1 / -1;
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(104, 132, 175, 0.12);
}

.copy-stage-progress span {
  display: block;
  width: 40%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(
    90deg,
    rgba(47, 128, 255, 0.14),
    rgba(47, 128, 255, 0.9),
    rgba(44, 192, 210, 0.62)
  );
  animation: copyProgressTravel 1.18s cubic-bezier(0.48, 0.02, 0.34, 1) infinite;
}

.copy-stage-enter-active,
.copy-stage-leave-active {
  transition:
    opacity 0.24s ease,
    backdrop-filter 0.24s ease;
}

.copy-stage-enter-from,
.copy-stage-leave-to {
  opacity: 0;
  backdrop-filter: blur(0) saturate(1);
}

.copy-stage-enter-active .copy-stage-card,
.copy-stage-leave-active .copy-stage-card {
  transition:
    opacity 0.28s ease,
    transform 0.28s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.copy-stage-enter-from .copy-stage-card,
.copy-stage-leave-to .copy-stage-card {
  opacity: 0;
  transform: translateY(10px) scale(0.97);
}

.empty-panel {
  border-radius: 24px;
  padding: 34px;
  text-align: center;
}

.empty-panel strong {
  display: block;
  color: var(--strong-text);
  font-size: 18px;
}

.empty-panel span {
  display: block;
  margin-top: 8px;
  color: var(--muted-text);
}

.pagination-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}

@keyframes gridDrift {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(8px, 10px, 0);
  }
}

@keyframes floatGlow {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(0, -12px, 0);
  }
}

@keyframes inputLightSweep {
  0%,
  100% {
    opacity: 0.52;
    transform: scaleX(0.52);
  }

  50% {
    opacity: 1;
    transform: scaleX(1);
  }
}

@keyframes feedbackSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes feedbackLine {
  0%,
  100% {
    opacity: 0.36;
    transform: scaleX(0.48);
  }

  48% {
    opacity: 1;
    transform: scaleX(1);
  }
}

@keyframes feedbackRail {
  0% {
    transform: translateX(-112%);
  }

  100% {
    transform: translateX(260%);
  }
}

@keyframes deleteOrbit {
  0% {
    transform: translate(-50%, -50%) rotate(0deg);
  }

  100% {
    transform: translate(-50%, -50%) rotate(360deg);
  }
}

@keyframes deleteProgress {
  0% {
    transform: translateX(-110%);
  }

  55% {
    transform: translateX(72%);
  }

  100% {
    transform: translateX(240%);
  }
}

@keyframes copyButtonSweep {
  0% {
    transform: translateX(-115%);
  }

  58%,
  100% {
    transform: translateX(115%);
  }
}

@keyframes copyOrbitSpin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes copyCoreFloat {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }

  50% {
    transform: translateY(-2px) scale(1.08);
  }
}

@keyframes copyPulse {
  0% {
    opacity: 0.72;
    transform: scale(0.58);
  }

  100% {
    opacity: 0;
    transform: scale(1.42);
  }
}

@keyframes copyProgressTravel {
  0% {
    transform: translateX(-118%);
  }

  54% {
    transform: translateX(78%);
  }

  100% {
    transform: translateX(260%);
  }
}

@media (max-width: 1024px) {
  .hero-section {
    grid-template-columns: 1fr;
  }

  .template-section {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .home-shell {
    width: min(100% - 32px, 1240px);
    padding: 28px 0 48px;
  }

  .hero-copy,
  .composer-panel,
  .section {
    padding: 22px;
    border-radius: 24px;
  }

  .hero-title {
    font-size: 36px;
  }

  .hero-description {
    font-size: 15px;
  }

  .hero-metrics,
  .section-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .section-head p {
    text-align: left;
  }

  .template-section,
  .workspace-grid {
    grid-template-columns: 1fr;
  }

  .workspace-actions {
    flex-direction: column;
  }

  .workspace-card-actions,
  .featured-modal-actions {
    flex-direction: column;
  }

  .workspace-card-actions {
    width: 100%;
  }

  .workspace-card-actions .workspace-action {
    width: 100%;
  }

  .featured-modal-body {
    grid-template-columns: 1fr;
  }

  .input-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .input-tools {
    width: 100%;
  }

  .composer-feedback {
    grid-template-columns: 1fr;
  }

  .composer-feedback-visual {
    display: none;
  }

  :deep(.optimize-button.ant-btn),
  :deep(.create-button.ant-btn) {
    flex: 1;
    min-width: 0;
  }

  .delete-modal-layer {
    padding: 16px;
  }

  .delete-modal-card {
    border-radius: 24px;
  }

  .delete-modal-visual {
    height: 128px;
  }

  .delete-modal-copy {
    padding: 22px 20px 0;
  }

  .delete-modal-copy h3 {
    font-size: 24px;
  }

  .delete-target {
    grid-template-columns: 64px minmax(0, 1fr);
  }

  .delete-target img {
    width: 64px;
    height: 52px;
  }

  .delete-modal-actions {
    display: grid;
    grid-template-columns: 1fr;
    padding: 22px 20px 22px;
  }

  .delete-modal-button,
  .delete-modal-button--danger {
    width: 100%;
  }
}
</style>
