<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  delay?: number
}

const props = withDefaults(defineProps<Props>(), {
  delay: 0,
})

const isVisible = ref(false)

onMounted(() => {
  setTimeout(() => {
    isVisible.value = true
  }, props.delay)
})
</script>

<template>
  <div
    :class="cn(
      'transition-all duration-700 ease-out',
      isVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-8',
      props.class
    )"
  >
    <slot />
  </div>
</template>
