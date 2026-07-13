<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

export interface PrimaryNavigationItem {
  label: string
  path: string
}

interface Props {
  items: PrimaryNavigationItem[]
  currentPath: string
}

const props = defineProps<Props>()

const navigationItems = computed(() =>
  props.items.filter((item) => item.label.trim().length > 0 && item.path.trim().length > 0),
)

const isItemActive = (item: PrimaryNavigationItem) =>
  item.path === '/' ? props.currentPath === '/' : props.currentPath.startsWith(item.path)
</script>

<template>
  <div v-if="navigationItems.length" class="primary-navigation" role="list">
    <RouterLink
      v-for="item in navigationItems"
      :key="item.path"
      :to="item.path"
      class="primary-navigation__link"
      :class="{ 'primary-navigation__link--active': isItemActive(item) }"
      :aria-current="isItemActive(item) ? 'page' : undefined"
      role="listitem"
    >
      <span>{{ item.label }}</span>
    </RouterLink>
  </div>
</template>

<style scoped>
.primary-navigation {
  position: relative;
  display: inline-flex;
  align-items: stretch;
  width: max-content;
  min-width: 0;
  height: 44px;
}

.primary-navigation::after {
  position: absolute;
  right: 4px;
  bottom: 0;
  left: 4px;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(126, 158, 196, 0.2) 14%,
    rgba(126, 158, 196, 0.2) 86%,
    transparent
  );
  content: '';
  pointer-events: none;
}

.primary-navigation__link {
  position: relative;
  z-index: 1;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  min-width: 58px;
  padding: 0 14px;
  color: var(--color-ink-soft, #6f8198);
  font-size: 13px;
  font-weight: 620;
  letter-spacing: 0.01em;
  line-height: 1;
  text-decoration: none;
  white-space: nowrap;
  transition:
    color 0.22s var(--ease-out, ease),
    transform 0.22s var(--ease-out, ease);
}

.primary-navigation__link::before {
  position: absolute;
  inset: 5px 3px;
  z-index: -1;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  content: '';
  opacity: 0;
  transform: scale(0.94);
  transition:
    opacity 0.22s var(--ease-out, ease),
    transform 0.22s var(--ease-out, ease),
    background-color 0.22s var(--ease-out, ease),
    border-color 0.22s var(--ease-out, ease);
}

.primary-navigation__link::after {
  position: absolute;
  bottom: 0;
  left: 50%;
  width: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: linear-gradient(90deg, #2f8bff, #50b9ed);
  box-shadow: 0 -2px 10px rgba(47, 139, 255, 0.26);
  content: '';
  transform: translateX(-50%);
  transition: width 0.28s var(--ease-out, ease);
}

.primary-navigation__link:hover,
.primary-navigation__link:focus-visible {
  color: var(--color-ink-strong, #102033);
}

.primary-navigation__link:hover::before,
.primary-navigation__link:focus-visible::before {
  border-color: rgba(126, 158, 196, 0.13);
  background: rgba(238, 244, 251, 0.72);
  opacity: 1;
  transform: scale(1);
}

.primary-navigation__link:focus-visible {
  outline: none;
}

.primary-navigation__link:focus-visible::before {
  border-color: rgba(47, 139, 255, 0.34);
  box-shadow: 0 0 0 3px rgba(47, 139, 255, 0.1);
}

.primary-navigation__link--active {
  color: var(--color-primary-strong, #176fdd);
  font-weight: 720;
}

.primary-navigation__link--active::after {
  width: calc(100% - 24px);
}

@media (max-width: 640px) {
  .primary-navigation {
    height: 40px;
  }

  .primary-navigation__link {
    min-width: 52px;
    padding: 0 12px;
    font-size: 12px;
  }

  .primary-navigation__link::before {
    inset-block: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .primary-navigation__link,
  .primary-navigation__link::before,
  .primary-navigation__link::after {
    transition: none;
  }
}
</style>
