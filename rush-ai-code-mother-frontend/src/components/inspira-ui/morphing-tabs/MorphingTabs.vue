<script setup lang="ts">
interface MorphingTabItem {
  key: string
  label: string
}

interface Props {
  tabs: MorphingTabItem[]
  activeTab: string
  margin?: number
  class?: string
  blurStdDeviation?: number
}

const props = withDefaults(defineProps<Props>(), {
  margin: 14,
  blurStdDeviation: 6,
})

const emit = defineEmits<{
  (e: 'update:activeTab', tab: string): void
}>()

const handleClick = (tabKey: string) => {
  emit('update:activeTab', tabKey)
}
</script>

<template>
  <div
    v-if="props.tabs.length"
    style="filter: url('#morphingTabsGoo')"
    :class="['morphing-tabs', props.class]"
  >
    <button
      v-for="tab in props.tabs"
      :key="tab.key"
      class="morphing-tab"
      :class="{ 'morphing-tab--active': props.activeTab === tab.key }"
      :style="{ marginInline: props.activeTab === tab.key ? `${props.margin}px` : '0px' }"
      type="button"
      @click="handleClick(tab.key)"
    >
      {{ tab.label }}
    </button>

    <svg class="morphing-tabs-filter" xmlns="http://www.w3.org/2000/svg" version="1.1">
      <defs>
        <filter
          id="morphingTabsGoo"
          x="-50%"
          y="-50%"
          width="200%"
          height="200%"
          color-interpolation-filters="sRGB"
        >
          <feGaussianBlur in="SourceGraphic" :stdDeviation="props.blurStdDeviation" result="blur" />
          <feColorMatrix
            in="blur"
            type="matrix"
            values="1 0 0 0 0
                    0 1 0 0 0
                    0 0 1 0 0
                    0 0 0 34 -12"
            result="goo"
          />
          <feComposite in="SourceGraphic" in2="goo" operator="atop" />
        </filter>
      </defs>
    </svg>
  </div>
</template>

<style scoped>
.morphing-tabs {
  position: relative;
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
}

.morphing-tab {
  position: relative;
  z-index: 1;
  height: 42px;
  padding: 0 18px;
  border: 0;
  border-radius: 999px;
  background: rgba(162, 189, 220, 0.18);
  color: rgba(226, 237, 250, 0.72);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    margin 0.44s cubic-bezier(0.2, 0.8, 0.2, 1),
    background 0.32s ease,
    color 0.32s ease,
    transform 0.32s ease;
}

.morphing-tab:hover {
  color: #ffffff;
  transform: translateY(-1px);
}

.morphing-tab--active {
  background: linear-gradient(135deg, #a5efff 0%, #77bbff 100%);
  color: #051120;
  box-shadow: 0 16px 36px rgba(114, 191, 255, 0.26);
}

.morphing-tabs-filter {
  position: absolute;
  width: 0;
  height: 0;
}
</style>
