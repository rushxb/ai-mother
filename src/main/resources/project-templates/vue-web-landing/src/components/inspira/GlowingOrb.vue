<script setup lang="ts">
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  size?: 'sm' | 'md' | 'lg'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'md',
})

const sizeClasses = {
  sm: 'h-16 w-16',
  md: 'h-24 w-24',
  lg: 'h-32 w-32',
}
</script>

<template>
  <div :class="cn('relative', sizeClasses[size], props.class)">
    <div class="absolute inset-0 rounded-full bg-gradient-to-r from-primary via-purple-500 to-pink-500 animate-spin-slow" />
    <div class="absolute inset-1 rounded-full bg-background" />
    <div class="absolute inset-2 rounded-full bg-gradient-to-r from-primary via-purple-500 to-pink-500 opacity-50 blur-sm" />
  </div>
</template>

<style scoped>
@keyframes spin-slow {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.animate-spin-slow {
  animation: spin-slow 8s linear infinite;
}
</style>
<r></r>