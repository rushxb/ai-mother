<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { GlitchPass } from 'three/examples/jsm/postprocessing/GlitchPass'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  delay?: number
  duration?: number
  strength?: number
}

const props = withDefaults(defineProps<Props>(), {
  delay: 1000,
  duration: 500,
  strength: 0.5,
})

const glitchPass = ref<GlitchPass>()

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  if (glitchPass.value) {
    const time = elapsed * 1000
    const cycle = delay + duration
    const phase = time % cycle
    glitchPass.value.enabled = phase < duration
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
        <TresGlitchPass ref="glitchPass" :args="[strength]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
