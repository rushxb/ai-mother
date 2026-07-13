<template>
  <Motion
    as="a-layout-header"
    class="header"
    :initial="{ opacity: 0, y: -12 }"
    :animate="{ opacity: 1, y: 0 }"
    :transition="{ duration: 0.45, ease: [0.25, 0.46, 0.45, 0.94], delay: 0.05 }"
  >
    <div class="header-inner">
      <RouterLink to="/" class="brand" aria-label="返回主页">
        <span class="logo-frame">
          <img class="logo" src="@/assets/logo.png" alt="Logo" />
        </span>
        <span class="brand-copy">
          <span class="brand-kicker">RUSH STUDIO</span>
          <span class="site-title">rush应用生成</span>
        </span>
      </RouterLink>

      <nav class="nav-shell" aria-label="主导航">
        <div class="tabs-frame">
          <PrimaryNavigationTabs
            class="site-tabs"
            :items="navItems"
            :current-path="route.path"
          />
        </div>
      </nav>

      <div class="command-area">
        <button
          type="button"
          class="command-trigger"
          aria-label="打开工作台命令中心"
          aria-keyshortcuts="Control+K Meta+K"
          @click="openCommandPalette"
        >
          <SearchOutlined />
          <span class="command-trigger-copy">搜索 / 命令</span>
          <kbd>⌘K</kbd>
        </button>
      </div>

      <div class="account-area">
        <a-dropdown v-if="loginUserStore.loginUser.id" trigger="click" placement="bottomRight">
          <button type="button" class="user-trigger">
            <a-avatar :src="loginUserAvatar" size="small" />
            <span class="user-meta">
              <span class="user-name">{{ loginUserStore.loginUser.userName ?? '神秘用户' }}</span>
              <span class="credit-badge"
                >{{ loginUserStore.loginUser.creditBalance ?? 0 }} 积分</span
              >
            </span>
          </button>
          <template #overlay>
            <a-menu class="user-menu">
              <a-menu-item @click="doLogout">
                <LogoutOutlined />
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>

        <RouterLink v-else to="/user/login" class="login-button">登录</RouterLink>
      </div>
    </div>
  </Motion>

  <GlobalCommandPalette
    v-model:open="commandPaletteOpen"
    :items="commandPaletteItems"
    @select="handleCommandSelect"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  AppstoreOutlined,
  BarChartOutlined,
  HomeOutlined,
  LogoutOutlined,
  MessageOutlined,
  RobotOutlined,
  SearchOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { Motion } from 'motion-v'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import GlobalCommandPalette from '@/components/navigation/GlobalCommandPalette.vue'
import PrimaryNavigationTabs from '@/components/navigation/PrimaryNavigationTabs.vue'
import type { CommandPaletteItem } from '@/components/navigation/commandPalette'

interface NavItem {
  label: string
  path: string
  icon?: CommandPaletteItem['icon']
  description: string
  keywords?: string[]
}

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()

const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)
const commandPaletteOpen = ref(false)

const navItems = computed<NavItem[]>(() => {
  const items: NavItem[] = [
    {
      label: '主页',
      path: '/',
      icon: HomeOutlined,
      description: '返回个人应用工作台和灵感案例库',
      keywords: ['home', 'workspace', '首页', '工作台'],
    },
  ]

  if (loginUserStore.loginUser?.userRole === 'admin') {
    items.push(
      {
        label: '用户管理',
        path: '/admin/userManage',
        icon: UserOutlined,
        description: '管理用户、角色和账户状态',
        keywords: ['admin', 'user', '用户'],
      },
      {
        label: '应用管理',
        path: '/admin/appManage',
        icon: AppstoreOutlined,
        description: '查看与治理平台应用资产',
        keywords: ['admin', 'app', '应用'],
      },
      {
        label: '模型管理',
        path: '/admin/modelManage',
        icon: RobotOutlined,
        description: '维护模型供应商、能力和调用配置',
        keywords: ['admin', 'model', '模型', 'ai'],
      },
      {
        label: '生成耗时',
        path: '/admin/generationPerformance',
        icon: BarChartOutlined,
        description: '分析生成链路、阶段耗时和任务状态',
        keywords: ['admin', 'performance', '耗时', '生成'],
      },
    )
  }

  return items
})

