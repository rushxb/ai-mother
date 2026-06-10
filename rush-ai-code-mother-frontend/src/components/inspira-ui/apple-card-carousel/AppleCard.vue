<script setup lang="ts">
import { onClickOutside } from '@vueuse/core'
import { AnimatePresence, Motion } from 'motion-v'
import { inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { CloseOutlined } from '@ant-design/icons-vue'
import AppleBlurImage from './AppleBlurImage.vue'
import { CarouselKey } from './AppleCarouselContext'

interface CardData {
  src: string
  title: string
  category: string
}

interface Props {
  card: CardData
  index: number
}

const props = defineProps<Props>()
const open = ref(false)
const containerRef = ref<HTMLElement | null>(null)
const carouselContext = inject(CarouselKey)

if (!carouselContext) {
  throw new Error('AppleCard must be used within AppleCardCarousel')
}

const { onCardClose } = carouselContext

function handleOpen() {
  open.value = true
}

function handleClose() {
  open.value = false
  onCardClose(props.index)
}

function handleKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    handleClose()
  }
}

watch(open, (value) => {
  document.body.style.overflow = value ? 'hidden' : 'auto'
})

onMounted(() => {
  window.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown)
  document.body.style.overflow = 'auto'
})

onClickOutside(containerRef, () => {
  if (open.value) {
    handleClose()
  }
})
</script>

<template>
  <Teleport to="body">
    <AnimatePresence>
      <div v-if="open" class="apple-card-modal-shell">
        <Motion
          as="div"
          :initial="{ opacity: 0 }"
          :animate="{ opacity: 1 }"
          :exit="{ opacity: 0 }"
          class="apple-card-modal-backdrop"
        />
        <Motion
          ref="containerRef"
          as="div"
          :initial="{ opacity: 0, y: 20, scale: 0.98 }"
          :animate="{ opacity: 1, y: 0, scale: 1 }"
          :exit="{ opacity: 0, y: 20, scale: 0.98 }"
          class="apple-card-modal"
        >
          <button class="apple-card-close" @click="handleClose">
            <CloseOutlined />
          </button>
          <div class="apple-card-modal-category">{{ card.category }}</div>
          <div class="apple-card-modal-title">{{ card.title }}</div>
          <div class="apple-card-modal-content">
            <slot />
          </div>
        </Motion>
      </div>
    </AnimatePresence>
  </Teleport>

  <Motion
    as="article"
    :initial="{ opacity: 0, y: 20 }"
    :animate="{ opacity: 1, y: 0 }"
    class="apple-card"
    @click="handleOpen"
  >
    <div class="apple-card-gradient"></div>
    <div class="apple-card-copy">
      <div class="apple-card-category">{{ card.category }}</div>
      <div class="apple-card-title">{{ card.title }}</div>
    </div>
    <AppleBlurImage :src="card.src" :alt="card.title" class="apple-card-image" :fill="true" />
  </Motion>
</template>

<style scoped>
.apple-card {
  position: relative;
  z-index: 10;
  display: flex;
  width: min(78vw, 420px);
  aspect-ratio: 16 / 9;
  flex-direction: column;
  justify-content: flex-start;
  overflow: hidden;
  border-radius: 28px;
  background: #f3f6fa;
  cursor: pointer;
  box-shadow:
    0 24px 56px rgba(115, 132, 156, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.52);
}

.apple-card-gradient {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 30;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.5) 0%, rgba(0, 0, 0, 0.08) 48%, transparent 100%);
}

.apple-card-copy {
  position: relative;
  z-index: 40;
  padding: 22px;
}

.apple-card-category,
.apple-card-modal-category {
  color: rgba(255, 255, 255, 0.86);
  font-size: 14px;
  font-weight: 600;
}

.apple-card-title {
  margin-top: 10px;
  max-width: min(300px, 72%);
  color: #ffffff;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.18;
}

.apple-card-image {
  position: absolute;
  inset: 0;
  z-index: 10;
  object-fit: contain;
  padding: 10px;
  background:
    linear-gradient(180deg, rgba(248, 251, 255, 0.94), rgba(232, 239, 248, 0.94)),
    #f3f6fa;
}

.apple-card-modal-shell {
  position: fixed;
  inset: 0;
  z-index: 1200;
  height: 100vh;
  overflow: auto;
}

.apple-card-modal-backdrop {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(18px);
}

.apple-card-modal {
  position: relative;
  z-index: 1210;
  max-width: 960px;
  margin: 40px auto;
  border-radius: 32px;
  background: rgba(255, 255, 255, 0.97);
  padding: 24px 24px 32px;
  box-shadow: 0 32px 80px rgba(15, 23, 42, 0.28);
}

.apple-card-close {
  position: sticky;
  top: 8px;
  margin-left: auto;
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  cursor: pointer;
}

.apple-card-modal-category {
  margin-top: 8px;
  color: #0f172a;
}

.apple-card-modal-title {
  margin-top: 16px;
  color: #111827;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.08;
}

.apple-card-modal-content {
  padding-top: 28px;
}

@media (min-width: 768px) {
  .apple-card {
    width: 520px;
  }

  .apple-card-copy {
    padding: 28px;
  }

  .apple-card-title {
    max-width: 360px;
    font-size: 30px;
  }

  .apple-card-modal {
    padding: 28px 40px 40px;
  }

  .apple-card-modal-title {
    font-size: 56px;
  }
}
</style>
