<template>
  <aside class="sidebar">
    <a class="brand" href="#overview">
      <span>A</span>
      <strong>{{ appInfo.name }}</strong>
    </a>
    <nav>
      <a v-for="item in navItems" :key="item.path" :href="item.path">
        <span>{{ item.icon }}</span>
        {{ item.label }}
      </a>
    </nav>
  </aside>
</template>

<script setup>
import { appInfo, navItems } from '@/data/adminData'
</script>
