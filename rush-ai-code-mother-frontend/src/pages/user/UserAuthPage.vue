<template>
  <div id="userAuthPage">
    <div class="ambient-grid"></div>
    <div class="ambient-glow glow-one"></div>
    <div class="ambient-glow glow-two"></div>
    <div class="page-shell">
      <section class="brand-panel">
        <div class="brand-copy">
          <div class="eyebrow">AI APPLICATION STUDIO</div>
          <h1 class="title">{{ heroTitle }}</h1>
          <p class="desc">{{ heroDesc }}</p>
          <div class="hero-scene">
            <svg
              class="hero-svg"
              viewBox="0 0 520 240"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <defs>
                <linearGradient id="flowGradient" x1="73" y1="58" x2="409" y2="178">
                  <stop offset="0%" stop-color="#7FD6FF" stop-opacity="0.08" />
                  <stop offset="45%" stop-color="#2F80FF" stop-opacity="0.92" />
                  <stop offset="100%" stop-color="#54D2FF" stop-opacity="0.18" />
                </linearGradient>
                <linearGradient id="flowGradientSoft" x1="124" y1="90" x2="445" y2="185">
                  <stop offset="0%" stop-color="#A7E6FF" stop-opacity="0.02" />
                  <stop offset="50%" stop-color="#2F80FF" stop-opacity="0.6" />
                  <stop offset="100%" stop-color="#2CC0D2" stop-opacity="0.04" />
                </linearGradient>
                <radialGradient id="coreGlow" cx="0" cy="0" r="1" gradientUnits="userSpaceOnUse"
                  gradientTransform="translate(386 96) rotate(90) scale(96 96)"
                >
                  <stop offset="0%" stop-color="#8ADBFF" stop-opacity="0.9" />
                  <stop offset="55%" stop-color="#2F80FF" stop-opacity="0.28" />
                  <stop offset="100%" stop-color="#2F80FF" stop-opacity="0" />
                </radialGradient>
                <filter id="softBlur" x="-20%" y="-20%" width="140%" height="140%">
                  <feGaussianBlur stdDeviation="8" />
                </filter>
                <pattern id="microGrid" x="0" y="0" width="28" height="28" patternUnits="userSpaceOnUse">
                  <path d="M28 0H0V28" stroke="rgba(104,132,175,0.12)" stroke-width="1" />
                </pattern>
              </defs>

              <rect x="0.5" y="0.5" width="519" height="239" rx="30" fill="url(#microGrid)" />
              <ellipse
                cx="386"
                cy="96"
                rx="112"
                ry="92"
                fill="url(#coreGlow)"
                class="svg-glow"
                filter="url(#softBlur)"
              />

              <g class="svg-rings ring-group-primary">
                <circle cx="154" cy="92" r="76" class="svg-ring ring-large" />
                <circle cx="154" cy="92" r="44" class="svg-ring ring-small" />
              </g>
              <g class="svg-rings ring-group-secondary">
                <circle cx="388" cy="84" r="54" class="svg-ring ring-medium" />
                <circle cx="388" cy="84" r="22" class="svg-ring ring-core" />
              </g>

              <path
                d="M72 146C122 126 164 116 208 122C250 128 286 152 332 152C372 152 408 132 450 94"
                class="svg-flow flow-main"
                stroke="url(#flowGradient)"
              />
              <path
                d="M98 74C146 74 180 90 218 104C258 120 304 126 346 118C386 110 414 92 444 56"
                class="svg-flow flow-secondary"
                stroke="url(#flowGradientSoft)"
              />
              <path
                d="M156 92L206 118L256 110L306 146L388 84"
                class="svg-link"
              />

              <g class="svg-node node-left">
                <circle cx="96" cy="150" r="7" class="svg-node-core" />
                <circle cx="96" cy="150" r="17" class="svg-node-halo" />
              </g>
              <g class="svg-node node-mid">
                <circle cx="256" cy="110" r="7" class="svg-node-core" />
                <circle cx="256" cy="110" r="17" class="svg-node-halo" />
              </g>
              <g class="svg-node node-right">
                <circle cx="388" cy="84" r="8" class="svg-node-core" />
                <circle cx="388" cy="84" r="21" class="svg-node-halo" />
              </g>
              <g class="svg-node node-end">
                <circle cx="448" cy="58" r="6" class="svg-node-core" />
                <circle cx="448" cy="58" r="15" class="svg-node-halo" />
              </g>

              <g class="svg-bars">
                <rect x="330" y="144" width="18" height="34" rx="9" class="bar bar-one" />
                <rect x="358" y="126" width="18" height="52" rx="9" class="bar bar-two" />
                <rect x="386" y="112" width="18" height="66" rx="9" class="bar bar-three" />
                <rect x="414" y="136" width="18" height="42" rx="9" class="bar bar-four" />
              </g>
            </svg>
            <div class="scene-core">
              <span class="scene-core-label">{{ isRegisterMode ? 'AUTH SETUP' : 'WORKSPACE ACCESS' }}</span>
              <strong>{{ isRegisterMode ? 'Create secure onboarding' : 'Enter your active session' }}</strong>
            </div>
          </div>
        </div>
        <div class="metrics">
          <div v-for="item in metrics" :key="item.label" class="metric-card">
            <span class="metric-label">{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
        <div class="signal-board">
          <div class="signal-header">
            <span class="signal-dot"></span>
            <span>{{ isRegisterMode ? 'Launch Checklist' : 'Workspace Signal' }}</span>
          </div>
          <div class="signal-list">
            <div v-for="item in signalItems" :key="item.label" class="signal-item">
              <span>{{ item.label }}</span>
              <span>{{ item.value }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="login-panel">
        <div class="panel-decoration"></div>
        <div class="panel-content">
          <div class="form-head">
            <div class="auth-switch">
              <button
                type="button"
                class="switch-option"
                :class="{ active: !isRegisterMode }"
                @click="switchMode('login')"
              >
                登录
              </button>
              <button
                type="button"
                class="switch-option"
                :class="{ active: isRegisterMode }"
                @click="switchMode('register')"
              >
                注册
              </button>
            </div>
            <p class="form-kicker">{{ isRegisterMode ? 'CREATE ACCESS' : 'WELCOME BACK' }}</p>
            <h2>{{ isRegisterMode ? '创建你的工作台账号' : '登录工作台' }}</h2>
            <p>
              {{
                isRegisterMode
                  ? '完成账号创建后即可进入应用生成与调试流程。'
                  : '继续管理你的 AI 应用、会话与部署流程。'
              }}
            </p>
          </div>

          <div class="form-stage">
            <Transition name="auth-card" mode="out-in">
              <a-form
                v-if="!isRegisterMode"
                key="login"
                :model="loginFormState"
                name="login"
                autocomplete="off"
                layout="vertical"
                class="auth-form"
                @finish="handleLoginSubmit"
              >
                <a-form-item
                  label="账号"
                  name="userAccount"
                  :rules="[{ required: true, message: '请输入账号' }]"
                >
                  <a-input
                    v-model:value="loginFormState.userAccount"
                    size="large"
                    placeholder="输入账号"
                  />
                </a-form-item>
                <a-form-item
                  label="密码"
                  name="userPassword"
                  :rules="[
                    { required: true, message: '请输入密码' },
                    { min: 8, message: '密码长度不能小于 8 位' },
                  ]"
                >
                  <a-input-password
                    v-model:value="loginFormState.userPassword"
                    size="large"
                    placeholder="输入密码"
                  />
                </a-form-item>

                <div class="assist-row">
                  <span class="assist-text">首次使用可先创建账号</span>
                  <button type="button" class="register-link" @click="switchMode('register')">
                    去注册
                  </button>
                </div>

                <a-form-item class="submit-row">
                  <a-button
                    type="primary"
                    html-type="submit"
                    size="large"
                    :loading="loginSubmitting"
                    class="submit-button"
                  >
                    登录并进入
                  </a-button>
                </a-form-item>
              </a-form>

              <a-form
                v-else
                key="register"
                :model="registerFormState"
                name="register"
                autocomplete="off"
                layout="vertical"
                class="auth-form"
                @finish="handleRegisterSubmit"
              >
                <a-form-item
                  label="账号"
                  name="userAccount"
                  :rules="[{ required: true, message: '请输入账号' }]"
                >
                  <a-input
                    v-model:value="registerFormState.userAccount"
                    size="large"
                    placeholder="设置账号"
                  />
                </a-form-item>
                <a-form-item
                  label="密码"
                  name="userPassword"
                  :rules="[
                    { required: true, message: '请输入密码' },
                    { min: 8, message: '密码长度不能小于 8 位' },
                  ]"
                >
                  <a-input-password
                    v-model:value="registerFormState.userPassword"
                    size="large"
                    placeholder="设置密码"
                  />
                </a-form-item>
                <a-form-item
                  label="确认密码"
                  name="checkPassword"
                  :rules="[
                    { required: true, message: '请确认密码' },
                    { min: 8, message: '密码长度不能小于 8 位' },
                    { validator: validateCheckPassword },
                  ]"
                >
                  <a-input-password
                    v-model:value="registerFormState.checkPassword"
                    size="large"
                    placeholder="再次输入密码"
                  />
                </a-form-item>

                <div class="assist-row">
                  <span class="assist-text">已经有账号可以直接进入</span>
                  <button type="button" class="register-link" @click="switchMode('login')">
                    去登录
                  </button>
                </div>

                <a-form-item class="submit-row">
                  <a-button
                    type="primary"
                    html-type="submit"
                    size="large"
                    :loading="registerSubmitting"
                    class="submit-button"
                  >
                    创建账号
                  </a-button>
                </a-form-item>
              </a-form>
            </Transition>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { userLogin, userRegister } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'

