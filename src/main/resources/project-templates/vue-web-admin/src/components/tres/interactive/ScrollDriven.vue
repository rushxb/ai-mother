<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  speed?: number
  offset?: number
}

const props = withDefaults(defineProps<Props>(), {
  speed: 0.5,
  offset: 0,
})

const scrollY = ref(0)
const cameraRef = ref()

const handleScroll = () => {
  scrollY.value = window.scrollY
}

onMounted(() => {
  window.addEventListener('scroll', handleScroll, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (cameraRef.value) {
    const targetY = (scrollY.value * speed) + offset
    cameraRef.value.position.y += (targetY - cameraRef.value.position.y) * 0.1
    cameraRef.value.lookAt(0, cameraRef.value.position.y, 0)
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera ref="cameraRef" :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <slot />
    </TresCanvas>
  </div>
</template>
