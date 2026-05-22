<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { addApp, copyApp, listMyAppVoByPage, listGoodAppVoByPage, optimizePrompt } from '@/api/appController'
import { getDeployUrl } from '@/config/env'
import { normalizeImageUrl } from '@/utils/url'
import { DEFAULT_APP_COVER, DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import { BulbOutlined } from '@ant-design/icons-vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const userPrompt = ref('')
const promptInputRef = ref()
const creating = ref(false)
const optimizingPrompt = ref(false)
const animatedPlaceholder =
  '例如：帮我创建一个面向设计师的作品集网站，包含项目展示、个人介绍和联系表单...'
const shouldShowAnimatedPlaceholder = computed(() => !userPrompt.value && !creating.value && !optimizingPrompt.value)

const myApps = ref<API.AppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

const featuredApps = ref<API.AppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

const copyingAppIds = ref<Set<string>>(new Set())

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
      await router.push(`/app/chat/${appId}`)
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
  const prompt = userPrompt.value.trim()
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
    const res = await optimizePrompt({
      prompt,
    })
    if (res.data.code === 0 && res.data.data) {
      userPrompt.value = res.data.data
      message.success('提示词已优化')
      return
    }
    message.error('优化失败：' + (res.data.message || '请重试'))
  } catch (error) {
    console.error('优化提示词失败：', error)
    message.error('优化失败，请重试')
  } finally {
    optimizingPrompt.value = false
  }
}

const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载我的应用失败：', error)
  }
}

const loadFeaturedApps = async () => {
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载精选应用失败：', error)
  }
}

const viewChat = (appId: string | number | undefined) => {
  if (appId) {
    router.push(`/app/chat/${appId}?view=1`)
  }
}

const viewWork = (app: API.AppVO) => {
  if (app.deployKey) {
    const url = getDeployUrl(app.deployKey)
    window.open(url, '_blank')
  }
}

const isOwnApp = (app: API.AppVO) => {
  if (!app.userId || !loginUserStore.loginUser.id) {
    return false
  }
  return String(app.userId) === String(loginUserStore.loginUser.id)
}

const isCopyingApp = (app: API.AppVO) => {
  return Boolean(app.id && copyingAppIds.value.has(String(app.id)))
}

const getAppImage = (app: API.AppVO) => normalizeImageUrl(app.cover) || DEFAULT_APP_COVER

const getAppAuthor = (app: API.AppVO, fallback = '未知用户') => app.user?.userName || fallback

const getAppSummary = (app: API.AppVO, index: number) => {
  const source = (app.initPrompt || '').trim()
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
    summary: getAppSummary(app, index),
    app,
  })),
)

const workspaceCards = computed(() =>
  myApps.value.map((app, index) => ({
    id: String(app.id ?? index),
    title: app.appName || '未命名应用',
    author: getAppAuthor(app),
    summary: getAppSummary(app, index),
    imageUrl: getAppImage(app),
    avatar: app.user?.userAvatar || DEFAULT_USER_AVATAR,
    status: app.isGenerating ? '生成中' : app.deployKey ? '已部署' : '草稿',
    app,
  })),
)

