<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  intensity?: number
  threshold?: number
  radius?: number
}

const props = withDefaults(defineProps<Props>(), {
  intensity: 1.5,
  threshold: 0,
  radius: 0.8,
})

const bloomPass = ref<UnrealBloomPass>()

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (bloomPass.value) {
    bloomPass.value.strength = props.intensity
    bloomPass.value.threshold = props.threshold
    bloomPass.value.radius = props.radius
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 5]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <slot />
      <TresEffectComposer>
        <TresUnrealBloomPass ref="bloomPass" :args="[undefined, intensity, radius, threshold]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
