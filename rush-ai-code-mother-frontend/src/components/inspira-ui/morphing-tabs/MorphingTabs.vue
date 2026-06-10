<script setup lang="ts">
interface Props {
  tabs: string[]
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
  (e: 'change', tab: string): void
}>()

const handleClick = (tab: string) => {
  if (tab === props.activeTab) {
    return
  }
  emit('update:activeTab', tab)
  emit('change', tab)
}
</script>

<template>
  <div
    v-if="props.tabs.length"
    :style="{ filter: 'url(#morphingTabsGoo)' }"
    :class="['morphing-tabs', props.class]"
  >
    <button
      v-for="tab in props.tabs"
      :key="tab"
      class="morphing-tab"
      :class="{ 'morphing-tab--active': props.activeTab === tab }"
      :style="{ marginInline: props.activeTab === tab ? `${props.margin}px` : '0px' }"
      type="button"
      @click="handleClick(tab)"
    >
      {{ tab }}
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
  width: max-content;
  height: 42px;
  min-width: 0;
  border-radius: 999px;
  line-height: 1;
}

.morphing-tab {
  position: relative;
  z-index: 1;
  height: 42px;
  padding: 0 18px;
  flex: 0 0 auto;
  border: 0;
  border-radius: 999px;
  background: rgba(222, 232, 245, 0.7);
  color: rgba(42, 59, 82, 0.74);
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
  color: #0f2540;
  transform: translateY(-1px);
}

.morphing-tab--active {
  background: linear-gradient(135deg, #2f8bff 0%, #68c9ff 100%);
  color: #ffffff;
  box-shadow: 0 14px 30px rgba(47, 139, 255, 0.24);
}

.morphing-tabs-filter {
  position: absolute;
  width: 0;
  height: 0;
  display: block;
  overflow: hidden;
  pointer-events: none;
}
</style>
