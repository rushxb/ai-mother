<template>
  <section class="generation-activity" :class="`generation-activity--${variant}`" role="status" aria-live="polite">
    <div class="activity-visual" aria-hidden="true">
      <span class="activity-ring" />
      <span class="activity-orbit activity-orbit--one" />
      <span class="activity-orbit activity-orbit--two" />
      <span class="activity-mark activity-mark--left">{</span>
      <span class="activity-mark activity-mark--right">}</span>
      <div class="activity-code-lines">
        <span v-for="line in codeLines" :key="line" :style="{ '--line-index': line - 1 }" />
      </div>
    </div>

    <div class="activity-copy">
      <strong>{{ statusText }}</strong>
      <span>{{ description }}</span>
    </div>

    <div v-if="steps.length" class="activity-steps">
      <span
          v-for="step in steps"
          :key="step.key"
          class="activity-step"
          :class="{ active: stepIndex === step.index, done: stepIndex > step.index }"
      >
        {{ step.label }}
      </span>
    </div>
  </section>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  description: string
  statusText: string
  stepIndex: number
  steps: Array<{ key: string; label: string; index: number }>
  variant?: 'floating' | 'empty' | 'sidebar'
}>(), {
  variant: 'empty',
})

const codeLines = [1, 2, 3, 4]
</script>

<style scoped>
.generation-activity {
  --activity-accent: var(--chat-primary, var(--color-primary, #2f8bff));
  --activity-accent-strong: var(
    --chat-primary-strong,
    var(--color-primary-strong, #176fdd)
  );
  --activity-secondary: var(--chat-secondary, var(--color-secondary, #3cc9bb));
  --activity-ink: var(--chat-ink-strong, var(--color-ink-strong, #102033));
  --activity-muted: var(--chat-ink-soft, var(--color-ink-soft, #6f8198));
  --activity-line: var(--chat-line, var(--color-line, rgba(112, 140, 175, 0.18)));
  display: grid;
  justify-items: center;
  gap: 14px;
  width: min(520px, calc(100% - 28px));
  margin: auto;
  padding: 26px;
  color: var(--activity-ink);
  text-align: center;
}

.generation-activity--floating {
  width: 100%;
  margin: 0;
  justify-items: start;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px 16px;
  padding: 14px 16px;
  border: 1px solid var(--activity-line);
  border-radius: 18px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(248, 250, 252, 0.84)),
    radial-gradient(circle at 8% 0%, rgba(47, 139, 255, 0.12), transparent 36%);
  box-shadow: var(--chat-shadow-soft, 0 18px 36px rgba(68, 96, 136, 0.12));
  text-align: left;
  backdrop-filter: blur(16px);
}

.generation-activity--sidebar {
  width: 100%;
  min-height: 100%;
  padding: 20px 14px;
}

.activity-visual {
  position: relative;
  width: 92px;
  height: 92px;
  display: grid;
  place-items: center;
  border-radius: 28px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.96), rgba(241, 245, 249, 0.78)),
    radial-gradient(circle at 35% 20%, rgba(60, 201, 187, 0.18), transparent 42%);
  border: 1px solid var(--activity-line);
  box-shadow:
    0 18px 34px rgba(68, 96, 136, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  overflow: hidden;
}

.generation-activity--floating .activity-visual {
  width: 58px;
  height: 58px;
  border-radius: 18px;
}

.generation-activity--sidebar .activity-visual {
  width: 70px;
  height: 70px;
  border-radius: 22px;
}

.activity-visual::before {
  content: '';
  position: absolute;
  inset: 10px;
  border-radius: inherit;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.12) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.12) 1px, transparent 1px);
  background-size: 13px 13px;
  mask-image: radial-gradient(circle, #000 42%, transparent 72%);
}

.activity-ring {
  position: absolute;
  inset: 15px;
  border-radius: 999px;
  border: 1px solid rgba(47, 139, 255, 0.18);
}

.activity-ring::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  border: 2px solid transparent;
  border-top-color: var(--activity-accent);
  border-right-color: var(--activity-secondary);
  animation: activityRotate 1.45s linear infinite;
}

.activity-orbit {
  position: absolute;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--activity-secondary);
  box-shadow: 0 0 0 5px rgba(60, 201, 187, 0.12);
}

.activity-orbit--one {
  top: 15px;
  right: 20px;
  animation: activityFloat 1.8s ease-in-out infinite;
}

.activity-orbit--two {
  left: 18px;
  bottom: 18px;
  background: #f59e0b;
  box-shadow: 0 0 0 5px rgba(245, 158, 11, 0.12);
  animation: activityFloat 1.8s ease-in-out -0.7s infinite;
}

.activity-mark {
  position: absolute;
  z-index: 1;
  color: color-mix(in srgb, var(--activity-ink) 78%, transparent);
  font-family: Consolas, "SFMono-Regular", "Liberation Mono", monospace;
  font-size: 20px;
  font-weight: 700;
}

.generation-activity--floating .activity-mark,
.generation-activity--sidebar .activity-mark {
  font-size: 15px;
}

.activity-mark--left {
  left: 25px;
}

.activity-mark--right {
  right: 25px;
}

.generation-activity--floating .activity-mark--left,
.generation-activity--sidebar .activity-mark--left {
  left: 16px;
}

.generation-activity--floating .activity-mark--right,
.generation-activity--sidebar .activity-mark--right {
  right: 16px;
}

.activity-code-lines {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 5px;
  width: 32px;
}

.generation-activity--floating .activity-code-lines,
.generation-activity--sidebar .activity-code-lines {
  width: 24px;
  gap: 4px;
}

.activity-code-lines span {
  display: block;
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    rgba(47, 139, 255, 0.18),
    var(--activity-accent),
    rgba(60, 201, 187, 0.42)
  );
  transform-origin: left center;
  animation: activityLine 1.36s ease-in-out calc(var(--line-index) * 0.13s) infinite;
}

.activity-code-lines span:nth-child(2),
.activity-code-lines span:nth-child(4) {
  width: 72%;
}

.activity-copy {
  display: grid;
  gap: 4px;
  max-width: 420px;
}

.generation-activity--floating .activity-copy {
  min-width: 0;
  max-width: none;
}

.activity-copy strong {
  color: var(--activity-ink);
  font-size: 15px;
  line-height: 1.4;
}

.activity-copy span {
  color: var(--activity-muted);
  font-size: 12px;
  line-height: 1.6;
}

.activity-steps {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.generation-activity--floating .activity-steps {
  grid-column: 1 / -1;
  justify-content: flex-start;
}

.activity-step {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  background: var(--chat-surface-soft, rgba(246, 249, 253, 0.9));
  color: var(--activity-muted);
  font-size: 12px;
  font-weight: 600;
  transition:
    background 0.2s ease,
    color 0.2s ease,
    box-shadow 0.2s ease;
}

.activity-step.active {
  background: rgba(219, 234, 254, 0.96);
  color: var(--activity-accent-strong);
  box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.08);
}

.activity-step.done {
  background: rgba(204, 251, 241, 0.92);
  color: #0f766e;
}

@keyframes activityRotate {
  to {
    transform: rotate(360deg);
  }
}

@keyframes activityFloat {
  50% {
    transform: translate3d(0, -5px, 0);
  }
}

@keyframes activityLine {
  0%,
  100% {
    opacity: 0.38;
    transform: scaleX(0.48);
  }

  45% {
    opacity: 1;
    transform: scaleX(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .activity-ring::after,
  .activity-orbit,
  .activity-code-lines span {
    animation: none;
  }
}
</style>
