<template>
  <div id="userAuthPage">
    <BubblesBackground class="auth-bubbles" :bubble-count="34" :speed="1.2" :blur="0.2" />
    <div class="auth-overlay"></div>

    <main class="auth-shell">
      <section class="auth-hero" aria-label="产品介绍">
        <BlurReveal class="hero-reveal" :duration="0.78" :delay="0.14" blur="22px" :y-offset="24">
          <p class="eyebrow">AI APPLICATION STUDIO</p>
          <h1>{{ heroTitle }}</h1>
          <p class="hero-desc">{{ heroDesc }}</p>
        </BlurReveal>

        <div class="signal-strip">
          <div v-for="item in signalItems" :key="item.label" class="signal-item">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </section>

      <section class="login-panel">
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
      </section>
    </main>
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
  --text-strong: #f8fbff;
  --text-soft: rgba(226, 237, 250, 0.74);
  --panel-bg: rgba(8, 18, 33, 0.58);
  --panel-border: rgba(191, 222, 255, 0.22);
  --accent: #8fe4ff;
  --accent-strong: #58b8ff;
  min-height: 100vh;
  position: relative;
  overflow: hidden;
  color: var(--text-strong);
  background:
    radial-gradient(circle at 16% 8%, rgba(120, 196, 255, 0.22), transparent 30%),
    linear-gradient(135deg, #07101f 0%, #0b1627 48%, #102b3d 100%);
}

.auth-bubbles {
  position: absolute;
  inset: -8%;
  width: 116%;
  height: 116%;
  opacity: 0.92;
}

.auth-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(4, 11, 23, 0.5), rgba(4, 11, 23, 0.16) 42%, rgba(4, 11, 23, 0.58)),
    radial-gradient(circle at 76% 18%, rgba(143, 228, 255, 0.2), transparent 26%),
    radial-gradient(circle at 40% 92%, rgba(88, 184, 255, 0.18), transparent 30%);
}

.auth-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
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
  text-shadow: 0 22px 58px rgba(0, 0, 0, 0.34);
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
  background: rgba(6, 16, 31, 0.34);
  backdrop-filter: blur(18px);
  box-shadow:
    0 24px 70px rgba(0, 0, 0, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
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
  color: rgba(226, 237, 250, 0.58);
  font-size: 12px;
  letter-spacing: 0.16em;
}

.signal-item strong {
  color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
}

.login-panel {
  position: relative;
  min-width: 0;
  border-radius: 30px;
  padding: 1px;
  background: linear-gradient(145deg, rgba(199, 232, 255, 0.44), rgba(255, 255, 255, 0.08));
  box-shadow:
    0 32px 90px rgba(0, 0, 0, 0.34),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
}

.panel-rim {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background:
    radial-gradient(circle at 30% 0%, rgba(143, 228, 255, 0.3), transparent 36%),
    radial-gradient(circle at 90% 18%, rgba(88, 184, 255, 0.22), transparent 34%);
  pointer-events: none;
}

.panel-content {
  position: relative;
  padding: 32px 34px 36px;
  border-radius: 29px;
  background:
    linear-gradient(180deg, rgba(10, 22, 39, 0.78), rgba(8, 17, 31, 0.66)),
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
  background: rgba(220, 240, 255, 0.1);
  border: 1px solid rgba(191, 222, 255, 0.16);
}

.switch-option {
  border: none;
  height: 38px;
  border-radius: 999px;
  background: transparent;
  color: rgba(226, 237, 250, 0.72);
  font-size: 13px;
  cursor: pointer;
  transition:
    color 0.24s ease,
    background 0.24s ease,
    box-shadow 0.24s ease;
}

.switch-option.active {
  color: #05111f;
  background: linear-gradient(135deg, #9eeaff 0%, #7cbcff 100%);
  box-shadow: 0 12px 28px rgba(104, 196, 255, 0.32);
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
  color: #ffffff;
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
  color: rgba(226, 237, 250, 0.74);
  font-size: 13px;
}

:deep(.ant-input-affix-wrapper),
:deep(.ant-input) {
  min-height: 52px;
  border-radius: 16px;
  color: #f8fbff;
  background: rgba(6, 16, 31, 0.54);
  border: 1px solid rgba(191, 222, 255, 0.2);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
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
  color: rgba(226, 237, 250, 0.4);
}

:deep(.ant-input-affix-wrapper:hover),
:deep(.ant-input:hover),
:deep(.ant-input-affix-wrapper:focus),
:deep(.ant-input-affix-wrapper-focused),
:deep(.ant-input:focus) {
  border-color: rgba(143, 228, 255, 0.62);
  background: rgba(8, 21, 38, 0.72);
  box-shadow: 0 0 0 4px rgba(143, 228, 255, 0.12);
}

:deep(.ant-input-password-icon.anticon) {
  color: rgba(226, 237, 250, 0.66);
}

.assist-row {
  margin: 2px 0 26px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  color: rgba(226, 237, 250, 0.56);
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
  color: #04111f;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: linear-gradient(135deg, #9eecff 0%, #73b5ff 100%);
  box-shadow:
    0 20px 44px rgba(83, 178, 255, 0.3),
    inset 0 -2px 0 rgba(4, 17, 31, 0.1);
}

.submit-button:hover,
.submit-button:focus {
  color: #04111f;
  background: linear-gradient(135deg, #b8f3ff 0%, #89c3ff 100%);
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
