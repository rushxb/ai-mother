<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

interface Props {
  words: string
  duration?: number
  stagger?: number
  startDelay?: number
  once?: boolean
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  duration: 0.42,
  stagger: 0.034,
  startDelay: 0.06,
  once: false,
})

const displayText = ref('')
let frameTimers: number[] = []

const letters = computed(() => Array.from(props.words))

const clearFrameTimers = () => {
  frameTimers.forEach((timer) => window.clearTimeout(timer))
  frameTimers = []
}

const play = () => {
  clearFrameTimers()
  displayText.value = ''

  letters.value.forEach((_, index) => {
    const timer = window.setTimeout(() => {
      displayText.value = letters.value.slice(0, index + 1).join('')
    }, (props.startDelay + props.stagger * index) * 1000)
    frameTimers.push(timer)
  })
}

onMounted(() => {
  play()
})

watch(
  () => props.words,
  () => {
    play()
  },
)

onBeforeUnmount(() => {
  clearFrameTimers()
})
</script>

<template>
  <span :class="['text-generate-effect', props.class]">
    <span class="generated-text">{{ displayText }}</span>
    <span class="text-caret" aria-hidden="true"></span>
  </span>
</template>

<style scoped>
.text-generate-effect {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  color: inherit;
}

.generated-text {
  white-space: pre-wrap;
}

.text-caret {
  width: 1px;
  height: 1.1em;
  margin-left: 0.18em;
  background: currentColor;
  opacity: 0.72;
  animation: caretBlink 1s steps(1, end) infinite;
}

@keyframes caretBlink {
  0%,
  48% {
    opacity: 0.76;
  }

  49%,
  100% {
    opacity: 0;
  }
}
</style>