type AuthMode = 'login' | 'register'

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()

const loginSubmitting = ref(false)
const registerSubmitting = ref(false)

const loginFormState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const registerFormState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const isRegisterMode = computed(() => route.path === '/user/register')

const heroTitle = computed(() =>
  isRegisterMode.value
    ? '建立统一入口，让每一次产品试验都有明确起点。'
    : '把想法整理成产品，而不是停留在草稿里。',
)

const heroDesc = computed(() =>
  isRegisterMode.value
    ? '创建账号后即可进入同一套 AI 应用工作流，从需求描述、页面成型到对话调试，减少切换与重复配置。'
    : '面向高频迭代场景的 AI 应用生成台，从需求描述、页面搭建到对话调试，尽量把过程收敛到更短路径。',
)

const metrics = computed(() =>
  isRegisterMode.value
    ? [
        { label: 'Onboarding', value: '账号创建只保留必要步骤' },
        { label: 'Workspace', value: '注册后直接进入统一工作台' },
        { label: 'Security', value: '密码校验与确认流程完整' },
      ]
    : [
        { label: 'Prompt to App', value: '分钟级成型' },
        { label: 'Workflow', value: '生成 · 编辑 · 部署' },
        { label: 'Collaboration', value: '适合团队快速验证' },
      ],
)

