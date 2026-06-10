<template>
  <header class="topbar">
    <a class="brand" href="#hero">
      <span class="brand-mark">V</span>
      <span>{{ site.brand }}</span>
    </a>
    <nav class="nav-links" aria-label="主导航">
      <a v-for="item in site.nav" :key="item.path" :href="item.path">{{ item.label }}</a>
    </nav>
  </header>
</template>

<script setup>
import { site } from '@/data/siteData'
</script>
