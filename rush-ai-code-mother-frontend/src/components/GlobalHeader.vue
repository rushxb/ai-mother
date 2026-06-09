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
          <MorphingTabs
            v-model:activeTab="activeTab"
            class="site-tabs"
            :tabs="navLabels"
            :margin="10"
            :blur-std-deviation="5"
            @change="handleTabChange"
          />
        </div>
      </nav>

      <div class="account-area">
        <a-dropdown v-if="loginUserStore.loginUser.id" trigger="click" placement="bottomRight">
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
  </Motion>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LogoutOutlined } from '@ant-design/icons-vue'
import { Motion } from 'motion-v'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { userLogout } from '@/api/userController.ts'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

interface NavItem {
  label: string
  path: string
}

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()

const loginUserAvatar = computed(() => loginUserStore.loginUser.userAvatar || DEFAULT_USER_AVATAR)
const activeTab = ref('主页')

const navItems = computed<NavItem[]>(() => {
  const items: NavItem[] = [{ label: '主页', path: '/' }]

  if (loginUserStore.loginUser?.userRole === 'admin') {
    items.push(
      { label: '用户管理', path: '/admin/userManage' },
      { label: '应用管理', path: '/admin/appManage' },
      { label: '模型管理', path: '/admin/modelManage' },
      { label: '生成耗时', path: '/admin/generationPerformance' },
    )
  }

  return items
})

const navLabels = computed(() => navItems.value.map((item) => item.label))

const getActiveLabel = (path: string) => {
  const matchedItem = navItems.value.find((item) =>
    item.path === '/' ? path === '/' : path.startsWith(item.path),
  )
  return matchedItem?.label ?? '主页'
}

watch(
  [() => route.path, navItems],
  ([path]) => {
    activeTab.value = getActiveLabel(path)
  },
  { immediate: true },
)

const handleTabChange = (label: string) => {
  const target = navItems.value.find((item) => item.label === label)
  if (target && target.path !== route.path) {
    router.push(target.path)
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
  max-width: 1480px;
  height: 100%;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(210px, 300px) minmax(0, 1fr) minmax(128px, auto);
  align-items: center;
  gap: 18px;
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
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.98), rgba(236, 244, 255, 0.9));
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
  color: #2f8bff;
  font-size: 10px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.18em;
}

.site-title {
  overflow: hidden;
  color: #102033;
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
  padding: 4px;
  overflow-x: auto;
  border: 1px solid rgba(126, 158, 196, 0.18);
  border-radius: 999px;
  background: rgba(244, 248, 253, 0.92);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.96),
    0 10px 26px rgba(80, 114, 150, 0.08);
  scrollbar-width: none;
}

.tabs-frame::-webkit-scrollbar {
  display: none;
}

.site-tabs {
  width: max-content;
  max-width: none;
}

.account-area {
  min-width: 0;
  display: flex;
  justify-content: flex-end;
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
  color: #102033;
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
  color: #102033;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.credit-badge {
  color: #2f8bff;
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
  border-radius: 14px;
  overflow: hidden;
}

@media (max-width: 980px) {
  .header {
    height: auto;
    padding: 10px 16px 12px;
  }

  .header-inner {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'brand account'
      'nav nav';
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

  .user-trigger {
    width: 38px;
    height: 38px;
    padding: 0;
    justify-content: center;
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

  .tabs-frame {
    padding: 3px;
  }

  .site-tabs :deep(.morphing-tab) {
    height: 38px;
    padding: 0 14px;
    font-size: 12px;
  }
}
</style>
