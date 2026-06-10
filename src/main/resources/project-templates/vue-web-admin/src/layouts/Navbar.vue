<template>
  <header class="workspace-header">
    <div>
      <span class="eyebrow">{{ appInfo.org }}</span>
      <h1>{{ route.meta.title }}</h1>
    </div>
    <div class="operator">
      <span>{{ appInfo.operator }}</span>
      <button type="button">导出</button>
    </div>
  </header>
</template>

<script setup>
import { useRoute } from 'vue-router'
import { appInfo } from '@/data/adminData'

const route = useRoute()
</script>
