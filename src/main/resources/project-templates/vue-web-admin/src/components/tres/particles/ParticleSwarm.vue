<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  count?: number
  color?: string
  size?: number
  speed?: number
}

const props = withDefaults(defineProps<Props>(), {
  count: 1000,
  color: '#00ff00',
  size: 0.02,
  speed: 0.5,
})

interface Particle {
  position: { x: number; y: number; z: number }
  velocity: { x: number; y: number; z: number }
  phase: number
}

const particles = ref<Particle[]>([])

onMounted(() => {
  particles.value = Array.from({ length: count }, () => ({
    position: {
      x: (Math.random() - 0.5) * 10,
      y: (Math.random() - 0.5) * 10,
      z: (Math.random() - 0.5) * 10,
    },
    velocity: {
      x: (Math.random() - 0.5) * 0.1,
      y: (Math.random() - 0.5) * 0.1,
      z: (Math.random() - 0.5) * 0.1,
    },
    phase: Math.random() * Math.PI * 2,
  }))
})

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  particles.value.forEach((particle) => {
    particle.position.x += Math.sin(elapsed * speed + particle.phase) * 0.01
    particle.position.y += Math.cos(elapsed * speed + particle.phase) * 0.01
    particle.position.z += Math.sin(elapsed * speed * 0.5 + particle.phase) * 0.01
  })
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 10]" />
      <TresAmbientLight :intensity="0.5" />
      <TresPoints>
        <TresBufferGeometry>
          <TresBufferAttribute
            :args="[new Float32Array(particles.flatMap(p => [p.position.x, p.position.y, p.position.z])), 3]"
            attach="attributes-position"
          />
        </TresBufferGeometry>
        <TresPointsMaterial :color="color" :size="size" />
      </TresPoints>
      <slot />
    </TresCanvas>
  </div>
</template>
