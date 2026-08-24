<script setup lang="ts">
interface Props {
  title: string
  eyebrow?: string
  description?: string
}

defineProps<Props>()
</script>

<template>
  <header class="section-title">
    <span v-if="eyebrow">{{ eyebrow }}</span>
    <h2>{{ title }}</h2>
    <p v-if="description">{{ description }}</p>
  </header>
</template>
