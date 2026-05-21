<script setup lang="ts">
import { computed, ref } from 'vue'
import { LeftOutlined, RightOutlined } from '@ant-design/icons-vue'

export interface AppleCardItem {
  id: string | number
  title: string
  subtitle?: string
  imageUrl: string
  meta?: string
  badge?: string
  app?: unknown
}

interface Props {
  items: AppleCardItem[]
  emptyText?: string
}

const props = withDefaults(defineProps<Props>(), {
  emptyText: '暂无内容',
})

const emit = defineEmits<{
  (e: 'primary', item: AppleCardItem): void
  (e: 'secondary', item: AppleCardItem): void
}>()

const activeIndex = ref(0)

const hasItems = computed(() => props.items.length > 0)
const safeActiveIndex = computed(() => Math.min(activeIndex.value, Math.max(props.items.length - 1, 0)))
const activeItem = computed(() => props.items[safeActiveIndex.value])

const setActive = (index: number) => {
  activeIndex.value = index
}

const previous = () => {
  if (!props.items.length) {
    return
  }
  activeIndex.value = (safeActiveIndex.value - 1 + props.items.length) % props.items.length
}

const next = () => {
  if (!props.items.length) {
    return
  }
  activeIndex.value = (safeActiveIndex.value + 1) % props.items.length
}
</script>

<template>
  <div class="apple-card-carousel">
    <div v-if="hasItems && activeItem" class="apple-card-stage">
      <article class="apple-card-hero">
        <img :src="activeItem.imageUrl" :alt="activeItem.title" />
        <div class="apple-card-scrim"></div>
        <div class="apple-card-copy">
          <span v-if="activeItem.badge" class="apple-card-badge">{{ activeItem.badge }}</span>
          <h3>{{ activeItem.title }}</h3>
          <p>{{ activeItem.subtitle || '查看这个案例的页面结构与生成路径。' }}</p>
          <div class="apple-card-actions">
            <button type="button" class="apple-card-action apple-card-action--primary" @click="emit('primary', activeItem)">
              预览
            </button>
            <button type="button" class="apple-card-action" @click="emit('secondary', activeItem)">
              {{ activeItem.meta || '查看' }}
            </button>
          </div>
        </div>
      </article>

      <div class="apple-card-strip" aria-label="精选案例轮播">
        <button
          v-for="(item, index) in items"
          :key="item.id"
          type="button"
          class="apple-card-thumb"
          :class="{ 'apple-card-thumb--active': index === safeActiveIndex }"
          @click="setActive(index)"
        >
          <img :src="item.imageUrl" :alt="item.title" />
          <span>{{ item.title }}</span>
        </button>
      </div>

      <div class="apple-card-controls">
        <button type="button" aria-label="上一个案例" @click="previous">
          <LeftOutlined />
        </button>
        <button type="button" aria-label="下一个案例" @click="next">
          <RightOutlined />
        </button>
      </div>
    </div>
    <div v-else class="apple-card-empty">
      {{ emptyText }}
    </div>
  </div>
</template>

<style scoped>
.apple-card-carousel {
  width: 100%;
}

.apple-card-stage {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 210px;
  gap: 18px;
}

.apple-card-hero {
  position: relative;
  min-height: 430px;
  overflow: hidden;
  border-radius: 30px;
  background: #dfe8f3;
  box-shadow:
    0 28px 64px rgba(52, 76, 109, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.apple-card-hero img {
  width: 100%;
  height: 100%;
  min-height: 430px;
  object-fit: cover;
  display: block;
  transform: scale(1.01);
  transition: transform 0.6s ease;
}

.apple-card-hero:hover img {
  transform: scale(1.055);
}

.apple-card-scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(7, 18, 32, 0.68) 0%, rgba(7, 18, 32, 0.24) 52%, transparent 100%),
    linear-gradient(180deg, transparent 42%, rgba(7, 18, 32, 0.5) 100%);
}

.apple-card-copy {
  position: absolute;
  left: 34px;
  bottom: 32px;
  width: min(470px, calc(100% - 68px));
  color: #ffffff;
}

.apple-card-badge {
  display: inline-flex;
  padding: 8px 12px;
  margin-bottom: 16px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.22);
  backdrop-filter: blur(16px);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.apple-card-copy h3 {
  margin: 0;
  color: #ffffff;
  font-size: clamp(30px, 4vw, 52px);
  line-height: 1.04;
  letter-spacing: -0.04em;
}

.apple-card-copy p {
  margin: 16px 0 0;
  max-width: 430px;
  color: rgba(255, 255, 255, 0.82);
  font-size: 15px;
  line-height: 1.8;
}

.apple-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 24px;
}

.apple-card-action {
  height: 42px;
  padding: 0 18px;
  border: 1px solid rgba(255, 255, 255, 0.42);
  border-radius: 999px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.14);
  backdrop-filter: blur(16px);
  font-weight: 700;
  cursor: pointer;
}

.apple-card-action--primary {
  color: #0d1b2a;
  border-color: #ffffff;
  background: #ffffff;
}

.apple-card-strip {
  display: grid;
  grid-template-rows: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.apple-card-thumb {
  position: relative;
  min-height: 132px;
  overflow: hidden;
  border: 1px solid rgba(104, 132, 175, 0.16);
  border-radius: 22px;
  padding: 0;
  background: #ffffff;
  cursor: pointer;
  box-shadow: 0 16px 34px rgba(114, 137, 170, 0.12);
}

.apple-card-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  filter: saturate(0.86) brightness(0.96);
  transition:
    filter 0.25s ease,
    transform 0.25s ease;
}

.apple-card-thumb span {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.42);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.apple-card-thumb--active {
  border-color: rgba(47, 128, 255, 0.5);
  box-shadow:
    0 20px 42px rgba(47, 128, 255, 0.2),
    0 0 0 4px rgba(47, 128, 255, 0.1);
}

.apple-card-thumb--active img,
.apple-card-thumb:hover img {
  filter: saturate(1.04) brightness(1);
  transform: scale(1.04);
}

.apple-card-controls {
  position: absolute;
  right: 230px;
  top: 18px;
  display: inline-flex;
  gap: 8px;
}

.apple-card-controls button {
  width: 40px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.34);
  border-radius: 999px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(16px);
  cursor: pointer;
}

.apple-card-empty {
  border: 1px dashed rgba(104, 132, 175, 0.24);
  border-radius: 24px;
  padding: 42px;
  color: #73859c;
  text-align: center;
}

@media (max-width: 900px) {
  .apple-card-stage {
    grid-template-columns: 1fr;
  }

  .apple-card-strip {
    display: flex;
    overflow-x: auto;
    padding: 4px 4px 10px;
  }

  .apple-card-thumb {
    flex: 0 0 180px;
  }

  .apple-card-controls {
    right: 18px;
  }
}

@media (max-width: 640px) {
  .apple-card-hero,
  .apple-card-hero img {
    min-height: 360px;
  }

  .apple-card-copy {
    left: 22px;
    bottom: 22px;
    width: calc(100% - 44px);
  }
}
</style>