const copyFeaturedApp = async (app: API.AppVO) => {
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
  copyingAppIds.value = new Set(copyingAppIds.value).add(appId)
  try {
    const res = await copyApp({
      sourceAppId: app.id,
    })
    if (res.data.code === 0 && res.data.data) {
      message.success('复制成功，已加入我的作品')
      await loadMyApps()
      await router.push(`/app/chat/${res.data.data}`)
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

const handleMouseMove = (e: MouseEvent) => {
  const { clientX, clientY } = e
  const { innerWidth, innerHeight } = window
  const x = (clientX / innerWidth) * 100
  const y = (clientY / innerHeight) * 100

  document.documentElement.style.setProperty('--mouse-x', `${x}%`)
  document.documentElement.style.setProperty('--mouse-y', `${y}%`)
}

onMounted(() => {
  loadMyApps()
  loadFeaturedApps()
  document.addEventListener('mousemove', handleMouseMove)
})

onUnmounted(() => {
  document.removeEventListener('mousemove', handleMouseMove)
})
</script>

<template>
  <div id="homePage">
    <div class="ambient-grid"></div>
    <div class="ambient-glow glow-one"></div>
    <div class="ambient-glow glow-two"></div>

    <main class="home-shell">
      <section class="hero-section">
        <div class="hero-copy">
          <div class="eyebrow">
            <span class="eyebrow-dot"></span>
            AI APP GENERATION CONSOLE
          </div>
          <h1 class="hero-title">把一句需求，推进成可对话、可部署的应用。</h1>
          <p class="hero-description">
            用自然语言描述目标，系统会为你创建应用骨架并进入对话调试流程，适合快速验证 MVP、内部工具和演示项目。
          </p>
          <div class="hero-metrics">
            <div class="metric-pill">
              <strong>Prompt</strong>
              <span>需求驱动</span>
            </div>
            <div class="metric-pill">
              <strong>Chat</strong>
              <span>持续迭代</span>
            </div>
            <div class="metric-pill">
              <strong>Deploy</strong>
              <span>在线预览</span>
            </div>
          </div>
        </div>

        <div class="composer-panel">
          <div class="composer-head">
            <div>
              <span class="panel-kicker">New Application</span>
              <h2>描述你要生成的应用</h2>
            </div>
            <span class="status-chip">Ready</span>
          </div>
          <div class="input-section">
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
                  开始生成
                </ShimmerButton>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="template-section">
        <div class="template-card" v-for="item in promptTemplates" :key="item.title" @click="setPrompt(item.prompt)">
          <span>{{ item.tag }}</span>
          <strong>{{ item.title }}</strong>
          <p class="template-summary">
            <TextScrollReveal :text="item.summary" :stagger="0.05" :duration="0.48" />
          </p>
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <span class="section-kicker">My Workspace</span>
            <h2 class="section-title">我的作品</h2>
          </div>
          <p>继续编辑最近创建的应用，或进入只读对话查看生成过程。</p>
        </div>
        <div v-if="workspaceCards.length" class="workspace-grid">
          <article v-for="item in workspaceCards" :key="item.id" class="workspace-card">
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
                  <button type="button" class="workspace-action workspace-action--primary" @click="viewChat(item.app.id)">
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
                </div>
              </div>
            </DirectionAwareHover>
          </article>
        </div>
        <div v-else class="empty-panel">
          <strong>还没有作品</strong>
          <span>在上方输入需求并生成第一个应用。</span>
        </div>
        <div v-if="myAppsPage.total > myAppsPage.pageSize" class="pagination-wrapper">
          <a-pagination
            v-model:current="myAppsPage.current"
            v-model:page-size="myAppsPage.pageSize"
            :total="myAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个应用`"
            @change="loadMyApps"
          />
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <div>
            <span class="section-kicker">Featured Gallery</span>
            <h2 class="section-title">精选案例</h2>
          </div>
          <p>参考优秀案例的结构、交互和页面组织方式。</p>
        </div>
        <div v-if="featuredCarouselItems.length" class="featured-carousel-wrap">
          <AppleCardCarousel>
            <AppleCarouselItem v-for="(item, index) in featuredCarouselItems" :key="item.id" :index="index">
              <AppleCard :card="item" :index="index">
                <div class="featured-modal-body">
                  <img :src="item.src" :alt="item.title" />
                  <div class="featured-modal-copy">
                    <span>{{ item.category }}</span>
                    <p>{{ item.summary }}</p>
                    <div class="featured-modal-actions">
                      <button type="button" class="workspace-action workspace-action--primary" @click="viewWork(item.app)">
                        预览
                      </button>
                      <button
                        type="button"
                        class="workspace-action"
                        :disabled="isCopyingApp(item.app)"
                        @click="isOwnApp(item.app) ? viewChat(item.app.id) : copyFeaturedApp(item.app)"
                      >
                        {{ isOwnApp(item.app) ? '查看对话' : '复制案例' }}
                      </button>
                    </div>
                  </div>
                </div>
              </AppleCard>
            </AppleCarouselItem>
          </AppleCardCarousel>
        </div>
        <div v-else class="empty-panel">
          <strong>暂无精选案例</strong>
          <span>精选内容发布后会展示在这里。</span>
        </div>
        <div v-if="featuredAppsPage.total > featuredAppsPage.pageSize" class="pagination-wrapper">
          <a-pagination
            v-model:current="featuredAppsPage.current"
            v-model:page-size="featuredAppsPage.pageSize"
            :total="featuredAppsPage.total"
            :show-size-changer="false"
            :show-total="(total: number) => `共 ${total} 个案例`"
            @change="loadFeaturedApps"
          />
        </div>
      </section>
    </main>
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
  animation: panelReveal 0.62s cubic-bezier(0.2, 0.8, 0.2, 1) both;
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
  animation: panelReveal 0.62s cubic-bezier(0.2, 0.8, 0.2, 1) 0.08s both;
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
}

.input-section {
  position: relative;
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
    transform 0.28s ease,
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
  transform: translateY(-4px);
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
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 22px;
}

.workspace-card {
  border-radius: 24px;
  padding: 8px;
  border: 1px solid rgba(104, 132, 175, 0.14);
  background: rgba(255, 255, 255, 0.62);
  box-shadow:
    0 20px 48px rgba(114, 137, 170, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.84);
}

.workspace-direction-card {
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: 248px;
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.96), rgba(234, 241, 249, 0.92)),
    #f5f8fc;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.72);
}

:deep(.workspace-direction-card .direction-aware-overlay) {
  background:
    linear-gradient(180deg, rgba(5, 17, 31, 0.08) 0%, rgba(5, 17, 31, 0.56) 48%, rgba(5, 17, 31, 0.84) 100%),
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
    linear-gradient(180deg, rgba(13, 27, 42, 0.36), rgba(13, 27, 42, 0.58)),
    rgba(13, 27, 42, 0.42);
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

.featured-carousel-wrap {
  position: relative;
}

.featured-modal-body {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(260px, 0.9fr);
  gap: 24px;
  align-items: stretch;
}

.featured-modal-body img {
  width: 100%;
  min-height: 340px;
  border-radius: 24px;
  object-fit: cover;
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

@keyframes panelReveal {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
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

  :deep(.optimize-button.ant-btn),
  :deep(.create-button.ant-btn) {
    flex: 1;
    min-width: 0;
  }
}
</style>
