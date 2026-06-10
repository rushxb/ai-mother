<script setup lang="ts">
import { ref } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { Vector2 } from 'three'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  offset?: number
  angle?: number
}

const props = withDefaults(defineProps<Props>(), {
  offset: 0.005,
  angle: 0.5,
})

const chromaticAberrationShader = {
  uniforms: {
    tDiffuse: { value: null },
    uOffset: { value: offset },
    uAngle: { value: angle },
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
    uniform float uOffset;
    uniform float uAngle;
    varying vec2 vUv;

    void main() {
      vec2 offset = vec2(
        cos(uAngle) * uOffset,
        sin(uAngle) * uOffset
      );

      float r = texture2D(tDiffuse, vUv + offset).r;
      float g = texture2D(tDiffuse, vUv).g;
      float b = texture2D(tDiffuse, vUv - offset).b;

      gl_FragColor = vec4(r, g, b, 1.0);
    }
  `,
}

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  chromaticAberrationShader.uniforms.uAngle.value = angle + Math.sin(elapsed * 0.5) * 0.3
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
        <TresShaderPass :args="[chromaticAberrationShader]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
