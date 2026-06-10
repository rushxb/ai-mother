<script setup lang="ts">
import { Motion } from 'motion-v'

interface Props {
  index: number
}

defineProps<Props>()
</script>

<template>
  <Motion
    as="div"
    :initial="{
      opacity: 0,
      y: 20,
    }"
    :animate="{
      opacity: 1,
      y: 0,
      transition: {
        duration: 0.5,
        delay: 0.2 * index,
        ease: 'easeOut',
      },
    }"
    class="apple-carousel-item"
  >
    <slot />
  </Motion>
</template>

<style scoped>
.apple-carousel-item {
  border-radius: 28px;
  flex: 0 0 auto;
}

.apple-carousel-item:last-child {
  padding-right: 5%;
}

@media (min-width: 768px) {
  .apple-carousel-item:last-child {
    padding-right: 33%;
  }
}
</style>
