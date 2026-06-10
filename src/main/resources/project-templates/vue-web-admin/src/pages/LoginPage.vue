<template>
  <div class="login-page">
    <Card class="login-card">
      <CardHeader>
        <CardTitle>{{ site.brand }}</CardTitle>
        <CardDescription>{{ site.slogan }}</CardDescription>
      </CardHeader>

      <CardContent>
        <form @submit.prevent="handleLogin" class="login-form">
          <div class="form-field">
            <Label for="userAccount">账号</Label>
            <Input
              id="userAccount"
              v-model="formData.userAccount"
              placeholder="请输入账号"
              :disabled="loading"
            />
          </div>

          <div class="form-field">
            <Label for="userPassword">密码</Label>
            <Input
              id="userPassword"
              v-model="formData.userPassword"
              type="password"
              placeholder="请输入密码"
              :disabled="loading"
              @keydown.enter="handleLogin"
            />
          </div>

          <Button type="submit" class="w-full" :disabled="loading">
            {{ loading ? '登录中...' : '登录' }}
          </Button>
        </form>
      </CardContent>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { site } from '@/data/adminData'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)

const formData = reactive({
  userAccount: '',
  userPassword: ''
})

async function handleLogin() {
  if (!formData.userAccount || !formData.userPassword) {
    alert('请填写账号和密码')
    return
  }

  try {
    loading.value = true
    await userStore.login(formData)

    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch (error: any) {
    alert(error?.message || '登录失败')
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
  background: var(--bg, #0a0a0a);
  padding: 2rem;
}

.login-card {
  width: 100%;
  max-width: 420px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
</style>
