<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { TextureLoader } from 'three'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  matcapUrl?: string
}

const props = withDefaults(defineProps<Props>(), {
  matcapUrl: 'https://raw.githubusercontent.com/nidorx/matcaps/master/1024/293534_B2C6C5_6B8B8A_547877.png',
})

const meshRef = ref()
const matcapTexture = ref()

onMounted(async () => {
  const loader = new TextureLoader()
  matcapTexture.value = await loader.loadAsync(props.matcapUrl)
})

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (meshRef.value) {
    meshRef.value.rotation.y += 0.01
    meshRef.value.rotation.x += 0.005
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresMesh ref="meshRef">
        <TresTorusKnotGeometry :args="[1, 0.3, 128, 16]" />
        <TresMeshMatcapMaterial v-if="matcapTexture" :matcap="matcapTexture" />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
