<script setup lang="ts">
import { computed, onMounted, ref, useSlots } from 'vue'

interface Props {
  duration?: number
  delay?: number
  boxColor?: string
  yOffset?: number
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  duration: 0.82,
  delay: 0.14,
  boxColor: '#2f8bff',
  yOffset: 18,
})

const slots = useSlots()
const isActive = ref(false)

const children = computed(() => (slots.default ? slots.default() : []))

onMounted(() => {
  requestAnimationFrame(() => {
    isActive.value = true
  })
})

const getItemStyle = (index: number) => {
  const boxDelay = props.delay * index
  const contentDelay = boxDelay + props.duration * 0.36

  return {
    '--box-delay': `${boxDelay}s`,
    '--box-duration': `${props.duration}s`,
    '--content-delay': `${contentDelay}s`,
    '--box-color': props.boxColor,
    '--reveal-offset': `${props.yOffset}px`,
  }
}
</script>

<template>
  <div :class="['box-reveal', props.class, { active: isActive }]">
    <div
      v-for="(child, index) in children"
      :key="index"
      class="box-reveal-item"
      :style="getItemStyle(index)"
    >
      <span class="box-reveal-overlay" aria-hidden="true"></span>
      <span class="box-reveal-content">
        <component :is="child" />
      </span>
    </div>
  </div>
</template>

<style scoped>
.box-reveal {
  display: grid;
  gap: 0.2rem;
}

.box-reveal-item {
  position: relative;
  display: block;
  overflow: hidden;
}

.box-reveal-overlay {
  position: absolute;
  inset: -0.08em 0;
  z-index: 2;
  border-radius: 0.18em;
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--box-color) 92%, white 8%), var(--box-color));
  transform: scaleX(0);
  transform-origin: left center;
  will-change: transform;
  pointer-events: none;
}

.box-reveal-content {
  position: relative;
  z-index: 1;
  display: block;
  opacity: 0;
  transform: translateY(var(--reveal-offset));
  will-change: transform, opacity;
}

.box-reveal.active .box-reveal-overlay {
  animation: box-sweep var(--box-duration) cubic-bezier(0.76, 0, 0.24, 1) forwards;
  animation-delay: var(--box-delay);
}

.box-reveal.active .box-reveal-content {
  animation: content-rise 0.66s cubic-bezier(0.22, 1, 0.36, 1) forwards;
  animation-delay: var(--content-delay);
}

@keyframes box-sweep {
  0% {
    transform-origin: left center;
    transform: scaleX(0);
  }

  48% {
    transform-origin: left center;
    transform: scaleX(1);
  }

  52% {
    transform-origin: right center;
    transform: scaleX(1);
  }

  100% {
    transform-origin: right center;
    transform: scaleX(0);
  }
}

@keyframes content-rise {
  from {
    opacity: 0;
    transform: translateY(var(--reveal-offset));
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
