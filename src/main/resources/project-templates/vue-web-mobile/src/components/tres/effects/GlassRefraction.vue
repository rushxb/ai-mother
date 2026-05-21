<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  opacity?: number
  roughness?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#ffffff',
  opacity: 0.8,
  roughness: 0.1,
})

const meshRef = ref()

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (meshRef.value) {
    meshRef.value.rotation.y += 0.005
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
        <TresMeshPhysicalMaterial
          :color="color"
          :opacity="opacity"
          :roughness="roughness"
          :metalness="0.9"
          :transmission="0.9"
          :thickness="0.5"
          :clearcoat="1"
          :clearcoatRoughness="0.1"
          transparent
        />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
