<script setup lang="ts">
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  modelValue?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="duration-200 ease-out"
      leave-active-class="duration-200 ease-in"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="modelValue"
        :class="cn('fixed inset-0 z-50 bg-background/80 backdrop-blur-sm', props.class)"
        @click="emit('update:modelValue', false)"
      />
    </Transition>
    <Transition
      enter-active-class="duration-200 ease-out"
      leave-active-class="duration-200 ease-in"
      enter-from-class="opacity-0 scale-95"
      enter-to-class="opacity-100 scale-100"
      leave-from-class="opacity-100 scale-100"
      leave-to-class="opacity-0 scale-95"
    >
      <div
        v-if="modelValue"
        class="fixed left-[50%] top-[50%] z-50 grid w-full max-w-lg translate-x-[-50%] translate-y-[-50%] gap-4 border bg-background p-6 shadow-lg duration-200 sm:rounded-lg"
      >
        <slot />
      </div>
    </Transition>
  </Teleport>
</template>
