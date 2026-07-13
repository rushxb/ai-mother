<template>
  <a-layout
    class="basic-layout"
    :class="{ 'auth-page-layout': isAuthPage, 'chat-page-layout': isChatPage }"
  >
    <!-- 顶部导航栏 -->
    <GlobalHeader v-if="showHeader" />
    <!-- 主要内容区域 -->
    <a-layout-content class="main-content">
      <router-view v-slot="{ Component }">
        <Transition :name="transitionName" mode="out-in">
          <component :is="Component" />
        </Transition>
      </router-view>
    </a-layout-content>
    <!-- 底部版权信息 -->
    <GlobalFooter v-if="!isAuthPage && !isChatPage" />
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isAuthPage = computed(
  () =>
    route.path === '/user/login' ||
    route.path === '/user/register' ||
    route.path === '/user/success',
)
const isChatPage = computed(() => route.path.startsWith('/app/chat/'))
const showHeader = computed(() => !isChatPage.value && route.path !== '/user/success')

const transitionName = computed(() => {
  if (isChatPage.value) return 'route-chat'
  if (isAuthPage.value) return 'route-fade'
  return 'route-slide'
})
</script>

<style scoped>
.basic-layout {
  background:
    radial-gradient(circle at 12% 0%, rgba(47, 139, 255, 0.055), transparent 28%),
    var(--color-surface-soft);
  min-height: 100vh;
}

.basic-layout.chat-page-layout {
  height: 100vh;
  min-height: 0;
  overflow: hidden;
}

.basic-layout.auth-page-layout {
  height: 100vh;
  min-height: 0;
  overflow: hidden;
}

.main-content {
  flex: 1;
  width: 100%;
  padding: 0;
  background: transparent;
  margin: 0;
  min-height: 0;
}

.chat-page-layout .main-content {
  overflow: hidden;
}

.auth-page-layout .main-content {
  overflow: hidden;
}

/* Page transitions */
.route-slide-enter-active,
.route-slide-leave-active,
.route-fade-enter-active,
.route-fade-leave-active,
.route-chat-enter-active,
.route-chat-leave-active {
  transition:
    opacity 0.28s var(--ease-out),
    transform 0.34s var(--ease-out),
    filter 0.34s var(--ease-out);
}

.route-slide-enter-from {
  opacity: 0;
  transform: translateY(10px);
  filter: blur(3px);
}

.route-slide-leave-to {
  opacity: 0;
  transform: translateY(-6px);
  filter: blur(2px);
}

.route-fade-enter-from {
  opacity: 0;
  filter: blur(4px);
}

.route-fade-leave-to {
  opacity: 0;
  filter: blur(3px);
}

.route-chat-enter-from {
  opacity: 0;
}

.route-chat-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .route-slide-enter-active,
  .route-slide-leave-active,
  .route-fade-enter-active,
  .route-fade-leave-active,
  .route-chat-enter-active,
  .route-chat-leave-active {
    transition: opacity 0.01ms linear;
  }

  .route-slide-enter-from,
  .route-slide-leave-to,
  .route-fade-enter-from,
  .route-fade-leave-to {
    filter: none;
    transform: none;
  }
}
</style>