const commandPaletteItems = computed<CommandPaletteItem[]>(() => {
  const navigationItems: CommandPaletteItem[] = navItems.value.map((item) => ({
    id: `navigate:${item.path}`,
    group: '页面导航',
    title: item.label,
    description: item.description,
    keywords: item.keywords,
    icon: item.icon,
  }))

  const actionItems: CommandPaletteItem[] = [
    {
      id: 'action:new-app',
      group: '快捷操作',
      title: '新建 AI 应用',
      description: '回到首页并聚焦需求输入框，快速开始生成',
      keywords: ['new', 'create', 'prompt', '生成', '新建'],
      shortcut: 'N',
      icon: AppstoreOutlined,
    },
  ]

  if (loginUserStore.loginUser?.userRole === 'admin') {
    actionItems.push({
      id: 'action:chat-history',
      group: '快捷操作',
      title: '对话记录管理',
      description: '查看应用对话、生成上下文和用户反馈',
      keywords: ['chat', 'history', '对话', '消息'],
      icon: MessageOutlined,
    })
  }

  if (!loginUserStore.loginUser?.id) {
    actionItems.push({
      id: 'navigate:/user/login',
      group: '账户',
      title: '登录工作台',
      description: '进入登录页，开启个人应用生成流程',
      keywords: ['login', 'account', '登录'],
      icon: UserOutlined,
    })
  }

  return [...navigationItems, ...actionItems]
})

const openCommandPalette = () => {
  commandPaletteOpen.value = true
}

const focusHomeComposer = () => {
  window.setTimeout(() => {
    window.dispatchEvent(new CustomEvent('rush:focus-composer'))
  }, 180)
}

const handleCommandSelect = async (item: CommandPaletteItem) => {
  if (item.id === 'action:new-app') {
    if (route.path !== '/') {
      await router.push('/')
    }
    focusHomeComposer()
    return
  }

  if (item.id === 'action:chat-history') {
    await router.push('/admin/chatManage')
    return
  }

  if (item.id.startsWith('navigate:')) {
    const targetPath = item.id.replace('navigate:', '')
    if (targetPath !== route.path) {
      await router.push(targetPath)
    }
  }
}

const handleGlobalShortcut = (event: KeyboardEvent) => {
  const isCommandK = (event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k'
  if (!isCommandK) {
    return
  }
  event.preventDefault()
  commandPaletteOpen.value = !commandPaletteOpen.value
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalShortcut)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalShortcut)
})

const doLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code !== 0) {
      message.error(`退出登录失败：${res.data.message || '服务异常'}`)
      return
    }

    loginUserStore.setLoginUser(null)
    message.success('退出登录成功')
    await router.replace('/user/login')
  } catch (error) {
    console.error('Logout request failed', error)
    message.error('退出登录失败，请检查网络后重试')
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 1000;
  display: block;
  height: 72px;
  padding: 0 24px;
  line-height: normal;
  background: rgba(255, 255, 255, 0.9);
  border-bottom: 1px solid rgba(123, 148, 180, 0.16);
  box-shadow: 0 12px 34px rgba(77, 102, 135, 0.08);
  backdrop-filter: saturate(180%) blur(18px);
}

.header-inner {
  max-width: var(--page-max-width, 1480px);
  height: 100%;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(210px, 300px) minmax(0, 1fr) minmax(174px, auto) minmax(128px, auto);
  align-items: center;
  gap: 14px;
}

.brand {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: inherit;
  text-decoration: none;
}

.logo-frame {
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.98), rgba(236, 244, 255, 0.9));
  border: 1px solid rgba(126, 158, 196, 0.2);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 12px 24px rgba(80, 114, 150, 0.12);
}

.logo {
  width: 32px;
  height: 32px;
  border-radius: 10px;
}

