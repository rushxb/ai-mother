<template>
  <a-layout-header class="header">
    <div class="header-inner">
      <RouterLink to="/" class="brand">
        <img class="logo" src="@/assets/logo.png" alt="Logo" />
        <div class="brand-text">
          <h1 class="site-title">rush应用生成</h1>
          <p class="site-subtitle">高效构建，流畅交互</p>
        </div>
      </RouterLink>

      <a-menu
        v-model:selectedKeys="selectedKeys"
        class="site-menu"
        mode="horizontal"
        :items="menuItems"
        @click="handleMenuClick"
      />

      <div class="user-login-status">
        <a-dropdown v-if="loginUserStore.loginUser.id" trigger="click">
          <a-button class="user-trigger" type="text">
            <a-avatar :src="loginUserAvatar" size="small" />
            <span class="user-name">{{ loginUserStore.loginUser.userName ?? '神秘用户' }}</span>
          </a-button>
          <template #overlay>
            <a-menu class="user-menu">
              <a-menu-item @click="doLogout">
                <LogoutOutlined />
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>

        <a-button v-else class="login-button" href="/user/login">登录</a-button>
      </div>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type MenuProps, message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import { LogoutOutlined, HomeOutlined } from '@ant-design/icons-vue'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()
const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)
// 当前选中菜单
const selectedKeys = ref<string[]>(['/'])

const getSelectedKey = (path: string) => {
  if (path.startsWith('/admin/userManage')) {
    return '/admin/userManage'
  }
  if (path.startsWith('/admin/appManage')) {
    return '/admin/appManage'
  }
  return path
}

watch(
  () => route.path,
  (path) => {
    selectedKeys.value = [getSelectedKey(path)]
  },
  { immediate: true },
)

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/appManage',
    label: '应用管理',
    title: '应用管理',
  },

]

// 过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const menuKey = menu?.key as string
    if (menuKey?.startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  selectedKeys.value = [key]
  // 跳转到对应页面
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 退出登录
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
  height: 72px;
  background: #fff;
  padding: 0 20px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.04);
  backdrop-filter: saturate(180%) blur(16px);
}

.header-inner {
  height: 100%;
  display: flex;
  align-items: center;
  gap: 20px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
  text-decoration: none;
  color: inherit;
}

.brand-text {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}

.logo {
  height: 40px;
  width: 40px;
  border-radius: 12px;
  box-shadow: 0 6px 18px rgba(24, 144, 255, 0.18);
}

.site-title {
  margin: 0;
  font-size: 17px;
  line-height: 1.1;
  color: #0f172a;
  font-weight: 700;
}

.site-subtitle {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.2;
  color: #94a3b8;
}

.site-menu {
  flex: 1 1 auto;
  min-width: 0;
  border-bottom: 0;
  justify-content: center;
  background: transparent;
}

:deep(.site-menu.ant-menu-horizontal) {
  border-bottom: 0;
}

:deep(.site-menu .ant-menu-item) {
  margin-inline: 4px;
  padding-inline: 18px;
  border-radius: 999px;
  transition:
    background-color 0.25s ease,
    color 0.25s ease,
    transform 0.25s ease,
    box-shadow 0.25s ease;
}

:deep(.site-menu .ant-menu-item:hover) {
  background: rgba(24, 144, 255, 0.08);
  color: #1677ff;
  transform: translateY(-1px);
}

:deep(.site-menu .ant-menu-item-selected) {
  background: linear-gradient(180deg, rgba(24, 144, 255, 0.12), rgba(24, 144, 255, 0.08));
  color: #1677ff;
  box-shadow: inset 0 0 0 1px rgba(24, 144, 255, 0.12);
}

:deep(.site-menu .ant-menu-title-content) {
  transition: color 0.25s ease;
}

.user-login-status {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
}

.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 12px;
  border-radius: 999px;
  color: #0f172a;
  transition: background-color 0.25s ease, box-shadow 0.25s ease, transform 0.25s ease;
}

.user-trigger:hover {
  background: rgba(15, 23, 42, 0.05);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
  transform: translateY(-1px);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.login-button {
  height: 40px;
  border-radius: 999px;
  padding: 0 18px;
  box-shadow: 0 8px 24px rgba(24, 144, 255, 0.14);
}

:deep(.user-menu) {
  margin-top: 8px;
  border-radius: 14px;
  overflow: hidden;
}

@media (max-width: 768px) {
  .header {
    height: auto;
    padding: 12px 16px;
  }

  .header-inner {
    flex-wrap: wrap;
    gap: 12px;
  }

  .site-menu {
    order: 3;
    width: 100%;
    justify-content: flex-start;
  }

  .brand {
    min-width: 0;
  }
}
</style>
