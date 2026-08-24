<script setup lang="ts">
import { cn } from '@/lib/utils'

interface Props {
  class?: string
}

const props = defineProps<Props>()
</script>

<template>
  <section :class="cn('grid gap-4', props.class)">
    <slot />
  </section>
</template>
