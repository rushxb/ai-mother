import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useRouter } from 'vue-router'
import type { UserInfo, LoginParams } from '@/types'
import request from '@/services/request'
import { safeLocalStorage } from '@/lib/safe-storage'

export const useUserStore = defineStore('user', () => {
  const router = useRouter()
  
  // State
  const token = ref<string>('')
  const userInfo = ref<UserInfo | null>(null)
  
  // Getters
  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => userInfo.value?.userRole === 'admin')
  const userName = computed(() => userInfo.value?.userName || '用户')
  
  // Actions
  async function login(params: LoginParams) {
    const res = await request.post<any>('/auth/login', params)
    token.value = res.data.token
    userInfo.value = res.data.user
    return res
  }
  
  async function logout() {
    token.value = ''
    userInfo.value = null
    router.push('/login')
  }
  
  async function getUserInfo() {
    if (!token.value) return
    const res = await request.get<any>('/auth/userInfo')
    userInfo.value = res.data
    return res
  }
  
  function setToken(newToken: string) {
    token.value = newToken
  }
  
  // @AI_INJECT_STORE_ACTION
  
  return {
    token,
    userInfo,
    isLoggedIn,
    isAdmin,
    userName,
    login,
    logout,
    getUserInfo,
    setToken
  }
}, {
  persist: {
    key: 'user-store',
    storage: safeLocalStorage,
    pick: ['token']
  }
})
