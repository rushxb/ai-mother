<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { FontLoader } from 'three/examples/jsm/loaders/FontLoader'
import { TextGeometry } from 'three/examples/jsm/geometries/TextGeometry'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  text?: string
  color?: string
  size?: number
  depth?: number
  fontUrl?: string
}

const props = withDefaults(defineProps<Props>(), {
  text: 'Hello',
  color: '#00ff00',
  size: 1,
  depth: 0.2,
  fontUrl: 'https://raw.githubusercontent.com/mrdoob/three.js/dev/examples/fonts/helvetiker_regular.typeface.json',
})

const meshRef = ref()
const geometryRef = ref<TextGeometry>()

onMounted(async () => {
  const loader = new FontLoader()
  const font = await loader.loadAsync(fontUrl)
  geometryRef.value = new TextGeometry(text, {
    font,
    size,
    depth,
    curveSegments: 12,
    bevelEnabled: true,
    bevelThickness: 0.03,
    bevelSize: 0.02,
    bevelOffset: 0,
    bevelSegments: 5,
  })
  geometryRef.value.center()
})

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
      <TresMesh v-if="geometryRef" ref="meshRef">
        <TresBufferGeometry :args="[geometryRef]" />
        <TresMeshStandardMaterial :color="color" />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
