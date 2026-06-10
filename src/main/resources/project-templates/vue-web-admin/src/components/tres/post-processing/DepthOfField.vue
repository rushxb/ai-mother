<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { BokehPass } from 'three/examples/jsm/postprocessing/BokehPass'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  focus?: number
  aperture?: number
  maxblur?: number
}

const props = withDefaults(defineProps<Props>(), {
  focus: 500,
  aperture: 0.025,
  maxblur: 0.01,
})

const bokehPass = ref<BokehPass>()

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (bokehPass.value) {
    bokehPass.value.uniforms['focus'].value = focus
    bokehPass.value.uniforms['aperture'].value = aperture
    bokehPass.value.uniforms['maxblur'].value = maxblur
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
        <TresBokehPass ref="bokehPass" :args="[focus, aperture, maxblur]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
