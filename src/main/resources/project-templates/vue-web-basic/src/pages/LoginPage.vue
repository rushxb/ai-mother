<template>
  <div class="login-page">
    <n-card class="login-card" :bordered="false">
      <template #header>
        <div class="login-header">
          <h1>{{ site.brand }}</h1>
          <p>{{ site.slogan }}</p>
        </div>
      </template>
      
      <n-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-placement="left"
        label-width="auto"
        require-mark-placement="right-hanging"
      >
        <n-form-item path="userAccount" label="账号">
          <n-input
            v-model:value="formData.userAccount"
            placeholder="请输入账号"
            @keydown.enter="handleLogin"
          />
        </n-form-item>
        
        <n-form-item path="userPassword" label="密码">
          <n-input
            v-model:value="formData.userPassword"
            type="password"
            placeholder="请输入密码"
            show-password-on="click"
            @keydown.enter="handleLogin"
          />
        </n-form-item>
        
        <n-form-item>
          <n-button
            type="primary"
            block
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </n-button>
        </n-form-item>
      </n-form>
      
      <template #footer>
        <div class="login-footer">
          <span>还没有账号？</span>
          <n-button text type="primary" @click="router.push('/register')">
            立即注册
          </n-button>
        </div>
      </template>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useUserStore } from '@/stores/user'
import { site } from '@/data/siteData'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const userStore = useUserStore()

const formRef = ref<FormInst | null>(null)
const loading = ref(false)

const formData = reactive({
  userAccount: '',
  userPassword: ''
})

const rules: FormRules = {
  userAccount: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 4, max: 20, message: '账号长度 4-20 位', trigger: 'blur' }
  ],
  userPassword: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 20, message: '密码长度 8-20 位', trigger: 'blur' }
  ]
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
    loading.value = true
    
    await userStore.login(formData)
    message.success('登录成功')
    
    // Redirect to previous page or home
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (error: any) {
    if (error?.message) {
      message.error(error.message)
    }
  } finally {
    loading.value = false
  }
}

// @AI_INJECT_VIEW
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
  padding: 2rem;
}

.login-card {
  width: 100%;
  max-width: 420px;
}

.login-header {
  text-align: center;
}

.login-header h1 {
  font-size: 1.5rem;
  margin-bottom: 0.5rem;
}

.login-header p {
  color: var(--muted);
  font-size: 0.9rem;
}

.login-footer {
  text-align: center;
  font-size: 0.9rem;
}
</style>
