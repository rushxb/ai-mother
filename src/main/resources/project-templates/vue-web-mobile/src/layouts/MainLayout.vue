<template>
  <van-config-provider :theme-vars="themeVars">
    <div class="mobile-shell">
      <Navbar :title="title" />
      <RouterView />
      <footer class="tabbar">
        <RouterLink v-for="item in tabs" :key="item.path" :to="item.path" class="tabbar-item">
          <span>{{ item.icon }}</span>
          <strong>{{ item.label }}</strong>
        </RouterLink>
      </footer>
    </div>
  </van-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import Navbar from './Navbar.vue'
import { tabs, themeVars } from '@/data/mobileData'

const route = useRoute()
const title = computed(() => route.meta.title || '轻享生活')
</script>