const signalItems = computed(() =>
  isRegisterMode.value
    ? [
        { label: '接入方式', value: '账号创建后立即可用' },
        { label: '适用场景', value: 'MVP / Demo / 内部工具' },
        { label: '当前入口', value: '新账号注册' },
      ]
    : [
        { label: '体验方向', value: '精炼、清晰、可执行' },
        { label: '适用场景', value: 'MVP / Demo / 内部工具' },
        { label: '当前入口', value: '账户登录' },
      ],
)

const switchMode = async (mode: AuthMode) => {
  const targetPath = mode === 'register' ? '/user/register' : '/user/login'
  if (route.path !== targetPath) {
    await router.replace({
      path: targetPath,
      query: route.query,
    })
  }
}

const getSafeRedirect = () => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect) {
    return '/'
  }

  try {
    const targetUrl = new URL(redirect, window.location.origin)
    if (targetUrl.origin !== window.location.origin) {
      return '/'
    }
    return `${targetUrl.pathname}${targetUrl.search}${targetUrl.hash}`
  } catch {
    return '/'
  }
}

const validateCheckPassword = async () => {
  if (
    registerFormState.checkPassword &&
    registerFormState.checkPassword !== registerFormState.userPassword
  ) {
    return Promise.reject(new Error('两次输入密码不一致'))
  }
  return Promise.resolve()
}

