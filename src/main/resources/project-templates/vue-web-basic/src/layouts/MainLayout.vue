<template>
  <div class="app-shell">
    <Navbar />
    <main>
      <RouterView />
    </main>
    <footer class="footer">
      <span>{{ site.brand }} © 2026</span>
      <span>{{ site.slogan }}</span>
    </footer>
  </div>
</template>

<script setup>
import { RouterView } from 'vue-router'
import Navbar from './Navbar.vue'
import { site } from '@/data/siteData'
</script>
