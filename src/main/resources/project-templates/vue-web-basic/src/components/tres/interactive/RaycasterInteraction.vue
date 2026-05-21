<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  hoverColor?: string
}

const props = withDefaults(defineProps<Props>(), {
  color: '#00ff00',
  hoverColor: '#ff0000',
})

const meshRef = ref()
const isHovered = ref(false)
const isClicked = ref(false)

const emit = defineEmits<{
  hover: [isHovered: boolean]
  click: [event: MouseEvent]
}>()

const handlePointerEnter = () => {
  isHovered.value = true
  emit('hover', true)
}

const handlePointerLeave = () => {
  isHovered.value = false
  emit('hover', false)
}

const handleClick = (event: MouseEvent) => {
  isClicked.value = true
  emit('click', event)
  setTimeout(() => {
    isClicked.value = false
  }, 200)
}

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (meshRef.value) {
    const targetScale = isClicked.value ? 1.2 : isHovered.value ? 1.1 : 1
    meshRef.value.scale.lerp({ x: targetScale, y: targetScale, z: targetScale }, 0.1)
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresMesh
        ref="meshRef"
        @pointer-enter="handlePointerEnter"
        @pointer-leave="handlePointerLeave"
        @click="handleClick"
      >
        <TresBoxGeometry :args="[1, 1, 1]" />
        <TresMeshStandardMaterial :color="isHovered ? hoverColor : color" />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