const handleLoginSubmit = async (values: API.UserLoginRequest) => {
  if (loginSubmitting.value) {
    return
  }

  loginSubmitting.value = true
  try {
    const res = await userLogin(values)
    if (res.data.code === 0 && res.data.data) {
      await loginUserStore.fetchLoginUser()
      message.success('登录成功')
      await router.replace(getSafeRedirect())
    } else {
      message.error('登录失败，' + res.data.message)
    }
  } finally {
    loginSubmitting.value = false
  }
}

const handleRegisterSubmit = async (values: API.UserRegisterRequest) => {
  if (registerSubmitting.value) {
    return
  }

  registerSubmitting.value = true
  try {
    const res = await userRegister(values)
    if (res.data.code === 0) {
      message.success('注册成功，请登录')
      loginFormState.userAccount = values.userAccount
      loginFormState.userPassword = ''
      await router.replace({
        path: '/user/login',
        query: route.query,
      })
    } else {
      message.error('注册失败，' + res.data.message)
    }
  } finally {
    registerSubmitting.value = false
  }
}
</script>

<style scoped>
#userAuthPage {
  --page-bg: #f4f8fc;
  --page-bg-strong: #edf3fa;
  --panel-bg: rgba(255, 255, 255, 0.78);
  --panel-border: rgba(104, 132, 175, 0.18);
  --soft-text: #607086;
  --strong-text: #0d1b2a;
  --accent: #2f80ff;
  --accent-strong: #1858c8;
  --accent-soft: rgba(47, 128, 255, 0.12);
  --accent-glow: rgba(47, 128, 255, 0.18);
  --cyan-soft: rgba(44, 192, 210, 0.18);
  height: 100%;
  min-height: 100%;
  padding: 20px 24px 24px;
  position: relative;
  overflow: hidden;
  background:
    radial-gradient(circle at top left, rgba(47, 128, 255, 0.12), transparent 28%),
    radial-gradient(circle at 88% 16%, rgba(44, 192, 210, 0.14), transparent 24%),
    linear-gradient(180deg, #fbfdff 0%, #f4f8fc 45%, #edf3fa 100%);
}

.ambient-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(122, 146, 184, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(122, 146, 184, 0.08) 1px, transparent 1px);
  background-size: 42px 42px;
  mask-image: radial-gradient(circle at center, black 35%, transparent 88%);
  opacity: 0.7;
  animation: gridDrift 18s linear infinite;
  pointer-events: none;
}

.ambient-glow {
  position: absolute;
  border-radius: 999px;
  filter: blur(18px);
  pointer-events: none;
}

.glow-one {
  width: 280px;
  height: 280px;
  top: 72px;
  right: 8%;
  background: rgba(47, 128, 255, 0.14);
  animation: floatGlow 10s ease-in-out infinite;
}

.glow-two {
  width: 340px;
  height: 340px;
  left: -80px;
  bottom: -120px;
  background: rgba(44, 192, 210, 0.12);
  animation: floatGlow 12s ease-in-out infinite reverse;
}

