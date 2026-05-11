<template>
  <div class="metric-grid">
    <article v-for="item in items" :key="item.label" :class="['metric-card', item.tone]">
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}</strong>
      <small>{{ item.trend }}</small>
    </article>
  </div>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] }
})
</script>
