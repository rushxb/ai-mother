<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  intensity?: number
  angle?: number
  penumbra?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#ffffff',
  intensity: 1,
  angle: 0.3,
  penumbra: 1,
})

const lightRef = ref()

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  if (lightRef.value) {
    lightRef.value.position.x = Math.sin(elapsed * 0.5) * 5
    lightRef.value.position.y = Math.cos(elapsed * 0.3) * 5
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 10]" />
      <TresAmbientLight :intensity="0.1" />
      <TresSpotLight
        ref="lightRef"
        :position="[5, 5, 5]"
        :intensity="intensity"
        :angle="angle"
        :penumbra="penumbra"
        :color="color"
        cast-shadow
      />
      <slot />
      <TresFog :args="['#000000', 5, 15]" />
    </TresCanvas>
  </div>
</template>
