<template>
  <a-layout class="basic-layout">
    <!-- 顶部导航栏 -->
    <GlobalHeader />
    <!-- 主要内容区域 -->
    <a-layout-content class="main-content">
      <router-view />
    </a-layout-content>
    <!-- 底部版权信息 -->
    <GlobalFooter v-if="!isAuthPage" />
  </a-layout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'

const route = useRoute()
const isAuthPage = computed(() => route.path === '/user/login' || route.path === '/user/register')
</script>

<style scoped>
.basic-layout {
  background: #f8fafc;
  min-height: 100vh;
}

.main-content {
  flex: 1;
  width: 100%;
  padding: 0;
  background: #f8fafc;
  margin: 0;
  min-height: 0;
}
</style>
