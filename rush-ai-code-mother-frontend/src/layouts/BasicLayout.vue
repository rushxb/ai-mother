<template>
  <a-layout
    class="basic-layout"
    :class="{ 'auth-page-layout': isAuthPage, 'chat-page-layout': isChatPage }"
  >
    <!-- 顶部导航栏 -->
    <GlobalHeader v-if="showHeader" />
    <!-- 主要内容区域 -->
    <a-layout-content class="main-content">
      <router-view />
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
const isAuthPage = computed(() =>
  route.path === '/user/login' || route.path === '/user/register' || route.path === '/user/success',
)
const isChatPage = computed(() => route.path.startsWith('/app/chat/'))
const showHeader = computed(() => !isChatPage.value && route.path !== '/user/success')
</script>

<style scoped>
.basic-layout {
  background: #f8fafc;
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
  background: #f8fafc;
  margin: 0;
  min-height: 0;
}

.chat-page-layout .main-content {
  overflow: hidden;
}

.auth-page-layout .main-content {
  overflow: hidden;
}
</style>
