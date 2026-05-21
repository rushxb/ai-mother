<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  text?: string
  delay?: number
}

const props = withDefaults(defineProps<Props>(), {
  text: '',
  delay: 50,
})

const displayedText = ref('')
const isComplete = ref(false)

onMounted(() => {
  let index = 0
  const interval = setInterval(() => {
    if (index < props.text.length) {
      displayedText.value += props.text[index]
      index++
    } else {
      isComplete.value = true
      clearInterval(interval)
    }
  }, props.delay)
})
</script>

<template>
  <span :class="cn('inline-block', props.class)">
    {{ displayedText }}
    <span
      v-if="!isComplete"
      class="animate-blink ml-0.5 inline-block h-4 w-0.5 bg-current"
    />
  </span>
</template>

<style scoped>
@keyframes blink {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}

.animate-blink {
  animation: blink 1s step-end infinite;
}
</style>
