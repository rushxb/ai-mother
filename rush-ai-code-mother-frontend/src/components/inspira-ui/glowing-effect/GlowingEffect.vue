<script setup lang="ts">
import { computed, ref } from 'vue'

interface Props {
  class?: string
  glowSize?: number
}

const props = withDefaults(defineProps<Props>(), {
  class: undefined,
  glowSize: 180,
})

const rootRef = ref<HTMLDivElement | null>(null)
const glowX = ref(50)
const glowY = ref(50)
const active = ref(false)

const rootClass = computed(() => ['glowing-effect', props.class, active.value ? 'glowing-effect--active' : ''])
const rootStyle = computed(() => ({
  '--glow-x': `${glowX.value}%`,
  '--glow-y': `${glowY.value}%`,
  '--glow-size': `${props.glowSize}px`,
}))

function handleMouseMove(event: MouseEvent) {
  if (!rootRef.value) {
    return
  }

  const rect = rootRef.value.getBoundingClientRect()
  glowX.value = ((event.clientX - rect.left) / rect.width) * 100
  glowY.value = ((event.clientY - rect.top) / rect.height) * 100
}
</script>

<template>
  <div
    ref="rootRef"
    :class="rootClass"
    :style="rootStyle"
    @mouseenter="active = true"
    @mouseleave="active = false"
    @mousemove="handleMouseMove"
  >
    <div class="glowing-effect__border" />
    <slot />
  </div>
</template>

<style scoped>
.glowing-effect {
  position: relative;
  border-radius: inherit;
  isolation: isolate;
}

.glowing-effect__border {
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  opacity: 0;
  transition: opacity 0.24s ease;
  background:
    radial-gradient(
      var(--glow-size) var(--glow-size) at var(--glow-x) var(--glow-y),
      rgba(72, 156, 255, 0.95),
      rgba(44, 192, 210, 0.36) 35%,
      transparent 72%
    );
  filter: saturate(1.2);
}

.glowing-effect__border::after {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  background: inherit;
  filter: blur(18px);
  opacity: 0.58;
}

.glowing-effect--active .glowing-effect__border {
  opacity: 1;
}
</style>
