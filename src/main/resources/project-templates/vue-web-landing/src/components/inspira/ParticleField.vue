<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  color?: string
  count?: number
}

const props = withDefaults(defineProps<Props>(), {
  color: 'primary',
  count: 50,
})

interface Particle {
  id: number
  x: number
  y: number
  size: number
  opacity: number
  delay: number
}

const particles = ref<Particle[]>([])

onMounted(() => {
  particles.value = Array.from({ length: props.count }, (_, i) => ({
    id: i,
    x: Math.random() * 100,
    y: Math.random() * 100,
    size: Math.random() * 4 + 1,
    opacity: Math.random() * 0.5 + 0.1,
    delay: Math.random() * 3,
  }))
})
</script>

<template>
  <div :class="cn('absolute inset-0 overflow-hidden', props.class)">
    <div
      v-for="particle in particles"
      :key="particle.id"
      class="absolute rounded-full animate-float"
      :style="{
        left: `${particle.x}%`,
        top: `${particle.y}%`,
        width: `${particle.size}px`,
        height: `${particle.size}px`,
        opacity: particle.opacity,
        animationDelay: `${particle.delay}s`,
        backgroundColor: `hsl(var(--${color}))`,
      }"
    />
  </div>
</template>

<style scoped>
@keyframes float {
  0%, 100% {
    transform: translateY(0) translateX(0);
  }
  25% {
    transform: translateY(-20px) translateX(10px);
  }
  50% {
    transform: translateY(-10px) translateX(-10px);
  }
  75% {
    transform: translateY(-30px) translateX(5px);
  }
}

.animate-float {
  animation: float 6s ease-in-out infinite;
}
</style>
