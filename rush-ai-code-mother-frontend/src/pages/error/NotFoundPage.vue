<template>
  <main class="not-found-page">
    <AmbientTechCanvas class="not-found-canvas" :density="44" />
    <div class="ambient-grid" aria-hidden="true"></div>

    <Motion
      as="section"
      class="not-found-shell"
      aria-labelledby="not-found-title"
      :initial="{ opacity: 0, y: 18, filter: 'blur(8px)' }"
      :animate="{ opacity: 1, y: 0, filter: 'blur(0px)' }"
      :transition="{ duration: 0.58, ease: [0.22, 1, 0.36, 1] }"
    >
      <div class="status-column">
        <span class="route-status"><i></i> ROUTE NOT FOUND</span>
        <div class="status-code" aria-hidden="true">
          <span>4</span>
          <span class="zero-orbit"><i></i><b></b></span>
          <span>4</span>
        </div>
        <p class="trace-label">ERROR / NAVIGATION / {{ route.name ?? 'UNKNOWN' }}</p>
      </div>

      <div class="content-column">
        <p class="eyebrow">RUSH WORKSPACE RECOVERY</p>
        <h1 id="not-found-title">当前路径没有连接到可用页面。</h1>
        <p class="description">
          目标页面可能已被移动、删除，或地址输入有误。你可以返回个人工作台继续创建应用，也可以回到上一页恢复刚才的操作。
        </p>

        <div class="route-readout" aria-label="当前无效路径">
          <span>REQUEST PATH</span>
          <code>{{ route.fullPath }}</code>
        </div>

        <div class="actions">
          <button type="button" class="primary-action" @click="goHome">
            <HomeOutlined />
            返回个人工作台
            <ArrowRightOutlined />
          </button>
          <button type="button" class="secondary-action" @click="goBack">
            <ArrowLeftOutlined />
            返回上一页
          </button>
        </div>

        <div class="support-strip">
          <span><i></i> 系统服务正常</span>
          <span>错误代码：ROUTE_404</span>
          <span>建议操作：首页 / 上一页</span>
        </div>
      </div>
    </Motion>
  </main>
</template>

<script setup lang="ts">
import { ArrowLeftOutlined, ArrowRightOutlined, HomeOutlined } from '@ant-design/icons-vue'
import { Motion } from 'motion-v'
import { useRoute, useRouter } from 'vue-router'
import AmbientTechCanvas from '@/components/visual/AmbientTechCanvas.vue'

const router = useRouter()
const route = useRoute()

const goHome = () => router.replace('/')

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void goHome()
}
</script>

<style scoped>
.not-found-page {
  position: relative;
  min-height: calc(100vh - 72px);
  display: grid;
  place-items: center;
  overflow: hidden;
  padding: clamp(40px, 7vw, 100px) 24px;
  background:
    radial-gradient(circle at 12% 16%, rgba(181, 220, 255, 0.5), transparent 32%),
    radial-gradient(circle at 86% 20%, rgba(185, 242, 230, 0.38), transparent 30%),
    linear-gradient(145deg, #fbfdff 0%, #f3f8fd 50%, #eef7f7 100%);
}

.not-found-page::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(
    115deg,
    transparent 20%,
    rgba(255, 255, 255, 0.7) 48%,
    transparent 72%
  );
  opacity: 0.46;
}

.not-found-canvas,
.ambient-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.not-found-canvas {
  opacity: 0.56;
}

