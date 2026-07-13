<template>
  <div id="userLoginSuccessPage">
    <BubblesBackground
      class="success-bubbles"
      :bubble-count="20"
      color1="#9bd8ff"
      color2="#b8caff"
      color3="#abefdf"
      :speed="0.64"
      :blur="0.36"
      :min-radius="0.56"
      :max-radius="1.58"
      :min-opacity="0.12"
      :max-opacity="0.34"
      :spread-x="16"
      :spread-y="10"
      :depth-min="-8"
      :depth-max="1"
      :drift-strength="0.48"
      :camera-z="19"
    />
    <div class="success-overlay"></div>

    <main class="success-shell">
      <section class="success-panel">
        <span class="status-pill">ACCESS GRANTED</span>

        <BoxReveal class="success-copy" :duration="0.86" :delay="0.16" box-color="#4d9dff" :y-offset="20">
          <p class="success-kicker">LOGIN SUCCESS</p>
          <h1>{{ titleText }}</h1>
          <p class="success-desc">{{ descText }}</p>
        </BoxReveal>

        <div class="progress-track" aria-hidden="true">
          <span class="progress-bar"></span>
        </div>

        <p class="jump-hint">正在进入目标页面...</p>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSafeSameOriginPath } from '@/utils/safeRedirect'

const router = useRouter()
const route = useRoute()

let timer: ReturnType<typeof setTimeout> | null = null

const getSafeRedirect = () => getSafeSameOriginPath(route.query.redirect)

const redirectTarget = computed(() => getSafeRedirect())

const titleText = computed(() =>
  redirectTarget.value === '/' ? '登录完成，正在进入工作台。' : '登录完成，正在载入你的目标页面。',
)

const descText = computed(() =>
  redirectTarget.value === '/'
    ? '当前会话已建立，马上进入主工作区。'
    : '将保留原始跳转目标，并在过渡完成后自动进入。',
)

onMounted(() => {
  timer = setTimeout(async () => {
    await router.replace(redirectTarget.value)
  }, 1400)
})

onUnmounted(() => {
  if (timer) {
    clearTimeout(timer)
  }
})
</script>

<style scoped>
#userLoginSuccessPage {
  --text-strong: #102033;
  --text-soft: rgba(47, 65, 88, 0.7);
  position: relative;
  min-height: 100%;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 14%, rgba(207, 233, 255, 0.74), transparent 34%),
    radial-gradient(circle at 84% 18%, rgba(220, 245, 238, 0.72), transparent 32%),
    linear-gradient(135deg, #fcfdff 0%, #f6f9fd 52%, #f2f8f8 100%);
}

#userLoginSuccessPage::before {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0.3;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(120, 144, 174, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(120, 144, 174, 0.08) 1px, transparent 1px);
  background-size: 46px 46px;
  mask-image: linear-gradient(180deg, transparent, black 18%, black 82%, transparent);
}

.success-bubbles {
  position: absolute;
  inset: -6%;
  width: 112%;
  height: 112%;
  opacity: 0.9;
}

.success-overlay {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.56), rgba(255, 255, 255, 0.28)),
    radial-gradient(circle at center, rgba(255, 255, 255, 0.12), rgba(255, 255, 255, 0.62));
}

.success-shell {
  position: relative;
  z-index: 1;
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 32px;
}

.success-panel {
  width: min(640px, 100%);
  padding: 42px 40px 36px;
  border: 1px solid rgba(136, 168, 204, 0.28);
  border-radius: 30px;
  background: rgba(255, 255, 255, 0.62);
  box-shadow:
    0 24px 70px rgba(67, 96, 140, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(18px);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  height: 32px;
  padding: 0 14px;
  border-radius: 999px;
  background: rgba(77, 157, 255, 0.1);
  color: #2c7fe8;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

.success-copy {
  margin-top: 18px;
}

.success-kicker {
  margin: 0 0 12px;
  color: #5d7ca3;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.success-copy :deep(h1) {
  margin: 0;
  color: var(--text-strong);
  font-size: clamp(30px, 4vw, 42px);
  line-height: 1.08;
  font-weight: 700;
}

.success-desc {
  margin: 16px 0 0;
  max-width: 32rem;
  color: var(--text-soft);
  font-size: 15px;
  line-height: 1.7;
}

.progress-track {
  position: relative;
  margin-top: 28px;
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(145, 174, 209, 0.18);
}

.progress-bar {
  display: block;
  height: 100%;
  width: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #80bfff 0%, #61a8ff 46%, #96ead6 100%);
  transform-origin: left center;
  animation: progress-fill 1.26s cubic-bezier(0.22, 1, 0.36, 1) forwards;
}

.jump-hint {
  margin: 14px 0 0;
  color: rgba(79, 101, 130, 0.86);
  font-size: 13px;
  line-height: 1.5;
}

@keyframes progress-fill {
  from {
    transform: scaleX(0);
  }

  to {
    transform: scaleX(1);
  }
}

@media (max-width: 640px) {
  .success-shell {
    padding: 18px;
  }

  .success-panel {
    padding: 34px 24px 28px;
    border-radius: 24px;
  }
}
</style>
