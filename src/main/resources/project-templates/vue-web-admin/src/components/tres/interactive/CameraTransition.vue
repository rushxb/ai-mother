<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  duration?: number
  from?: { x: number; y: number; z: number }
  to?: { x: number; y: number; z: number }
}

const props = withDefaults(defineProps<Props>(), {
  duration: 2,
  from: () => ({ x: -5, y: 0, z: 5 }),
  to: () => ({ x: 5, y: 0, z: 5 }),
})

const cameraRef = ref()
const progress = ref(0)
const isAnimating = ref(false)

const startTransition = () => {
  isAnimating.value = true
  progress.value = 0
}

const { onLoop } = useRenderLoop()

onLoop(({ delta }) => {
  if (cameraRef.value && isAnimating.value) {
    progress.value += delta / duration
    if (progress.value >= 1) {
      progress.value = 1
      isAnimating.value = false
    }

    const t = progress.value
    const eased = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2

    cameraRef.value.position.x = from.x + (to.x - from.x) * eased
    cameraRef.value.position.y = from.y + (to.y - from.y) * eased
    cameraRef.value.position.z = from.z + (to.z - from.z) * eased
    cameraRef.value.lookAt(0, 0, 0)
  }
})

defineExpose({ startTransition })
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera ref="cameraRef" :position="[from.x, from.y, from.z]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <slot />
    </TresCanvas>
  </div>
</template>
