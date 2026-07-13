<template>
  <div class="admin-page-frame">
    <div class="admin-grid" aria-hidden="true"></div>
    <div class="admin-glow admin-glow-primary" aria-hidden="true"></div>
    <div class="admin-glow admin-glow-secondary" aria-hidden="true"></div>
    <AmbientTechCanvas class="admin-tech-canvas" :density="46" />

    <main class="admin-page-content">
      <Motion
        as="header"
        class="admin-page-header"
        :initial="{ opacity: 0, y: 14 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.46, ease: [0.22, 1, 0.36, 1] }"
      >
        <div class="admin-heading-copy">
          <span class="admin-eyebrow"><i></i>{{ eyebrow }}</span>
          <h1>{{ title }}</h1>
          <p v-if="description">{{ description }}</p>
        </div>
        <div v-if="$slots.actions" class="admin-header-actions">
          <slot name="actions" />
        </div>
      </Motion>

      <Motion
        as="div"
        class="admin-page-body"
        :initial="{ opacity: 0, y: 18 }"
        :animate="{ opacity: 1, y: 0 }"
        :transition="{ duration: 0.5, delay: 0.06, ease: [0.22, 1, 0.36, 1] }"
      >
        <slot />
      </Motion>
    </main>
  </div>
</template>

<script setup lang="ts">
import { Motion } from 'motion-v'
import AmbientTechCanvas from '@/components/visual/AmbientTechCanvas.vue'

interface AdminPageFrameProps {
  eyebrow?: string
  title: string
  description?: string
}

withDefaults(defineProps<AdminPageFrameProps>(), {
  eyebrow: 'OPERATIONS CONSOLE',
  description: '',
})
</script>

<style scoped>
.admin-page-frame {
  position: relative;
  min-height: calc(100vh - 72px);
  overflow: hidden;
  background:
    radial-gradient(circle at 6% 0%, rgba(190, 222, 255, 0.46), transparent 27%),
    radial-gradient(circle at 96% 18%, rgba(202, 242, 234, 0.42), transparent 26%),
    linear-gradient(145deg, #fbfdff, #f5f9fd 54%, #f4faf9);
}

.admin-tech-canvas {
  opacity: 0.22;
  mask-image: linear-gradient(180deg, rgba(0, 0, 0, 0.88), transparent 76%);
}

.admin-grid {
  position: absolute;
  inset: 0;
  opacity: 0.28;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(103, 137, 174, 0.075) 1px, transparent 1px),
    linear-gradient(90deg, rgba(103, 137, 174, 0.075) 1px, transparent 1px);
  background-size: 52px 52px;
  mask-image: linear-gradient(180deg, #000, transparent 74%);
}

.admin-glow {
  position: absolute;
  width: 380px;
  height: 380px;
  border-radius: 50%;
  filter: blur(42px);
  pointer-events: none;
}

.admin-glow-primary {
  top: 20%;
  left: -260px;
  background: rgba(47, 139, 255, 0.1);
}

.admin-glow-secondary {
  right: -260px;
  bottom: 12%;
  background: rgba(60, 201, 187, 0.1);
}

.admin-page-content {
  position: relative;
  z-index: 1;
  width: min(var(--page-max-width), calc(100% - 48px));
  margin: 0 auto;
  padding: 28px 0 64px;
}

.admin-page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  min-height: 132px;
  padding: 27px 30px;
  border: 1px solid rgba(119, 150, 187, 0.17);
  border-radius: 27px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.92), rgba(247, 251, 255, 0.78)),
    rgba(255, 255, 255, 0.75);
  box-shadow:
    0 22px 62px rgba(70, 101, 140, 0.09),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(20px);
}

.admin-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--color-primary);
  font-size: 11px;
  font-weight: 850;
  letter-spacing: 0.17em;
}

.admin-eyebrow i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-secondary);
  box-shadow: 0 0 0 6px rgba(60, 201, 187, 0.11);
}

.admin-heading-copy h1 {
  margin: 10px 0 0;
  color: var(--color-ink-strong);
  font-family: var(--font-display);
  font-size: clamp(28px, 3.2vw, 42px);
  line-height: 1.08;
  letter-spacing: -0.035em;
}

.admin-heading-copy p {
  max-width: 720px;
  margin: 10px 0 0;
  color: var(--color-ink-soft);
  font-size: 14px;
  line-height: 1.7;
}

.admin-header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}

.admin-header-actions :deep(.ant-btn),
.admin-header-actions :deep(.ant-select-selector) {
  min-height: 42px;
  border-radius: 13px !important;
  border-color: rgba(112, 143, 179, 0.18) !important;
  background: rgba(255, 255, 255, 0.76) !important;
  box-shadow: none !important;
}

.admin-header-actions :deep(.ant-btn-primary) {
  border-color: transparent !important;
  background: linear-gradient(135deg, #267ff1, #49b6ee) !important;
  box-shadow: 0 12px 26px rgba(47, 139, 255, 0.2) !important;
}

.admin-page-body {
  display: grid;
  gap: 18px;
  margin-top: 20px;
}

@media (max-width: 760px) {
  .admin-page-frame {
    min-height: calc(100vh - 118px);
  }

  .admin-page-content {
    width: min(100% - 24px, 680px);
    padding-top: 18px;
  }

  .admin-page-header {
    align-items: flex-start;
    flex-direction: column;
    min-height: 0;
    padding: 22px;
    border-radius: 22px;
  }

  .admin-header-actions {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
