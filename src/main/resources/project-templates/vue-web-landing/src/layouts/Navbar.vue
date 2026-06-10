<template>
  <header class="landing-header">
    <a class="brand" href="#top">{{ brand.name }}</a>
    <nav>
      <a v-for="item in nav" :key="item" :href="`#${item}`">{{ item }}</a>
    </nav>
  </header>
</template>

<script setup>
import { brand, nav } from '@/data/landingData'
</script>
