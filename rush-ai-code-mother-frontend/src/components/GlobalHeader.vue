<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <RouterLink to="/" class="brand">
        <img class="logo" src="@/assets/logo.png" alt="Logo" />
        <div class="brand-copy">
          <span class="brand-kicker">RUSH STUDIO</span>
          <div class="brand-text">
            <h1 class="site-title">rush应用生成</h1>
            <p class="site-subtitle">高效构建，流畅交互</p>
          </div>
        </div>
      </RouterLink>

      <div class="nav-shell">
        <MorphingTabs
          v-model:activeTab="activeTab"
          class="site-tabs"
          :tabs="navTabs"
          :margin="10"
          :blur-std-deviation="5"
          @change="handleTabChange"
        />
      </div>

      <div class="user-login-status">
        <a-dropdown v-if="loginUserStore.loginUser.id" trigger="click">
          <button type="button" class="user-trigger">
            <a-avatar :src="loginUserAvatar" size="small" />
            <span class="user-meta">
              <span class="user-name">{{ loginUserStore.loginUser.userName ?? '神秘用户' }}</span>
              <span class="credit-badge">{{ loginUserStore.loginUser.creditBalance ?? 0 }} 积分</span>
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
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LogoutOutlined } from '@ant-design/icons-vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

interface NavTab {
  label: string
  value: string
}

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()

const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)
const activeTab = ref('/')

const getSelectedKey = (path: string) => {
  if (path.startsWith('/admin/userManage')) {
    return '/admin/userManage'
  }
  if (path.startsWith('/admin/appManage')) {
    return '/admin/appManage'
  }
  if (path.startsWith('/admin/modelManage')) {
    return '/admin/modelManage'
  }
  return '/'
}

watch(
  () => route.path,
  (path) => {
    activeTab.value = getSelectedKey(path)
  },
  { immediate: true },
)

const navTabs = computed<NavTab[]>(() => {
  const items: NavTab[] = [{ label: '主页', value: '/' }]

  if (loginUserStore.loginUser?.userRole === 'admin') {
    items.push(
      { label: '用户管理', value: '/admin/userManage' },
      { label: '应用管理', value: '/admin/appManage' },
      { label: '模型管理', value: '/admin/modelManage' },
    )
  }

  return items
})

const handleTabChange = (value: string) => {
  if (value !== route.path) {
    router.push(value)
  }
}

const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 1000;
  height: 78px;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.84);
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  box-shadow: 0 10px 30px rgba(76, 102, 140, 0.06);
  backdrop-filter: saturate(180%) blur(18px);
}

.header-inner {
  height: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
}

.brand {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 14px;
  text-decoration: none;
  color: inherit;
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  box-shadow: 0 10px 24px rgba(47, 139, 255, 0.16);
}

.brand-copy {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.brand-kicker {
  flex: 0 0 auto;
  height: 28px;
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border-radius: 999px;
  background: rgba(47, 139, 255, 0.08);
  color: #2f8bff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.brand-text {
  min-width: 0;
}

.site-title {
  margin: 0;
  color: #102033;
  font-size: 17px;
  line-height: 1.1;
  font-weight: 700;
}

.site-subtitle {
  margin: 3px 0 0;
  color: #7b8da6;
  font-size: 12px;
  line-height: 1.2;
}

.nav-shell {
  min-width: 0;
  display: flex;
  justify-content: center;
}

.site-tabs {
  max-width: 100%;
}

.user-login-status {
  display: flex;
  justify-content: flex-end;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  height: 46px;
  padding: 0 10px 0 8px;
  border: 1px solid rgba(126, 158, 196, 0.14);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  color: #102033;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 10px 26px rgba(76, 102, 140, 0.08);
  transition:
    transform 0.24s ease,
    box-shadow 0.24s ease,
    border-color 0.24s ease;
}

.user-trigger:hover {
  transform: translateY(-1px);
  border-color: rgba(47, 139, 255, 0.18);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.92),
    0 14px 32px rgba(76, 102, 140, 0.12);
}

.user-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.user-name {
  max-width: 124px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
}

.credit-badge {
  display: inline-flex;
  align-items: center;
  color: #2f8bff;
  font-size: 12px;
  line-height: 1;
}

.login-button {
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 20px;
  border-radius: 999px;
  text-decoration: none;
  color: #ffffff;
  font-weight: 600;
  background: linear-gradient(135deg, #2f8bff 0%, #67c8ff 100%);
  box-shadow: 0 14px 30px rgba(47, 139, 255, 0.22);
}

:deep(.user-menu) {
  margin-top: 8px;
  border-radius: 14px;
  overflow: hidden;
}

@media (max-width: 980px) {
  .header {
    height: auto;
    padding: 12px 16px;
  }

  .header-inner {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'brand user'
      'nav nav';
    row-gap: 12px;
  }

  .brand {
    grid-area: brand;
  }

  .nav-shell {
    grid-area: nav;
    justify-content: flex-start;
  }

  .user-login-status {
    grid-area: user;
  }
}

@media (max-width: 680px) {
  .brand-copy {
    gap: 10px;
  }

  .brand-kicker {
    display: none;
  }

  .site-title {
    font-size: 16px;
  }

  .site-subtitle {
    font-size: 11px;
  }

  .user-trigger {
    padding-right: 8px;
  }

  .user-name {
    max-width: 92px;
  }
}
</style>
