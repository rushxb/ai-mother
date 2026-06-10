<template>
  <div id="userAuthPage">
    <BubblesBackground
      class="auth-bubbles"
      :bubble-count="24"
      color1="#9BD8FF"
      color2="#B9CAFF"
      color3="#A8F0DF"
      :speed="0.72"
      :blur="0.45"
      :min-radius="0.62"
      :max-radius="1.9"
      :min-opacity="0.22"
      :max-opacity="0.52"
      :spread-x="18"
      :spread-y="11"
      :depth-min="-9"
      :depth-max="2"
      :drift-strength="0.58"
      :camera-z="20"
    />
    <div class="auth-overlay"></div>

    <main class="auth-shell">
      <section class="auth-hero" aria-label="产品介绍">
        <BlurReveal class="hero-reveal" :duration="0.78" :delay="0.14" blur="22px" :y-offset="24">
          <p class="eyebrow">AI APPLICATION STUDIO</p>
          <h1>{{ heroTitle }}</h1>
          <p class="hero-desc">{{ heroDesc }}</p>
        </BlurReveal>

        <div class="signal-strip">
          <Motion
            v-for="(item, index) in signalItems"
            :key="item.label"
            as="div"
            v-bind="staggerChild(index, 0.2)"
            class="signal-item"
          >
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </Motion>
        </div>
      </section>

      <Motion
        as="section"
        class="login-panel"
        :initial="{ opacity: 0, x: 20, filter: 'blur(6px)' }"
        :animate="{ opacity: 1, x: 0, filter: 'blur(0px)' }"
        :transition="{ duration: 0.58, ease: [0.25, 0.46, 0.45, 0.94], delay: 0.18 }"
      >
        <div class="panel-rim"></div>
        <div class="panel-content">
          <BlurReveal class="form-reveal" :duration="0.62" :delay="0.1" blur="16px" :y-offset="16">
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
            <div class="form-head">
              <p>{{ isRegisterMode ? 'CREATE ACCESS' : 'WELCOME BACK' }}</p>
              <h2>{{ isRegisterMode ? '创建工作台账号' : '登录工作台' }}</h2>
            </div>
          </BlurReveal>

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
                <span>首次使用可先创建账号</span>
                <button type="button" class="text-button" @click="switchMode('register')">
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
                <span>已经有账号可以直接进入</span>
                <button type="button" class="text-button" @click="switchMode('login')">
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
      </Motion>
    </main>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { Motion } from 'motion-v'
import { userLogin, userRegister } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import { staggerChild } from '@/composables/useMotionPresets'

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
  isRegisterMode.value ? '建立账号，进入你的 AI 应用工作台。' : '把想法整理成产品，而不是停留在草稿里。',
)

const heroDesc = computed(() =>
  isRegisterMode.value
    ? '创建账号后即可进入同一套 AI 应用工作流，从需求描述、页面成型到对话调试，减少切换与重复配置。'
    : '面向高频迭代场景的 AI 应用生成台，从需求描述、页面搭建到对话调试，尽量把过程收敛到更短路径。',
)

