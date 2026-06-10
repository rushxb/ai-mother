<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  speed?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#00ff00',
  speed: 0.5,
})

const meshRef = ref()
const morphRef = ref(0)

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  if (meshRef.value) {
    morphRef.value = (Math.sin(elapsed * speed) + 1) / 2
    meshRef.value.morphTargetInfluences[0] = morphRef.value
    meshRef.value.morphTargetInfluences[1] = 1 - morphRef.value
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresMesh ref="meshRef">
        <TresSphereGeometry :args="[1, 64, 64]" />
        <TresMeshStandardMaterial :color="color" morphTargets />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