#userAuthPage::before,
#userAuthPage::after {
  content: '';
  position: absolute;
  inset: auto;
  border-radius: 999px;
  filter: blur(24px);
  pointer-events: none;
}

#userAuthPage::before {
  width: 240px;
  height: 240px;
  top: 96px;
  right: 20%;
  background: rgba(255, 255, 255, 0.62);
}

#userAuthPage::after {
  width: 320px;
  height: 320px;
  bottom: 8%;
  left: 10%;
  background: rgba(255, 255, 255, 0.56);
}

.page-shell {
  height: 100%;
  max-width: 1240px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(380px, 460px);
  gap: 28px;
  align-items: stretch;
  position: relative;
  z-index: 1;
}

.brand-panel,
.login-panel {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--panel-border);
  background: var(--panel-bg);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px rgba(114, 137, 170, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.8);
}

.brand-panel {
  border-radius: 32px;
  padding: 40px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.92), rgba(247, 250, 255, 0.78)),
    rgba(255, 255, 255, 0.84);
  animation: panelReveal 0.62s cubic-bezier(0.2, 0.8, 0.2, 1) both;
}

.brand-panel::before {
  content: '';
  position: absolute;
  inset: 24px 24px auto auto;
  width: 220px;
  height: 220px;
  border-radius: 24px;
  border: 1px solid rgba(130, 153, 186, 0.18);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.7), rgba(255, 255, 255, 0.16)),
    radial-gradient(circle at top, rgba(47, 128, 255, 0.1), transparent 58%);
  transform: rotate(10deg);
}

.brand-copy {
  position: relative;
  max-width: 560px;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  letter-spacing: 0.28em;
  color: var(--accent);
  margin-bottom: 20px;
  padding: 10px 16px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(104, 132, 175, 0.16);
  box-shadow: 0 14px 30px rgba(114, 137, 170, 0.08);
}

.eyebrow::before {
  content: '';
  width: 36px;
  height: 1px;
  background: currentColor;
}

.title {
  margin: 0;
  font-size: clamp(38px, 5vw, 64px);
  line-height: 1.04;
  color: var(--strong-text);
  letter-spacing: -0.04em;
  font-weight: 700;
  text-wrap: balance;
}

.desc {
  margin: 24px 0 0;
  max-width: 520px;
  font-size: 16px;
  line-height: 1.9;
  color: var(--soft-text);
}

.hero-scene {
  position: relative;
  width: min(100%, 500px);
  height: 184px;
  margin-top: 32px;
  border-radius: 30px;
  overflow: hidden;
  border: 1px solid rgba(104, 132, 175, 0.16);
  background:
    linear-gradient(160deg, rgba(255, 255, 255, 0.94), rgba(241, 246, 255, 0.82)),
    rgba(255, 255, 255, 0.86);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.85),
    0 20px 44px rgba(122, 146, 184, 0.12);
}

.hero-scene::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, transparent 0%, rgba(47, 128, 255, 0.06) 48%, transparent 100%);
  transform: translateX(-100%);
  animation: beamSweep 7s ease-in-out infinite;
}

.hero-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.svg-glow {
  transform-origin: 386px 96px;
  animation: glowPulse 6s ease-in-out infinite;
}

.svg-rings {
  transform-origin: center;
}

.ring-group-primary {
  transform-origin: 154px 92px;
  animation: orbitSpin 18s linear infinite;
}

.ring-group-secondary {
  transform-origin: 388px 84px;
  animation: orbitSpin 14s linear infinite reverse;
}

.svg-ring {
  stroke: rgba(47, 128, 255, 0.18);
  stroke-width: 1.25;
}

.ring-small,
.ring-core {
  stroke: rgba(44, 192, 210, 0.26);
}

.svg-flow {
  fill: none;
  stroke-width: 3;
  stroke-linecap: round;
  stroke-dasharray: 8 12;
  animation: dashFlow 8s linear infinite;
}

