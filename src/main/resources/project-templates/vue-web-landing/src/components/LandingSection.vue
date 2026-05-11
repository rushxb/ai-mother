<template>
  <section class="landing-section" :id="id">
    <div class="section-heading">
      <span v-if="eyebrow">{{ eyebrow }}</span>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
    </div>
    <slot />
  </section>
</template>

<script setup>
defineProps({
  id: String,
  eyebrow: String,
  title: { type: String, required: true },
  description: String
})
</script>
