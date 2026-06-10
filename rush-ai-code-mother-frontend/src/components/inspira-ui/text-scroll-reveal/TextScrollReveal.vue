<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Motion } from 'motion-v'

interface Props {
  text: string
  delay?: number
  stagger?: number
  duration?: number
  once?: boolean
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  delay: 0,
  stagger: 0.045,
  duration: 0.55,
  once: true,
})

const rootRef = ref<HTMLElement | null>(null)
const isVisible = ref(false)
let observer: IntersectionObserver | null = null

const splitText = (text: string) => {
  const trimmedText = text.trim()
  if (!trimmedText) {
    return []
  }

  if (/\s/.test(trimmedText)) {
    return trimmedText.split(/(\s+)/).filter((token) => token.length > 0)
  }

  return Array.from(trimmedText)
}

const tokens = computed(() => splitText(props.text))

const createObserver = () => {
  if (!rootRef.value) {
    return
  }

  observer?.disconnect()
  observer = new IntersectionObserver(
    (entries) => {
      const [entry] = entries
      if (!entry) {
        return
      }

      if (entry.isIntersecting) {
        isVisible.value = true
        if (props.once) {
          observer?.disconnect()
          observer = null
        }
      } else if (!props.once) {
        isVisible.value = false
      }
    },
    {
      threshold: 0.25,
      rootMargin: '0px 0px -12% 0px',
    },
  )

  observer.observe(rootRef.value)
}

onMounted(() => {
  createObserver()
})

watch(
  () => props.text,
  () => {
    if (!props.once) {
      isVisible.value = false
    }
    createObserver()
  },
)

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <span ref="rootRef" :class="['text-scroll-reveal', props.class]">
    <Motion
      v-for="(token, index) in tokens"
      :key="`${token}-${index}`"
      as="span"
      class="reveal-token"
      :initial="{ opacity: 0.22, filter: 'blur(6px)', y: 10 }"
      :animate="isVisible ? { opacity: 1, filter: 'blur(0px)', y: 0 } : { opacity: 0.22, filter: 'blur(6px)', y: 10 }"
      :transition="{
        duration: props.duration,
        delay: props.delay + index * props.stagger,
        ease: 'easeOut',
      }"
    >
      {{ token }}
    </Motion>
  </span>
</template>

<style scoped>
.text-scroll-reveal {
  display: inline;
  color: inherit;
}

.reveal-token {
  display: inline-block;
  white-space: pre;
  will-change: opacity, transform, filter;
}
</style>
