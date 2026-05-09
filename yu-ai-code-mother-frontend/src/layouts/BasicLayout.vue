<template>
  <a-layout class="basic-layout" :class="{ 'fullscreen-layout': isFullscreenPage }">
    <!-- 顶部导航栏 -->
    <GlobalHeader />
    <!-- 主要内容区域 -->
    <a-layout-content class="main-content" :class="{ 'fullscreen-content': isFullscreenPage }">
      <router-view />
    </a-layout-content>
    <!-- 底部版权信息 -->
    <GlobalFooter v-if="showFooter" />
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isAuthPage = computed(() => route.path === '/user/login' || route.path === '/user/register')
const isFullscreenPage = computed(() => route.path.startsWith('/app/chat/'))
const showFooter = computed(() => !isAuthPage.value && !isFullscreenPage.value)
</script>

<style scoped>
.basic-layout {
  background: #f8fafc;
  min-height: 100vh;
}

.fullscreen-layout {
  height: 100vh;
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

.fullscreen-content {
  overflow: hidden;
}
</style>
