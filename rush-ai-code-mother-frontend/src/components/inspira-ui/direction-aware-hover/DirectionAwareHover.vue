<script setup lang="ts">
import { computed, ref } from 'vue'

interface Props {
  imageUrl?: string
  imageAlt?: string
  overlayClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  imageUrl: '',
  imageAlt: '',
  overlayClass: '',
})

const direction = ref('top')
const active = ref(false)

const overlayClassName = computed(() => [
  'direction-aware-overlay',
  `direction-aware-overlay--${direction.value}`,
  { 'direction-aware-overlay--active': active.value },
  props.overlayClass,
])

const getDirection = (event: MouseEvent, element: HTMLElement) => {
  const rect = element.getBoundingClientRect()
  const x = event.clientX - rect.left - rect.width / 2
  const y = event.clientY - rect.top - rect.height / 2
  const angle = Math.atan2(y, x)
  const sector = Math.round(((angle + Math.PI) / (Math.PI / 2) + 3) % 4)
  return ['top', 'right', 'bottom', 'left'][sector] || 'top'
}

const handleMouseEnter = (event: MouseEvent) => {
  direction.value = getDirection(event, event.currentTarget as HTMLElement)
  active.value = true
}

const handleMouseLeave = (event: MouseEvent) => {
  direction.value = getDirection(event, event.currentTarget as HTMLElement)
  active.value = false
}
</script>

<template>
  <div class="direction-aware-hover" @mouseenter="handleMouseEnter" @mouseleave="handleMouseLeave">
    <img v-if="imageUrl" class="direction-aware-image" :src="imageUrl" :alt="imageAlt" />
    <slot name="base" />
    <div :class="overlayClassName">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.direction-aware-hover {
  position: relative;
  height: 100%;
  overflow: hidden;
  border-radius: inherit;
  isolation: isolate;
}

.direction-aware-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 0.42s ease;
}

.direction-aware-hover:hover .direction-aware-image {
  transform: scale(1.04);
}

.direction-aware-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 0.26s ease,
    transform 0.34s cubic-bezier(0.2, 0.8, 0.2, 1);
}

.direction-aware-overlay--top {
  transform: translate3d(0, -100%, 0);
}

.direction-aware-overlay--right {
  transform: translate3d(100%, 0, 0);
}

.direction-aware-overlay--bottom {
  transform: translate3d(0, 100%, 0);
}

.direction-aware-overlay--left {
  transform: translate3d(-100%, 0, 0);
}

.direction-aware-overlay--active {
  opacity: 1;
  pointer-events: auto;
  transform: translate3d(0, 0, 0);
}
</style>