.brand-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.brand-kicker {
  color: var(--color-primary, #2f8bff);
  font-size: 10px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.18em;
}

.site-title {
  overflow: hidden;
  color: var(--color-ink-strong, #102033);
  font-size: 17px;
  font-weight: 750;
  line-height: 1.14;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-shell {
  min-width: 0;
  display: flex;
  justify-content: center;
  overflow: hidden;
}

.tabs-frame {
  max-width: 100%;
  padding: 0 2px;
  overflow-x: auto;
  scrollbar-width: none;
}

.tabs-frame::-webkit-scrollbar {
  display: none;
}

.site-tabs {
  width: max-content;
  max-width: none;
}

.command-area,
.account-area {
  min-width: 0;
  display: flex;
  justify-content: flex-end;
}

.command-trigger {
  height: 42px;
  min-width: 174px;
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 10px 0 14px;
  border: 1px solid rgba(126, 158, 196, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: #60748d;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 10px 24px rgba(80, 114, 150, 0.08);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    border-color 0.22s ease,
    color 0.22s ease;
}

.command-trigger:hover {
  transform: translateY(-1px);
  border-color: rgba(47, 139, 255, 0.28);
  color: var(--color-primary, #2f8bff);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 14px 30px rgba(80, 114, 150, 0.12);
}

.command-trigger-copy {
  flex: 1;
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-trigger kbd {
  min-width: 32px;
  padding: 5px 6px;
  border: 1px solid rgba(126, 158, 196, 0.2);
  border-radius: 8px;
  background: rgba(237, 244, 252, 0.9);
  color: #6f8198;
  font-family: var(--font-ui);
  font-size: 10px;
  line-height: 1;
  text-align: center;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  height: 42px;
  padding: 0 12px 0 8px;
  border: 1px solid rgba(126, 158, 196, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.82);
  color: var(--color-ink-strong, #102033);
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 10px 24px rgba(80, 114, 150, 0.08);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    border-color 0.22s ease;
}

.user-trigger:hover {
  transform: translateY(-1px);
  border-color: rgba(47, 139, 255, 0.28);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 14px 30px rgba(80, 114, 150, 0.12);
}

.user-meta {
  min-width: 0;
  display: grid;
  gap: 2px;
  text-align: left;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  color: var(--color-ink-strong, #102033);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.credit-badge {
  color: var(--color-primary, #2f8bff);
  font-size: 12px;
  line-height: 1;
}

.login-button {
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 22px;
  border-radius: 999px;
  color: #ffffff;
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  background: linear-gradient(135deg, #2f8bff 0%, #64c7ff 100%);
  box-shadow: 0 12px 26px rgba(47, 139, 255, 0.24);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease;
}

.login-button:hover {
  color: #ffffff;
  transform: translateY(-1px);
  box-shadow: 0 16px 34px rgba(47, 139, 255, 0.3);
}

:deep(.user-menu) {
  margin-top: 8px;
  overflow: hidden;
  border-radius: 14px;
}

@media (max-width: 1180px) {
  .header-inner {
    grid-template-columns: minmax(200px, 260px) minmax(0, 1fr) 42px minmax(118px, auto);
  }

  .command-trigger {
    width: 42px;
    min-width: 42px;
    justify-content: center;
    padding: 0;
  }

  .command-trigger-copy,
  .command-trigger kbd {
    display: none;
  }
}

@media (max-width: 980px) {
  .header {
    height: auto;
    padding: 10px 16px 12px;
  }

  .header-inner {
    grid-template-columns: minmax(0, 1fr) auto auto;
    grid-template-areas:
      'brand command account'
      'nav nav nav';
    row-gap: 10px;
  }

  .brand {
    grid-area: brand;
  }

  .nav-shell {
    grid-area: nav;
    justify-content: flex-start;
    width: 100%;
  }

  .command-area {
    grid-area: command;
  }

  .account-area {
    grid-area: account;
  }
}

@media (max-width: 640px) {
  .header {
    padding-inline: 12px;
  }

  .logo-frame {
    width: 38px;
    height: 38px;
    border-radius: 12px;
  }

  .logo {
    width: 29px;
    height: 29px;
  }

  .brand-kicker {
    display: none;
  }

  .site-title {
    font-size: 16px;
  }

  .command-trigger,
  .user-trigger {
    width: 38px;
    min-width: 38px;
    height: 38px;
    justify-content: center;
    padding: 0;
  }

  .user-meta {
    display: none;
  }

  .login-button {
    height: 38px;
    padding: 0 16px;
  }

  .site-tabs {
    width: max-content;
  }
}
</style>
