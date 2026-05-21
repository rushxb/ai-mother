<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  speed?: number
  count?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#00ff00',
  speed: 1,
  count: 50,
})

interface Sphere {
  position: [number, number, number]
  scale: number
}

const spheres = ref<Sphere[]>(
  Array.from({ length: props.count }, () => ({
    position: [
      (Math.random() - 0.5) * 10,
      (Math.random() - 0.5) * 10,
      (Math.random() - 0.5) * 10,
    ],
    scale: Math.random() * 0.5 + 0.1,
  }))
)

const groupRef = ref()

const { onLoop } = useRenderLoop()

onLoop(({ delta }) => {
  if (groupRef.value) {
    groupRef.value.rotation.y += delta * props.speed * 0.1
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 15]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresGroup ref="groupRef">
        <TresMesh
          v-for="(sphere, index) in spheres"
          :key="index"
          :position="sphere.position"
          :scale="sphere.scale"
        >
          <TresSphereGeometry :args="[1, 16, 16]" />
          <TresMeshStandardMaterial :color="color" />
        </TresMesh>
      </TresGroup>
    </TresCanvas>
  </div>
</template>
