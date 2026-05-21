<script setup lang="ts">
import { Motion } from 'motion-v'
import { computed, useSlots } from 'vue'

interface Props {
  duration?: number
  delay?: number
  blur?: string
  yOffset?: number
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  duration: 0.9,
  delay: 0.12,
  blur: '18px',
  yOffset: 18,
})

const slots = useSlots()

const children = computed(() => (slots.default ? slots.default() : []))

const initialState = computed(() => ({
  opacity: 0,
  filter: `blur(${props.blur})`,
  y: props.yOffset,
}))

const animateState = {
  opacity: 1,
  filter: 'blur(0px)',
  y: 0,
}
</script>

<template>
  <div :class="props.class">
    <Motion
      v-for="(child, index) in children"
      :key="index"
      as="div"
      :initial="initialState"
      :while-in-view="animateState"
      :in-view-options="{ once: true, margin: '-10% 0px' }"
      :transition="{
        duration: props.duration,
        ease: 'easeInOut',
        delay: props.delay * index,
      }"
    >
      <component :is="child" />
    </Motion>
  </div>
</template>
