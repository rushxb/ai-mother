<template>
  <a-modal
    v-model:open="visible"
    :width="width"
    :footer="null"
    :closable="closable"
    :mask-closable="maskClosable"
    :mask-style="modalMaskStyle"
    :keyboard="true"
    centered
    wrap-class-name="app-modal-shell-wrap"
  >
    <section class="app-modal-shell" :class="`app-modal-shell--${tone}`">
      <header class="app-modal-shell__header">
        <div class="app-modal-shell__icon" aria-hidden="true">
          <slot name="icon" />
        </div>
        <div class="app-modal-shell__heading">
          <span v-if="eyebrow" class="app-modal-shell__eyebrow">{{ eyebrow }}</span>
          <h2>{{ title }}</h2>
          <p v-if="description">{{ description }}</p>
        </div>
      </header>

      <div class="app-modal-shell__body">
        <slot />
      </div>

      <footer v-if="$slots.footer" class="app-modal-shell__footer">
        <slot name="footer" />
      </footer>
    </section>
  </a-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CSSProperties } from 'vue'

type ModalTone = 'primary' | 'success' | 'neutral'

interface Props {
  open: boolean
  title: string
  description?: string
  eyebrow?: string
  width?: number | string
  tone?: ModalTone
  closable?: boolean
  maskClosable?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  description: '',
  eyebrow: '',
  width: 560,
  tone: 'primary',
  closable: true,
  maskClosable: true,
})

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const visible = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})

const modalMaskStyle: CSSProperties = {
  background: 'rgba(15, 31, 49, 0.36)',
  backdropFilter: 'blur(6px)',
}
</script>

<style scoped>
.app-modal-shell {
  --modal-accent: var(--color-primary, #2f8bff);
  --modal-accent-soft: rgba(47, 139, 255, 0.12);
  position: relative;
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - 72px);
  overflow: hidden;
}

.app-modal-shell--success {
  --modal-accent: #0f9f8f;
  --modal-accent-soft: rgba(15, 159, 143, 0.12);
}

.app-modal-shell--neutral {
  --modal-accent: var(--color-ink, #2f4158);
  --modal-accent-soft: rgba(47, 65, 88, 0.1);
}

.app-modal-shell::before {
  content: '';
  position: absolute;
  top: -120px;
  right: -110px;
  width: 260px;
  height: 260px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--modal-accent-soft), transparent 68%);
  pointer-events: none;
}

.app-modal-shell__header {
  position: relative;
  display: grid;
  flex: none;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: 16px;
  padding: 2px 4px 22px;
  border-bottom: 1px solid var(--color-line, rgba(112, 140, 175, 0.18));
}

.app-modal-shell__icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border: 1px solid color-mix(in srgb, var(--modal-accent) 22%, transparent);
  border-radius: 16px;
  background: var(--modal-accent-soft);
  color: var(--modal-accent);
  font-size: 21px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.app-modal-shell__heading {
  min-width: 0;
}

.app-modal-shell__eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--modal-accent);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
  line-height: 1.4;
  text-transform: uppercase;
}

.app-modal-shell__heading h2 {
  margin: 0;
  color: var(--color-ink-strong, #102033);
  font-size: clamp(20px, 3vw, 24px);
  font-weight: 760;
  letter-spacing: -0.025em;
  line-height: 1.25;
}

.app-modal-shell__heading p {
  max-width: 460px;
  margin: 7px 0 0;
  color: var(--color-ink-soft, #6f8198);
  font-size: 13px;
  line-height: 1.65;
}

.app-modal-shell__body {
  position: relative;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 22px 4px 2px;
  scrollbar-color: rgba(112, 140, 175, 0.32) transparent;
  scrollbar-width: thin;
}

.app-modal-shell__footer {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
  padding: 18px 4px 2px;
  border-top: 1px solid var(--color-line, rgba(112, 140, 175, 0.18));
}

:global(.app-modal-shell-wrap .ant-modal-content) {
  overflow: hidden;
  padding: 24px;
  border: 1px solid rgba(112, 140, 175, 0.18);
  border-radius: 26px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.98), rgba(247, 251, 255, 0.96)),
    #fff;
  box-shadow:
    0 34px 90px rgba(43, 71, 108, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(22px);
}

:global(.app-modal-shell-wrap .ant-modal-close) {
  top: 18px;
  right: 18px;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  color: var(--color-ink-soft, #6f8198);
}

:global(.app-modal-shell-wrap .ant-modal-close:hover) {
  background: rgba(47, 139, 255, 0.08);
  color: var(--color-ink-strong, #102033);
}

:global(.app-modal-shell-wrap .ant-modal-body) {
  padding: 0;
}

@media (max-width: 640px) {
  :global(.app-modal-shell-wrap .ant-modal) {
    max-width: calc(100vw - 24px);
    margin: 12px auto;
  }

  :global(.app-modal-shell-wrap .ant-modal-content) {
    padding: 20px;
    border-radius: 22px;
  }

  .app-modal-shell__header {
    grid-template-columns: 42px minmax(0, 1fr);
    gap: 12px;
    padding-right: 28px;
  }

  .app-modal-shell {
    max-height: calc(100dvh - 64px);
  }

  .app-modal-shell__icon {
    width: 42px;
    height: 42px;
    border-radius: 14px;
  }
}
</style>
