<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { Vector2 } from 'three'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  intensity?: number
  samples?: number
}

const props = withDefaults(defineProps<Props>(), {
  intensity: 1,
  samples: 10,
})

const velocityBuffer = ref<Vector2>(new Vector2(0, 0))

const motionBlurShader = {
  uniforms: {
    tDiffuse: { value: null },
    uVelocity: { value: new Vector2(0, 0) },
    uIntensity: { value: intensity },
    uSamples: { value: samples },
  },
  vertexShader: `
    varying vec2 vUv;
    void main() {
      vUv = uv;
      gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    }
  `,
  fragmentShader: `
    uniform sampler2D tDiffuse;
    uniform vec2 uVelocity;
    uniform float uIntensity;
    uniform int uSamples;
    varying vec2 vUv;

    void main() {
      vec2 velocity = uVelocity * uIntensity;
      vec4 color = vec4(0.0);
      vec2 uv = vUv;

      for (int i = 0; i < uSamples; i++) {
        float t = float(i) / float(uSamples - 1);
        vec2 offset = velocity * t;
        color += texture2D(tDiffuse, uv + offset);
      }

      color /= float(uSamples);
      gl_FragColor = color;
    }
  `,
}

const { onLoop } = useRenderLoop()

onLoop(({ delta }) => {
  velocityBuffer.value.x = Math.sin(delta * 10) * 0.1
  velocityBuffer.value.y = Math.cos(delta * 10) * 0.1
  motionBlurShader.uniforms.uVelocity.value = velocityBuffer.value
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
        <TresShaderPass :args="[motionBlurShader]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
