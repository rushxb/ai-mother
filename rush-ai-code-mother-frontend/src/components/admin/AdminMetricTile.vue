<template>
  <article class="admin-metric-tile" :class="`tone-${tone}`">
    <div class="metric-topline">
      <span>{{ label }}</span>
      <i aria-hidden="true"></i>
    </div>
    <strong>{{ value }}</strong>
    <p>{{ hint }}</p>
  </article>
</template>

<script setup lang="ts">
interface AdminMetricTileProps {
  label: string
  value: string | number
  hint: string
  tone?: 'blue' | 'cyan' | 'green' | 'amber' | 'slate'
}

withDefaults(defineProps<AdminMetricTileProps>(), {
  tone: 'blue',
})
</script>

<style scoped>
.admin-metric-tile {
  position: relative;
  min-height: 148px;
  overflow: hidden;
  padding: 20px;
  border: 1px solid rgba(119, 150, 187, 0.16);
  border-radius: 21px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.94), rgba(247, 251, 255, 0.78)), #fff;
  box-shadow:
    0 16px 48px rgba(70, 101, 140, 0.075),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition:
    transform 0.28s var(--ease-out),
    border-color 0.28s ease,
    box-shadow 0.28s ease;
}

.admin-metric-tile::after {
  content: '';
  position: absolute;
  right: -42px;
  bottom: -60px;
  width: 132px;
  height: 132px;
  border-radius: 50%;
  background: var(--metric-glow, rgba(47, 139, 255, 0.12));
  filter: blur(4px);
}

.admin-metric-tile:hover {
  transform: translateY(-4px);
  border-color: rgba(47, 139, 255, 0.25);
  box-shadow: 0 24px 58px rgba(70, 101, 140, 0.12);
}

.metric-topline {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--color-ink-soft);
  font-size: 12px;
  font-weight: 680;
}

.metric-topline i {
  width: 9px;
  height: 9px;
  border: 2px solid var(--metric-color, var(--color-primary));
  border-radius: 50%;
  box-shadow: 0 0 0 6px var(--metric-glow, rgba(47, 139, 255, 0.1));
}

.admin-metric-tile strong {
  position: relative;
  z-index: 1;
  display: block;
  margin-top: 18px;
  color: var(--color-ink-strong);
  font-size: clamp(26px, 2.5vw, 36px);
  line-height: 1;
  letter-spacing: -0.04em;
}

.admin-metric-tile p {
  position: relative;
  z-index: 1;
  margin: 11px 0 0;
  color: #8a9aaf;
  font-size: 11px;
  line-height: 1.55;
}

.tone-cyan {
  --metric-color: #18a9c7;
  --metric-glow: rgba(24, 169, 199, 0.11);
}

.tone-green {
  --metric-color: #16a085;
  --metric-glow: rgba(22, 160, 133, 0.11);
}

.tone-amber {
  --metric-color: #db8b13;
  --metric-glow: rgba(219, 139, 19, 0.12);
}

.tone-slate {
  --metric-color: #71859c;
  --metric-glow: rgba(113, 133, 156, 0.1);
}
</style>