.flow-secondary {
  stroke-width: 2.2;
  animation-duration: 10s;
}

.svg-link {
  fill: none;
  stroke: rgba(47, 128, 255, 0.2);
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.svg-node {
  animation: nodeDrift 5.4s ease-in-out infinite;
}

.node-mid {
  animation-delay: 0.8s;
}

.node-right {
  animation-delay: 1.4s;
}

.node-end {
  animation-delay: 2s;
}

.svg-node-core {
  fill: #ffffff;
  stroke: #2f80ff;
  stroke-width: 3;
}

.svg-node-halo {
  fill: rgba(47, 128, 255, 0.06);
  stroke: rgba(47, 128, 255, 0.14);
  stroke-width: 1;
  animation: haloPulse 3.2s ease-in-out infinite;
}

.svg-bars .bar {
  fill: rgba(47, 128, 255, 0.16);
  transform-box: fill-box;
  transform-origin: center bottom;
  animation: barLift 3.4s ease-in-out infinite;
}

.svg-bars .bar-two {
  animation-delay: 0.2s;
}

.svg-bars .bar-three {
  animation-delay: 0.4s;
}

.svg-bars .bar-four {
  animation-delay: 0.6s;
}

.scene-core {
  position: absolute;
  left: 48px;
  bottom: 28px;
  display: inline-flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  min-width: 228px;
  border-radius: 22px;
  border: 1px solid rgba(104, 132, 175, 0.16);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 16px 40px rgba(122, 146, 184, 0.1);
  backdrop-filter: blur(12px);
}

.scene-core-label {
  font-size: 11px;
  letter-spacing: 0.22em;
  color: var(--accent);
}

.scene-core strong {
  font-size: 18px;
  color: var(--strong-text);
  font-weight: 600;
  line-height: 1.45;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  padding: 20px 18px;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(247, 250, 255, 0.88));
  border: 1px solid rgba(104, 132, 175, 0.14);
  box-shadow: 0 16px 34px rgba(122, 146, 184, 0.08);
  transition:
    transform 0.28s ease,
    box-shadow 0.28s ease,
    border-color 0.28s ease;
}

.metric-card:hover {
  transform: translateY(-4px);
  border-color: rgba(47, 128, 255, 0.24);
  box-shadow: 0 24px 44px rgba(122, 146, 184, 0.12);
}

.metric-label {
  display: block;
  margin-bottom: 12px;
  font-size: 12px;
  letter-spacing: 0.12em;
  color: #7d8ea6;
  text-transform: uppercase;
}

.metric-card strong {
  font-size: 18px;
  line-height: 1.5;
  color: var(--strong-text);
  font-weight: 600;
}

.signal-board {
  border-radius: 24px;
  padding: 22px 24px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(245, 249, 255, 0.86)),
    rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(104, 132, 175, 0.14);
  box-shadow: 0 18px 36px rgba(122, 146, 184, 0.08);
}

.signal-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  letter-spacing: 0.14em;
  color: #6c7f97;
  text-transform: uppercase;
}

.signal-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--accent);
  box-shadow: 0 0 0 7px var(--accent-soft);
}

.signal-list {
  margin-top: 20px;
  display: grid;
  gap: 14px;
}

.signal-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(104, 132, 175, 0.12);
  color: var(--strong-text);
  font-size: 14px;
}

.signal-item span:first-child {
  color: #7a8ca4;
}

.login-panel {
  border-radius: 28px;
  padding: 1px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(246, 249, 255, 0.8)),
    rgba(255, 255, 255, 0.84);
  animation: panelReveal 0.62s cubic-bezier(0.2, 0.8, 0.2, 1) 0.08s both;
}

.panel-decoration {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at top, rgba(47, 128, 255, 0.14), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.7), transparent 26%);
  pointer-events: none;
}

