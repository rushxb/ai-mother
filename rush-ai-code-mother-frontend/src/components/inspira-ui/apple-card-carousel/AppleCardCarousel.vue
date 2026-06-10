<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { LeftOutlined, RightOutlined } from '@ant-design/icons-vue'
import { CarouselKey } from './AppleCarouselContext'

interface Props {
  initialScroll?: number
}

const props = withDefaults(defineProps<Props>(), {
  initialScroll: 0,
})

const carouselRef = ref<HTMLDivElement | null>(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(true)
const currentIndex = ref(0)

const isMobile = computed(() => {
  if (typeof window === 'undefined') {
    return false
  }
  return window.innerWidth < 768
})

function checkScrollability() {
  if (!carouselRef.value) {
    return
  }
  const { scrollLeft, scrollWidth, clientWidth } = carouselRef.value
  canScrollLeft.value = scrollLeft > 0
  canScrollRight.value = scrollLeft < scrollWidth - clientWidth - 1
}

function scrollLeft() {
  const distance = isMobile.value ? Math.min(window.innerWidth * 0.82, 440) : 536
  carouselRef.value?.scrollBy({ left: -distance, behavior: 'smooth' })
}

function scrollRight() {
  const distance = isMobile.value ? Math.min(window.innerWidth * 0.82, 440) : 536
  carouselRef.value?.scrollBy({ left: distance, behavior: 'smooth' })
}

function handleCardClose(index: number) {
  if (!carouselRef.value) {
    return
  }
  const cardWidth = isMobile.value ? Math.min(window.innerWidth * 0.78, 420) : 520
  const gap = isMobile.value ? 16 : 16
  carouselRef.value.scrollTo({
    left: (cardWidth + gap) * index,
    behavior: 'smooth',
  })
  currentIndex.value = index
}

onMounted(() => {
  if (carouselRef.value) {
    carouselRef.value.scrollLeft = props.initialScroll
    checkScrollability()
  }
})

watch(
  () => props.initialScroll,
  (value) => {
    if (!carouselRef.value) {
      return
    }
    carouselRef.value.scrollLeft = value
    checkScrollability()
  },
)

provide(CarouselKey, {
  onCardClose: handleCardClose,
  currentIndex,
})
</script>

<template>
  <div class="apple-carousel">
    <div ref="carouselRef" class="apple-carousel-track" @scroll="checkScrollability">
      <div class="apple-carousel-fade"></div>
      <div class="apple-carousel-inner">
        <slot />
      </div>
    </div>
    <div class="apple-carousel-actions">
      <button :disabled="!canScrollLeft" @click="scrollLeft">
        <LeftOutlined />
      </button>
      <button :disabled="!canScrollRight" @click="scrollRight">
        <RightOutlined />
      </button>
    </div>
  </div>
</template>

<style scoped>
.apple-carousel {
  position: relative;
  width: 100%;
}

.apple-carousel-track {
  display: flex;
  width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  scroll-behavior: smooth;
  padding: 20px 0 24px;
  scrollbar-width: none;
}

.apple-carousel-track::-webkit-scrollbar {
  display: none;
}

.apple-carousel-fade {
  position: absolute;
  right: 0;
  z-index: 2;
  width: 5%;
  height: 100%;
  pointer-events: none;
  background: linear-gradient(to left, rgba(244, 248, 252, 1), rgba(244, 248, 252, 0));
}

.apple-carousel-inner {
  display: flex;
  flex-direction: row;
  justify-content: flex-start;
  gap: 16px;
  padding-left: 4px;
  margin: 0 auto;
}

.apple-carousel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-right: 10px;
}

.apple-carousel-actions button {
  position: relative;
  z-index: 4;
  display: flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  background: #eef3f8;
  color: #5f7289;
  cursor: pointer;
}

.apple-carousel-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

@media (min-width: 768px) {
  .apple-carousel-track {
    padding: 28px 0 32px;
  }
}
</style>
