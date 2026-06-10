<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { RGBELoader } from 'three/examples/jsm/loaders/RGBELoader'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  environmentUrl?: string
  metalness?: number
  roughness?: number
}

const props = withDefaults(defineProps<Props>(), {
  environmentUrl: 'https://raw.githubusercontent.com/mrdoob/three.js/dev/examples/textures/equirectangular/royal_esplanade_1k.hdr',
  metalness: 1,
  roughness: 0,
})

const meshRef = ref()
const environmentTexture = ref()

onMounted(async () => {
  const loader = new RGBELoader()
  environmentTexture.value = await loader.loadAsync(props.environmentUrl)
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
      <TresMesh ref="meshRef">
        <TresSphereGeometry :args="[1, 64, 64]" />
        <TresMeshStandardMaterial
          :metalness="metalness"
          :roughness="roughness"
          :envMap="environmentTexture"
        />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
