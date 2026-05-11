<template>
  <div class="section-title">
    <span v-if="eyebrow">{{ eyebrow }}</span>
    <h2>{{ title }}</h2>
    <p v-if="description">{{ description }}</p>
  </div>
</template>

<script setup>
defineProps({
  eyebrow: String,
  title: { type: String, required: true },
  description: String
})
</script>
