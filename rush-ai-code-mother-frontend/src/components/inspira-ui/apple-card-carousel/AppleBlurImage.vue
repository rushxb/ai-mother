<script setup lang="ts">
import { computed, ref } from 'vue'

interface Props {
  height?: number | string
  width?: number | string
  src: string
  class?: string
  alt?: string
  fill?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  height: undefined,
  width: undefined,
  class: '',
  alt: 'Background image',
  fill: false,
})

const isLoading = ref(true)
const className = computed(() => [
  'apple-blur-image',
  { 'apple-blur-image--loading': isLoading.value, 'apple-blur-image--fill': props.fill },
  props.class,
])

function handleLoad() {
  isLoading.value = false
}
</script>

<template>
  <img
    :src="src"
    :width="width"
    :height="height"
    loading="lazy"
    decoding="async"
    :alt="alt"
    :class="className"
    @load="handleLoad"
  />
</template>

<style scoped>
.apple-blur-image {
  transition:
    filter 0.3s ease,
    transform 0.6s ease;
}

.apple-blur-image--loading {
  filter: blur(10px);
}

.apple-blur-image--fill {
  width: 100%;
  height: 100%;
}
</style>
