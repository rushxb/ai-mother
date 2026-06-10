<script setup lang="ts">
import { Motion } from 'motion-v'
import { computed, ref } from 'vue'

interface LensProps {
  zoomFactor?: number
  lensSize?: number
  position?: {
    x: number
    y: number
  }
  isStatic?: boolean
  hovering?: boolean
}

const props = withDefaults(defineProps<LensProps>(), {
  zoomFactor: 1.5,
  lensSize: 170,
  isStatic: false,
  hovering: undefined,
  position: () => ({ x: 200, y: 150 }),
})

const emit = defineEmits<{
  (e: 'hover-update', value: boolean): void
}>()

const localIsHovering = ref(false)
const mousePosition = ref({ x: 100, y: 100 })

const isHovering = computed(() => props.hovering ?? localIsHovering.value)

function setIsHovering(hover: boolean) {
  localIsHovering.value = hover
  emit('hover-update', hover)
}

function handleMouseMove(event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  mousePosition.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top,
  }
}

const activePosition = computed(() => (props.isStatic ? props.position : mousePosition.value))

const maskImage = computed(
  () =>
    `radial-gradient(circle ${props.lensSize / 2}px at ${activePosition.value.x}px ${activePosition.value.y}px, black 100%, transparent 100%)`,
)

const transformOrigin = computed(() => `${activePosition.value.x}px ${activePosition.value.y}px`)
</script>

<template>
  <div
    class="inspira-lens"
    @mouseenter="setIsHovering(true)"
    @mouseleave="setIsHovering(false)"
    @mousemove="handleMouseMove"
  >
    <slot />

    <div v-if="props.isStatic || isHovering" class="inspira-lens__overlay-wrap">
      <Motion
        :initial="{ opacity: 0, scale: 0.58 }"
        :animate="{ opacity: 1, scale: 1 }"
        :leave="{ opacity: 0, scale: 0.8 }"
        :transition="{ duration: 0.3, ease: 'easeOut' }"
        class="inspira-lens__overlay"
        :style="{
          maskImage,
          WebkitMaskImage: maskImage,
          transformOrigin,
        }"
      >
        <div
          class="inspira-lens__zoomed"
          :style="{
            transform: `scale(${props.zoomFactor})`,
            transformOrigin,
          }"
        >
          <slot />
        </div>
        <div
          class="inspira-lens__ring"
          :style="{
            width: `${props.lensSize}px`,
            height: `${props.lensSize}px`,
            transform: `translate(${activePosition.x - props.lensSize / 2}px, ${activePosition.y - props.lensSize / 2}px)`,
          }"
        />
      </Motion>
    </div>
  </div>
</template>

<style scoped>
.inspira-lens {
  position: relative;
  z-index: 0;
  overflow: hidden;
  border-radius: inherit;
}

.inspira-lens__overlay-wrap {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.inspira-lens__overlay {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.inspira-lens__zoomed {
  position: absolute;
  inset: 0;
}

.inspira-lens__ring {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 2;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.82);
  box-shadow:
    0 18px 44px rgba(15, 23, 42, 0.22),
    inset 0 0 0 1px rgba(255, 255, 255, 0.44),
    inset 0 0 34px rgba(255, 255, 255, 0.26);
  backdrop-filter: blur(1px);
}

.inspira-lens__ring::after {
  content: '';
  position: absolute;
  inset: 10px;
  border-radius: inherit;
  border: 1px solid rgba(255, 255, 255, 0.34);
}
</style>
