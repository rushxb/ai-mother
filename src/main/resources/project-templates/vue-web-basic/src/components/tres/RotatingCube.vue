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
  speed: 1,
})

const meshRef = ref()

const { onLoop } = useRenderLoop()

onLoop(({ delta }) => {
  if (meshRef.value) {
    meshRef.value.rotation.x += delta * props.speed
    meshRef.value.rotation.y += delta * props.speed * 0.5
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 3]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresMesh ref="meshRef">
        <TresBoxGeometry :args="[1, 1, 1]" />
        <TresMeshStandardMaterial :color="color" />
      </TresMesh>
    </TresCanvas>
  </div>
</template>
