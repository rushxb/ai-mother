<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  intensity?: number
  ease?: number
}

const props = withDefaults(defineProps<Props>(), {
  intensity: 0.1,
  ease: 0.1,
})

const mouseX = ref(0)
const mouseY = ref(0)
const sceneRef = ref()

const handleMouseMove = (event: MouseEvent) => {
  mouseX.value = (event.clientX / window.innerWidth) * 2 - 1
  mouseY.value = -(event.clientY / window.innerHeight) * 2 + 1
}

onMounted(() => {
  window.addEventListener('mousemove', handleMouseMove, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('mousemove', handleMouseMove)
})

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (sceneRef.value) {
    const targetX = mouseX.value * intensity
    const targetY = mouseY.value * intensity
    sceneRef.value.rotation.y += (targetX - sceneRef.value.rotation.y) * ease
    sceneRef.value.rotation.x += (targetY - sceneRef.value.rotation.x) * ease
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresGroup ref="sceneRef">
        <slot />
      </TresGroup>
    </TresCanvas>
  </div>
</template>