.panel-content {
  position: relative;
  height: 100%;
  min-height: 0;
  padding: 30px 34px 34px;
  border-radius: 27px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(248, 251, 255, 0.92)),
    rgba(255, 255, 255, 0.94);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.form-head {
  margin-bottom: 24px;
}

.auth-switch {
  display: inline-grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 4px;
  padding: 4px;
  margin-bottom: 24px;
  border-radius: 999px;
  background: #eef4fb;
  border: 1px solid rgba(104, 132, 175, 0.14);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.92);
}

.switch-option {
  border: none;
  min-width: 86px;
  height: 38px;
  border-radius: 999px;
  background: transparent;
  color: #70839c;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.24s ease,
    color 0.24s ease,
    transform 0.24s ease;
}

.switch-option.active {
  color: #ffffff;
  background: linear-gradient(135deg, #2878ff 0%, #4bb8ff 100%);
  box-shadow: 0 10px 22px rgba(47, 128, 255, 0.24);
}

.form-kicker {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: 0.22em;
  color: var(--accent);
}

.form-head h2 {
  margin: 0;
  color: var(--strong-text);
  font-size: 32px;
  line-height: 1.15;
}

.form-head p {
  margin: 14px 0 0;
  color: var(--soft-text);
  line-height: 1.8;
  font-size: 14px;
}

.form-stage {
  position: relative;
  min-height: 332px;
}

.auth-form {
  width: 100%;
}

:deep(.ant-form-item) {
  margin-bottom: 20px;
}

:deep(.ant-form-item-label > label) {
  color: #5b6d85;
  font-size: 13px;
}

:deep(.ant-input-affix-wrapper),
:deep(.ant-input) {
  border-radius: 16px;
  min-height: 52px;
  color: var(--strong-text);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(104, 132, 175, 0.14);
  box-shadow: none;
  transition:
    border-color 0.24s ease,
    box-shadow 0.24s ease,
    transform 0.24s ease;
}

:deep(.ant-input-password .ant-input) {
  min-height: auto;
  background: transparent;
  border: none;
}

:deep(.ant-input::placeholder) {
  color: #9aa9bd;
}

:deep(.ant-input-affix-wrapper:hover),
:deep(.ant-input:hover),
:deep(.ant-input-affix-wrapper:focus),
:deep(.ant-input-affix-wrapper-focused),
:deep(.ant-input:focus) {
  border-color: rgba(47, 128, 255, 0.4);
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 0 0 4px rgba(47, 128, 255, 0.08);
  transform: translateY(-1px);
}

:deep(.ant-input-password-icon.anticon) {
  color: #7f91aa;
}

.assist-row {
  margin: 2px 0 26px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  font-size: 13px;
}

.assist-text {
  color: #7d8ea6;
}

.register-link {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--accent-strong);
  text-decoration: none;
  position: relative;
  cursor: pointer;
}

.register-link::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -2px;
  height: 1px;
  background: currentColor;
  transform: scaleX(0.5);
  transform-origin: left;
  opacity: 0.5;
  transition:
    transform 0.24s ease,
    opacity 0.24s ease;
}

.register-link:hover::after {
  transform: scaleX(1);
  opacity: 1;
}

.submit-row {
  margin-bottom: 0;
}

