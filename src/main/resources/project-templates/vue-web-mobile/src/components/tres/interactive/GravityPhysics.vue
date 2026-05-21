<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  count?: number
  gravity?: number
  bounce?: number
}

const props = withDefaults(defineProps<Props>(), {
  count: 50,
  gravity: -9.8,
  bounce: 0.8,
})

interface Particle {
  position: { x: number; y: number; z: number }
  velocity: { x: number; y: number; z: number }
  scale: number
}

const particles = ref<Particle[]>([])

onMounted(() => {
  particles.value = Array.from({ length: count }, () => ({
    position: {
      x: (Math.random() - 0.5) * 10,
      y: Math.random() * 10 + 5,
      z: (Math.random() - 0.5) * 10,
    },
    velocity: {
      x: (Math.random() - 0.5) * 2,
      y: Math.random() * 2,
      z: (Math.random() - 0.5) * 2,
    },
    scale: Math.random() * 0.5 + 0.1,
  }))
})

const { onLoop } = useRenderLoop()

onLoop(({ delta }) => {
  particles.value.forEach((particle) => {
    particle.velocity.y += gravity * delta
    particle.position.x += particle.velocity.x * delta
    particle.position.y += particle.velocity.y * delta
    particle.position.z += particle.velocity.z * delta

    if (particle.position.y < -5) {
      particle.position.y = -5
      particle.velocity.y *= -bounce
    }
  })
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 5, 15]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresMesh
        v-for="(particle, index) in particles"
        :key="index"
        :position="[particle.position.x, particle.position.y, particle.position.z]"
        :scale="particle.scale"
      >
        <TresSphereGeometry :args="[1, 16, 16]" />
        <TresMeshStandardMaterial color="#00ff00" />
      </TresMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