const signalItems = computed(() =>
  isRegisterMode.value
    ? [
        { label: '入口', value: '新账号注册' },
        { label: '流程', value: '创建后直接进入工作台' },
        { label: '适用', value: 'MVP / Demo / 内部工具' },
      ]
    : [
        { label: '入口', value: '账户登录' },
        { label: '流程', value: '生成 · 编辑 · 部署' },
        { label: '适用', value: 'MVP / Demo / 内部工具' },
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
      await router.replace({
        path: '/user/success',
        query: {
          redirect: getSafeRedirect(),
        },
      })
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
  --text-strong: #102033;
  --text-soft: rgba(47, 65, 88, 0.72);
  --panel-bg: rgba(255, 255, 255, 0.72);
  --panel-border: rgba(126, 158, 196, 0.24);
  --accent: #2f8bff;
  --accent-strong: #1466d8;
  --mist-blue: rgba(208, 231, 255, 0.54);
  --mist-green: rgba(216, 245, 237, 0.44);
  min-height: 100%;
  position: relative;
  overflow: hidden;
  color: var(--text-strong);
  background:
    radial-gradient(circle at 18% 10%, var(--mist-blue), transparent 34%),
    radial-gradient(circle at 82% 14%, var(--mist-green), transparent 34%),
    linear-gradient(135deg, #fcfdff 0%, #f6f9fd 48%, #f1f8f7 100%);
}

#userAuthPage::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.42;
  background-image:
    linear-gradient(rgba(116, 142, 174, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(116, 142, 174, 0.08) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: linear-gradient(90deg, transparent, black 18%, black 70%, transparent);
}

.auth-bubbles {
  position: absolute;
  inset: -8%;
  width: 116%;
  height: 116%;
  opacity: 0.94;
}

.auth-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, 0.68), rgba(255, 255, 255, 0.18) 46%, rgba(255, 255, 255, 0.72)),
    linear-gradient(180deg, rgba(255, 255, 255, 0.42), rgba(255, 255, 255, 0.08) 48%, rgba(255, 255, 255, 0.58)),
    radial-gradient(circle at 76% 18%, rgba(255, 255, 255, 0.44), transparent 32%),
    radial-gradient(circle at 42% 92%, rgba(207, 232, 255, 0.32), transparent 36%);
}

.auth-shell {
  position: relative;
  z-index: 1;
  min-height: 100%;
  width: min(1180px, calc(100% - 48px));
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(370px, 438px);
  gap: 42px;
  align-items: center;
  padding: 40px 0;
}

.auth-hero {
  min-width: 0;
}

.hero-reveal {
  max-width: 660px;
}

.eyebrow {
  margin: 0 0 18px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.26em;
}

.auth-hero h1 {
  margin: 0;
  max-width: 680px;
  font-size: clamp(44px, 6.4vw, 78px);
  line-height: 1.03;
  font-weight: 800;
  letter-spacing: 0;
  text-wrap: balance;
  text-shadow: 0 18px 48px rgba(91, 126, 168, 0.14);
}

.hero-desc {
  margin: 24px 0 0;
  max-width: 590px;
  color: var(--text-soft);
  font-size: 17px;
  line-height: 1.9;
}