.submit-button {
  width: 100%;
  height: 54px;
  border: none;
  border-radius: 16px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: #ffffff;
  background: linear-gradient(135deg, #1f6fff 0%, #4dbbff 100%);
  box-shadow:
    0 18px 36px rgba(47, 128, 255, 0.22),
    inset 0 -2px 0 rgba(0, 0, 0, 0.08);
  position: relative;
  overflow: hidden;
}

.submit-button:hover,
.submit-button:focus {
  color: #ffffff;
  background: linear-gradient(135deg, #2b79ff 0%, #62c4ff 100%);
}

.submit-button::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(110deg, transparent 24%, rgba(255, 255, 255, 0.34) 50%, transparent 74%);
  transform: translateX(-140%);
  animation: buttonSweep 5.2s ease-in-out infinite;
}

.auth-card-enter-active,
.auth-card-leave-active {
  transition:
    opacity 0.28s ease,
    transform 0.32s ease,
    filter 0.32s ease;
}

.auth-card-enter-from {
  opacity: 0;
  transform: translateY(18px) scale(0.985);
  filter: blur(4px);
}

.auth-card-leave-to {
  opacity: 0;
  transform: translateY(-12px) scale(0.985);
  filter: blur(3px);
}

@keyframes gridDrift {
  0% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(8px, 10px, 0);
  }
  100% {
    transform: translate3d(0, 0, 0);
  }
}

@keyframes floatGlow {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(0, -12px, 0);
  }
}

@keyframes beamSweep {
  0%,
  100% {
    transform: translateX(-100%);
    opacity: 0;
  }
  20%,
  70% {
    opacity: 1;
  }
  60% {
    transform: translateX(100%);
  }
}

@keyframes orbitSpin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

@keyframes nodeDrift {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(0, -4px, 0);
  }
}

@keyframes haloPulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.82;
  }
  50% {
    transform: scale(1.12);
    opacity: 0.42;
  }
}

@keyframes glowPulse {
  0%,
  100% {
    opacity: 0.8;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.04);
  }
}

@keyframes dashFlow {
  from {
    stroke-dashoffset: 0;
  }
  to {
    stroke-dashoffset: -120;
  }
}

@keyframes barLift {
  0%,
  100% {
    transform: scaleY(0.92);
    opacity: 0.72;
  }
  50% {
    transform: scaleY(1.06);
    opacity: 1;
  }
}

@keyframes buttonSweep {
  0%,
  100% {
    transform: translateX(-140%);
    opacity: 0;
  }
  18%,
  62% {
    opacity: 1;
  }
  48% {
    transform: translateX(140%);
  }
}

@keyframes panelReveal {
  from {
    opacity: 0;
    transform: translateY(18px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1080px) {
  .page-shell {
    height: auto;
    grid-template-columns: 1fr;
  }

  .brand-panel {
    gap: 32px;
  }

  .hero-scene {
    width: 100%;
  }
}

@media (max-width: 768px) {
  #userAuthPage {
    height: auto;
    min-height: 100%;
    padding: 16px;
  }

  .brand-panel,
  .panel-content {
    padding: 24px 20px;
  }

  .metrics {
    grid-template-columns: 1fr;
  }

  .hero-scene {
    height: 164px;
  }

  .scene-core {
    left: 20px;
    right: 20px;
    min-width: 0;
  }

  .signal-item,
  .assist-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .form-stage {
    min-height: 0;
  }

  .form-head h2 {
    font-size: 28px;
  }
}

@media (min-width: 1081px) and (max-height: 760px) {
  #userAuthPage {
    padding: 14px 20px 18px;
  }

  .brand-panel {
    padding: 30px;
  }

  .panel-content {
    padding: 24px 30px 28px;
  }

  .title {
    font-size: clamp(34px, 4.2vw, 52px);
  }

  .desc {
    margin-top: 18px;
    line-height: 1.7;
  }

  .hero-scene {
    height: 156px;
    margin-top: 24px;
  }

  .scene-core {
    left: 26px;
    bottom: 20px;
    padding: 14px 16px;
  }

  .form-head {
    margin-bottom: 18px;
  }

  .auth-switch {
    margin-bottom: 18px;
  }

  .form-head h2 {
    font-size: 28px;
  }

  .form-stage {
    min-height: 304px;
  }

  .metric-card {
    padding: 16px;
  }

  .signal-board {
    padding: 18px 20px;
  }

  .signal-list {
    margin-top: 14px;
    gap: 10px;
  }
}
</style>
