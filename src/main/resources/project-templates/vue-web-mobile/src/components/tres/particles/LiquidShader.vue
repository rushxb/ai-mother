<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { ShaderMaterial } from 'three'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  speed?: number
  intensity?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: '#00ff00',
  speed: 0.5,
  intensity: 1,
})

const meshRef = ref()
const materialRef = ref<ShaderMaterial>()

const vertexShader = `
  varying vec2 vUv;
  varying float vElevation;
  uniform float uTime;
  uniform float uIntensity;

  void main() {
    vUv = uv;
    vec3 pos = position;
    float elevation = sin(pos.x * 3.0 + uTime) * sin(pos.z * 3.0 + uTime) * uIntensity;
    pos.y += elevation;
    vElevation = elevation;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(pos, 1.0);
  }
`

const fragmentShader = `
  varying vec2 vUv;
  varying float vElevation;
  uniform vec3 uColor;
  uniform float uTime;

  void main() {
    float intensity = (vElevation + 1.0) * 0.5;
    vec3 color = uColor * intensity;
    gl_FragColor = vec4(color, 1.0);
  }
`

onMounted(() => {
  materialRef.value = new ShaderMaterial({
    vertexShader,
    fragmentShader,
    uniforms: {
      uTime: { value: 0 },
      uColor: { value: new THREE.Color(color) },
      uIntensity: { value: intensity },
    },
  })
})

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  if (materialRef.value) {
    materialRef.value.uniforms.uTime.value = elapsed * speed
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 5, 10]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresMesh ref="meshRef" :rotation="[-Math.PI / 2, 0, 0]">
        <TresPlaneGeometry :args="[10, 10, 128, 128]" />
        <TresShaderMaterial v-if="materialRef" :material="materialRef" />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