.ambient-grid {
  opacity: 0.32;
  background-image:
    linear-gradient(rgba(90, 126, 166, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(90, 126, 166, 0.08) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(circle at center, black 12%, transparent 74%);
}

.not-found-shell {
  position: relative;
  z-index: 1;
  width: min(1080px, 100%);
  display: grid;
  grid-template-columns: minmax(280px, 0.78fr) minmax(0, 1.22fr);
  overflow: hidden;
  border: 1px solid rgba(132, 164, 201, 0.25);
  border-radius: 34px;
  background: rgba(255, 255, 255, 0.68);
  box-shadow:
    0 34px 100px rgba(66, 100, 142, 0.15),
    inset 0 1px 0 rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(24px) saturate(140%);
}

.status-column {
  position: relative;
  min-height: 520px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 36px;
  overflow: hidden;
  border-right: 1px solid rgba(132, 164, 201, 0.18);
  background:
    radial-gradient(circle at 50% 44%, rgba(47, 139, 255, 0.18), transparent 38%),
    linear-gradient(160deg, rgba(235, 245, 255, 0.9), rgba(235, 250, 247, 0.66));
}

.status-column::before,
.status-column::after {
  content: '';
  position: absolute;
  width: 220px;
  height: 220px;
  border: 1px solid rgba(47, 139, 255, 0.13);
  border-radius: 50%;
}

.status-column::before {
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.status-column::after {
  top: 50%;
  left: 50%;
  width: 300px;
  height: 300px;
  border-style: dashed;
  transform: translate(-50%, -50%);
  animation: orbit-rotate 30s linear infinite;
}

.route-status {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: 8px;
  height: 32px;
  padding: 0 13px;
  border: 1px solid rgba(47, 139, 255, 0.14);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.62);
  color: #477097;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.route-status i,
.support-strip i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-secondary, #3cc9bb);
  box-shadow: 0 0 0 5px rgba(60, 201, 187, 0.1);
}

.status-code {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #17304b;
  font-size: clamp(76px, 9vw, 126px);
  font-weight: 760;
  line-height: 1;
  letter-spacing: -0.08em;
  text-shadow: 0 16px 42px rgba(57, 96, 136, 0.14);
}

.zero-orbit {
  position: relative;
  width: 0.76em;
  height: 0.76em;
  display: inline-block;
  margin: 0 0.04em;
  border: 0.08em solid rgba(47, 139, 255, 0.76);
  border-radius: 50%;
  box-shadow:
    inset 0 0 0 0.12em rgba(255, 255, 255, 0.62),
    0 0 0 0.02em rgba(47, 139, 255, 0.12),
    0 0 38px rgba(47, 139, 255, 0.2);
}

.zero-orbit i,
.zero-orbit b {
  position: absolute;
  top: 50%;
  left: 50%;
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.zero-orbit i {
  width: 0.12em;
  height: 0.12em;
  background: #3cc9bb;
}

.zero-orbit b {
  width: 1.24em;
  height: 0.36em;
  border: 1px solid rgba(47, 139, 255, 0.34);
  transform: translate(-50%, -50%) rotate(-18deg);
}

.trace-label {
  position: relative;
  z-index: 1;
  margin: 0;
  color: #758ba4;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.12em;
}

.content-column {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(40px, 6vw, 72px);
}

.eyebrow {
  margin: 0 0 16px;
  color: var(--color-primary, #2f8bff);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.19em;
}

h1 {
  max-width: 600px;
  margin: 0;
  color: var(--color-ink-strong, #102033);
  font-size: clamp(34px, 4.2vw, 54px);
  font-weight: 720;
  line-height: 1.12;
  letter-spacing: -0.035em;
}

.description {
  max-width: 620px;
  margin: 22px 0 0;
  color: var(--color-ink-soft, #6f8198);
  font-size: 15px;
  line-height: 1.82;
}

.route-readout {
  min-width: 0;
  display: grid;
  gap: 7px;
  margin-top: 28px;
  padding: 15px 17px;
  border: 1px solid rgba(112, 140, 175, 0.16);
  border-radius: 16px;
  background: rgba(242, 247, 252, 0.76);
}

.route-readout span {
  color: #8b9aae;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.15em;
}

.route-readout code {
  overflow: hidden;
  color: #36516f;
  font-family: 'Cascadia Code', 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}

.actions button {
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 0 18px;
  border-radius: 14px;
  font-size: 13px;
  font-weight: 720;
  cursor: pointer;
  transition:
    transform 0.22s var(--ease-out),
    box-shadow 0.22s ease,
    border-color 0.22s ease;
}

.actions button:hover {
  transform: translateY(-2px);
}

.primary-action {
  border: 0;
  background: linear-gradient(135deg, #247ff0, #53bdf0);
  color: #ffffff;
  box-shadow: 0 16px 34px rgba(47, 139, 255, 0.24);
}

.secondary-action {
  border: 1px solid rgba(112, 140, 175, 0.2);
  background: rgba(255, 255, 255, 0.72);
  color: #405771;
  box-shadow: 0 12px 28px rgba(70, 99, 134, 0.08);
}

.support-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
  margin-top: 34px;
  padding-top: 20px;
  border-top: 1px solid rgba(112, 140, 175, 0.14);
  color: #8494a8;
  font-size: 10px;
  letter-spacing: 0.04em;
}

.support-strip span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

@keyframes orbit-rotate {
  to {
    transform: translate(-50%, -50%) rotate(360deg);
  }
}

@media (max-width: 820px) {
  .not-found-page {
    padding: 24px 16px;
  }

  .not-found-shell {
    grid-template-columns: 1fr;
    border-radius: 26px;
  }

  .status-column {
    min-height: 250px;
    padding: 24px;
    border-right: 0;
    border-bottom: 1px solid rgba(132, 164, 201, 0.18);
  }

  .status-column::before {
    width: 170px;
    height: 170px;
  }

  .status-column::after {
    width: 230px;
    height: 230px;
  }

  .status-code {
    font-size: 78px;
  }

  .content-column {
    padding: 34px 26px;
  }

  h1 {
    font-size: clamp(30px, 9vw, 42px);
  }
}

@media (max-width: 520px) {
  .actions {
    display: grid;
  }

  .actions button {
    width: 100%;
  }

  .support-strip {
    display: grid;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-column::after {
    animation: none;
  }
}
</style>