.signal-strip {
  margin-top: 42px;
  width: min(100%, 720px);
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid rgba(191, 222, 255, 0.18);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px rgba(88, 124, 166, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.signal-item {
  padding: 22px 24px;
  border-right: 1px solid rgba(191, 222, 255, 0.14);
}

.signal-item:last-child {
  border-right: none;
}

.signal-item span {
  display: block;
  margin-bottom: 10px;
  color: rgba(47, 65, 88, 0.5);
  font-size: 12px;
  letter-spacing: 0.16em;
}

.signal-item strong {
  color: #14263a;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
}

.login-panel {
  position: relative;
  min-width: 0;
  border-radius: 30px;
  padding: 1px;
  background:
    linear-gradient(145deg, rgba(151, 199, 236, 0.34), rgba(255, 255, 255, 0.92)),
    rgba(255, 255, 255, 0.78);
  box-shadow:
    0 32px 90px rgba(87, 123, 166, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.96);
}

.panel-rim {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background:
    radial-gradient(circle at 30% 0%, rgba(168, 214, 245, 0.22), transparent 36%),
    radial-gradient(circle at 90% 18%, rgba(211, 244, 235, 0.28), transparent 34%);
  pointer-events: none;
}

.panel-content {
  position: relative;
  padding: 32px 34px 36px;
  border-radius: 29px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(249, 252, 255, 0.78)),
    var(--panel-bg);
  border: 1px solid var(--panel-border);
  backdrop-filter: blur(26px);
}

.auth-switch {
  display: inline-grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 5px;
  width: 178px;
  padding: 5px;
  border-radius: 999px;
  background: rgba(232, 243, 255, 0.76);
  border: 1px solid rgba(126, 158, 196, 0.18);
}

.switch-option {
  border: none;
  height: 38px;
  border-radius: 999px;
  background: transparent;
  color: rgba(47, 65, 88, 0.62);
  font-size: 13px;
  cursor: pointer;
  transition:
    color 0.24s ease,
    background 0.24s ease,
    box-shadow 0.24s ease;
}

.switch-option.active {
  color: #ffffff;
  background: linear-gradient(135deg, #2f8bff 0%, #67c8ff 100%);
  box-shadow: 0 12px 28px rgba(47, 139, 255, 0.26);
}

.form-head {
  margin: 26px 0 26px;
}

.form-head p {
  margin: 0 0 10px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.22em;
}

.form-head h2 {
  margin: 0;
  color: #102033;
  font-size: 30px;
  line-height: 1.18;
  font-weight: 700;
}

.auth-form {
  width: 100%;
}

:deep(.ant-form-item) {
  margin-bottom: 20px;
}

:deep(.ant-form-item-label > label) {
  color: rgba(47, 65, 88, 0.68);
  font-size: 13px;
}

:deep(.ant-input-affix-wrapper),
:deep(.ant-input) {
  min-height: 52px;
  border-radius: 16px;
  color: #102033;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(126, 158, 196, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.88);
  transition:
    border-color 0.22s ease,
    box-shadow 0.22s ease,
    background 0.22s ease;
}

:deep(.ant-input-password .ant-input) {
  min-height: auto;
  background: transparent;
  border: none;
}

:deep(.ant-input::placeholder) {
  color: rgba(47, 65, 88, 0.38);
}

:deep(.ant-input-affix-wrapper:hover),
:deep(.ant-input:hover),
:deep(.ant-input-affix-wrapper:focus),
:deep(.ant-input-affix-wrapper-focused),
:deep(.ant-input:focus) {
  border-color: rgba(47, 139, 255, 0.42);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 0 0 4px rgba(47, 139, 255, 0.1);
}

:deep(.ant-input-password-icon.anticon) {
  color: rgba(47, 65, 88, 0.5);
}

.assist-row {
  margin: 2px 0 26px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  color: rgba(47, 65, 88, 0.56);
  font-size: 13px;
}

.text-button {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
}

.submit-row {
  margin-bottom: 0;
}

.submit-button {
  width: 100%;
  height: 54px;
  border: none;
  border-radius: 16px;
  color: #ffffff;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: linear-gradient(135deg, #2f8bff 0%, #67c8ff 100%);
  box-shadow:
    0 20px 44px rgba(47, 139, 255, 0.26),
    inset 0 -2px 0 rgba(4, 17, 31, 0.08);
}

.submit-button:hover,
.submit-button:focus {
  color: #ffffff;
  background: linear-gradient(135deg, #3f98ff 0%, #7bd0ff 100%);
}

.auth-card-enter-active,
.auth-card-leave-active {
  transition:
    opacity 0.26s ease,
    transform 0.3s ease,
    filter 0.3s ease;
}

.auth-card-enter-from {
  opacity: 0;
  transform: translateY(14px);
  filter: blur(6px);
}

.auth-card-leave-to {
  opacity: 0;
  transform: translateY(-10px);
  filter: blur(5px);
}

@media (max-width: 980px) {
  .auth-shell {
    grid-template-columns: 1fr;
    gap: 28px;
    align-items: start;
  }

  .auth-hero {
    padding-top: 28px;
  }

  .login-panel {
    width: min(100%, 520px);
  }
}

@media (max-width: 680px) {
  .auth-shell {
    width: min(100% - 28px, 520px);
    padding: 24px 0;
  }

  .auth-hero h1 {
    font-size: 40px;
  }

  .hero-desc {
    font-size: 15px;
  }

  .signal-strip {
    grid-template-columns: 1fr;
    margin-top: 28px;
  }

  .signal-item {
    border-right: none;
    border-bottom: 1px solid rgba(191, 222, 255, 0.14);
  }

  .signal-item:last-child {
    border-bottom: none;
  }

  .panel-content {
    padding: 26px 20px 28px;
  }

  .assist-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
