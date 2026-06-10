<script setup lang="ts">
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  size?: 'sm' | 'md' | 'lg' | 'xl'
}

const props = withDefaults(defineProps<Props>(), {
  size: 'md',
})

const sizeClasses = {
  sm: 'text-3xl',
  md: 'text-5xl',
  lg: 'text-7xl',
  xl: 'text-9xl',
}

const gradientClasses = 'bg-gradient-to-r from-primary via-purple-500 to-pink-500 bg-clip-text text-transparent'
</script>

<template>
  <h1
    :class="cn(
      'font-bold tracking-tight',
      sizeClasses[size],
      gradientClasses,
      props.class
    )"
  >
    <slot />
  </h1>
</template>
