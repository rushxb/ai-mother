<script setup lang="ts">
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  intensity?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#ffffff',
  intensity: 1,
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="intensity" :color="color" />
      <slot />
      <TresGridHelper :args="[10, 10]" />
    </TresCanvas>
  </div>
</template>
