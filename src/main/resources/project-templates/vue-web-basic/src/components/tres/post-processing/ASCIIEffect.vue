<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { ShaderPass } from 'three/examples/jsm/postprocessing/ShaderPass'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  characters?: string
  fontSize?: number
  color?: string
}

const props = withDefaults(defineProps<Props>(), {
  characters: ' .:-=+*#%@',
  fontSize: 8,
  color: '#00ff00',
})

const shaderPass = ref<ShaderPass>()

const asciiShader = {
  uniforms: {
    tDiffuse: { value: null },
    uCharacters: { value: characters },
    uFontSize: { value: fontSize },
    uColor: { value: color },
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
    uniform string uCharacters;
    uniform float uFontSize;
    uniform vec3 uColor;
    varying vec2 vUv;

    void main() {
      vec2 uv = vUv;
      vec4 texel = texture2D(tDiffuse, uv);
      float gray = dot(texel.rgb, vec3(0.299, 0.587, 0.114));
      int index = int(gray * 9.0);
      float char = float(uCharacters[index]);
      gl_FragColor = vec4(uColor * char, 1.0);
    }
  `,
}

onMounted(() => {
  shaderPass.value = new ShaderPass(asciiShader)
})

const { onLoop } = useRenderLoop()

onLoop(() => {
  if (shaderPass.value) {
    shaderPass.value.uniforms.uCharacters.value = characters
    shaderPass.value.uniforms.uFontSize.value = fontSize
    shaderPass.value.uniforms.uColor.value = color
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
        <TresShaderPass v-if="shaderPass" :args="[asciiShader]" />
      </TresEffectComposer>
    </TresCanvas>
  </div>
</template>
