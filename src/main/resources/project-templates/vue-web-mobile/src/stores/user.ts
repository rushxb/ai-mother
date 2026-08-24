import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { UserInfo, LoginParams } from '@/types'
import { safeLocalStorage } from '@/lib/safe-storage'

export const useUserStore = defineStore('user', () => {
  // State
  const token = ref<string>('')
  const userInfo = ref<UserInfo | null>(null)
  
  // Getters
  const isLoggedIn = computed(() => !!token.value)
  const userName = computed(() => userInfo.value?.userName || '游客')
  
  // Actions
  async function login(params: LoginParams) {
    // Mock login
    token.value = 'mock-token'
    userInfo.value = {
      id: 1,
      userAccount: params.userAccount,
      userName: '用户',
      userAvatar: '',
      userRole: 'user',
      createTime: new Date().toISOString()
    }
  }
  
  async function logout() {
    token.value = ''
    userInfo.value = null
  }
  
  function setToken(newToken: string) {
    token.value = newToken
  }
  
  // @AI_INJECT_STORE_ACTION
  
  return {
    token,
    userInfo,
    isLoggedIn,
    userName,
    login,
    logout,
    setToken
  }
}, {
  persist: {
    key: 'user-store',
    storage: safeLocalStorage,
    pick: ['token']
  }
})
