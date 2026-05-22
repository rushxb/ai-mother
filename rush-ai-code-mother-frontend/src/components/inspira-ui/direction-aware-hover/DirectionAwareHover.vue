<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

interface Props {
  imageUrl: string
  childrenClass?: string
  imageClass?: string
  class?: string
  imageAlt?: string
}

const props = withDefaults(defineProps<Props>(), {
  childrenClass: undefined,
  imageClass: undefined,
  class: undefined,
  imageAlt: 'image',
})

const divRef = ref<HTMLDivElement | null>(null)
const direction = ref<'top' | 'bottom' | 'left' | 'right' | null>(null)
const isTouched = ref(false)
const isMobile = ref(false)
let touchTimer: ReturnType<typeof setTimeout> | null = null

function detectMobile() {
  isMobile.value = window.matchMedia('(max-width: 768px)').matches || 'ontouchstart' in window
}

function getDirection(ev: MouseEvent, obj: HTMLElement) {
  const { width: w, height: h, left, top } = obj.getBoundingClientRect()
  const x = ev.clientX - left - (w / 2) * (w > h ? h / w : 1)
  const y = ev.clientY - top - (h / 2) * (h > w ? w / h : 1)
  return Math.round(Math.atan2(y, x) / 1.57079633 + 5) % 4
}

function setDirectionFromEvent(event: MouseEvent) {
  if (!divRef.value) {
    return
  }

  const fetchedDirection = getDirection(event, divRef.value)
  direction.value = (['top', 'right', 'bottom', 'left'][fetchedDirection] || 'left') as
    | 'top'
    | 'right'
    | 'bottom'
    | 'left'
}

function handleMouseEnter(event: MouseEvent) {
  if (isMobile.value) {
    return
  }
  setDirectionFromEvent(event)
}

function handleMouseLeave() {
  if (isMobile.value) {
    return
  }
  direction.value = null
}

function handleTouchStart(event: TouchEvent) {
  if (!isMobile.value) {
    return
  }

  isTouched.value = true
  const touch = event.touches[0]
  if (!touch) {
    return
  }

  setDirectionFromEvent(
    new MouseEvent('mouseenter', {
      clientX: touch.clientX,
      clientY: touch.clientY,
    }),
  )

  if (touchTimer) {
    clearTimeout(touchTimer)
  }
  touchTimer = setTimeout(() => {
    handleTouchEnd()
  }, 3000)
}

function handleTouchEnd() {
  if (touchTimer) {
    clearTimeout(touchTimer)
    touchTimer = null
  }

  setTimeout(() => {
    direction.value = null
    isTouched.value = false
  }, 300)
}

const containerClass = computed(() => ['direction-aware-card', props.class])
const imageClassName = computed(() => ['direction-aware-image', props.imageClass])
const childrenClassName = computed(() => ['direction-aware-children', props.childrenClass])

const overlayClass = computed(() => [
  'direction-aware-overlay',
])

const imageContainerClass = computed(() => [
  'direction-aware-image-wrap',
  direction.value ? `direction-aware-image-wrap--${direction.value}` : '',
])

const directionVars = computed(() => {
  const map = {
    top: { overlayX: '0%', overlayY: '-100%', imageX: '0px', imageY: '20px' },
    bottom: { overlayX: '0%', overlayY: '100%', imageX: '0px', imageY: '-20px' },
    left: { overlayX: '-100%', overlayY: '0%', imageX: '20px', imageY: '0px' },
    right: { overlayX: '100%', overlayY: '0%', imageX: '-20px', imageY: '0px' },
  }
  const current = direction.value ? map[direction.value] : map.left
  return {
    '--overlay-from-x': current.overlayX,
    '--overlay-from-y': current.overlayY,
    '--image-shift-x': current.imageX,
    '--image-shift-y': current.imageY,
  }
})

onMounted(() => {
  detectMobile()
  window.addEventListener('resize', detectMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', detectMobile)
  if (touchTimer) {
    clearTimeout(touchTimer)
  }
})
</script>

<template>
  <div
    ref="divRef"
    :class="containerClass"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
    @touchstart="handleTouchStart"
    @touchend="handleTouchEnd"
    :style="directionVars"
  >
    <div class="direction-aware-inner">
      <Transition name="direction-overlay">
        <div v-show="direction !== null" :class="overlayClass" />
      </Transition>
      <div :class="imageContainerClass">
        <img :src="imageUrl" :alt="imageAlt" :class="imageClassName" width="1000" height="1000" />
      </div>
      <Transition name="fade">
        <div v-show="direction !== null || isTouched" :class="childrenClassName">
          <slot />
        </div>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.direction-aware-card {
  position: relative;
  overflow: hidden;
  border-radius: 8px;
  background: transparent;
  transition:
    transform 0.3s ease,
    box-shadow 0.3s ease;
  touch-action: manipulation;
}

.direction-aware-card:active {
  transform: scale(0.98);
}

.direction-aware-inner {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.direction-aware-overlay {
  position: absolute;
  inset: 0;
  z-index: 10;
  background: rgba(0, 0, 0, 0.42);
  transform: translate(0, 0);
}

.direction-aware-image-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  background: #f8fafc;
  transition: transform 0.3s ease;
}

.direction-aware-image-wrap--top {
  transform: translate(var(--image-shift-x), var(--image-shift-y));
}

.direction-aware-image-wrap--bottom {
  transform: translate(var(--image-shift-x), var(--image-shift-y));
}

.direction-aware-image-wrap--left {
  transform: translate(var(--image-shift-x), var(--image-shift-y));
}

.direction-aware-image-wrap--right {
  transform: translate(var(--image-shift-x), var(--image-shift-y));
}

.direction-aware-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transform: scale(1.5);
  transition: transform 0.3s ease;
}

.direction-aware-children {
  position: absolute;
  left: 16px;
  bottom: 16px;
  z-index: 40;
  color: #ffffff;
  transition: opacity 0.3s ease;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.direction-overlay-enter-active,
.direction-overlay-leave-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s ease;
}

.direction-overlay-enter-from,
.direction-overlay-leave-to {
  opacity: 0;
  transform: translate(var(--overlay-from-x), var(--overlay-from-y));
}

@media (max-width: 768px) {
  .direction-aware-card {
    min-width: 44px;
    min-height: 44px;
  }

  .direction-aware-image-wrap--top {
    transform: translateY(8px);
  }

  .direction-aware-image-wrap--bottom {
    transform: translateY(-8px);
  }

  .direction-aware-image-wrap--left {
    transform: translateX(8px);
  }

  .direction-aware-image-wrap--right {
    transform: translateX(-8px);
  }
}

@media (prefers-reduced-motion: reduce) {
  * {
    transition-duration: 0.1s !important;
  }
}
</style>
