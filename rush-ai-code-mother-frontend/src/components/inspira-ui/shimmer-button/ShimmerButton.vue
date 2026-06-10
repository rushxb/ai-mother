<script setup lang="ts">
interface Props {
  disabled?: boolean
  loading?: boolean
  type?: 'button' | 'submit' | 'reset'
  class?: string
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  loading: false,
  type: 'button',
})

defineEmits<{
  click: [event: MouseEvent]
}>()
</script>

<template>
  <button
    :type="props.type"
    :disabled="props.disabled || props.loading"
    :class="['shimmer-button', props.class, { disabled: props.disabled || props.loading }]"
    @click="$emit('click', $event)"
  >
    <span class="shimmer-sheen" aria-hidden="true"></span>
    <span class="shimmer-halo" aria-hidden="true"></span>
    <span class="shimmer-content">
      <slot />
    </span>
  </button>
</template>

<style scoped>
.shimmer-button {
  --button-start: #1f6fff;
  --button-end: #5bbcff;
  --button-shadow: rgba(47, 128, 255, 0.28);
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  min-width: 132px;
  height: 48px;
  padding: 0 1.5rem;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  line-height: 1;
  background: linear-gradient(135deg, var(--button-start) 0%, var(--button-end) 100%);
  box-shadow:
    0 18px 34px var(--button-shadow),
    inset 0 1px 0 rgba(255, 255, 255, 0.28);
  cursor: pointer;
  overflow: hidden;
  isolation: isolate;
  transition:
    transform 0.24s ease,
    box-shadow 0.24s ease,
    filter 0.24s ease;
}

.shimmer-button:hover {
  transform: translateY(-1px);
  box-shadow:
    0 22px 42px rgba(47, 128, 255, 0.32),
    inset 0 1px 0 rgba(255, 255, 255, 0.32);
  filter: saturate(1.05);
}

.shimmer-button:focus-visible {
  outline: none;
  box-shadow:
    0 0 0 4px rgba(47, 128, 255, 0.16),
    0 22px 42px rgba(47, 128, 255, 0.32);
}

.shimmer-button:active {
  transform: translateY(0);
}

.shimmer-button:disabled,
.shimmer-button.disabled {
  cursor: not-allowed;
  opacity: 0.72;
  transform: none;
  box-shadow:
    0 12px 22px rgba(47, 128, 255, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.shimmer-content {
  position: relative;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
}

.shimmer-sheen {
  position: absolute;
  inset: 0;
  z-index: 1;
  background: linear-gradient(
    120deg,
    transparent 10%,
    rgba(255, 255, 255, 0.12) 24%,
    rgba(255, 255, 255, 0.7) 42%,
    rgba(255, 255, 255, 0.14) 58%,
    transparent 72%
  );
  transform: translateX(-165%);
  animation: shimmerSweep 2.8s ease-in-out infinite;
}

.shimmer-halo {
  position: absolute;
  inset: auto 12% -75%;
  height: 120%;
  z-index: 0;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.38) 0%, transparent 70%);
  opacity: 0.56;
  filter: blur(12px);
}

@keyframes shimmerSweep {
  0% {
    transform: translateX(-165%);
  }

  55%,
  100% {
    transform: translateX(165%);
  }
}
</style>
