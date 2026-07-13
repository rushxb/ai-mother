import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getLoginUser } from '@/api/userController.ts'

type LoginStatus = 'idle' | 'loading' | 'authenticated' | 'anonymous' | 'error'

const createAnonymousUser = (): API.LoginUserVO => ({})

/**
 * Centralizes session initialization and deduplicates concurrent login-user requests.
 */
export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>(createAnonymousUser())
  const status = ref<LoginStatus>('idle')
  let pendingRequest: Promise<API.LoginUserVO | null> | null = null

  const isAuthenticated = computed(() => Boolean(loginUser.value.id))
  const isInitialized = computed(() => status.value !== 'idle' && status.value !== 'loading')

  const clearLoginUser = (nextStatus: LoginStatus = 'anonymous') => {
    loginUser.value = createAnonymousUser()
    status.value = nextStatus
  }

  async function fetchLoginUser(force = false) {
    if (pendingRequest) {
      return pendingRequest
    }
    if (!force && isInitialized.value) {
      return isAuthenticated.value ? loginUser.value : null
    }

    status.value = 'loading'
    pendingRequest = (async () => {
      try {
        const res = await getLoginUser()
        if (res.data.code === 0 && res.data.data?.id) {
          loginUser.value = res.data.data
          status.value = 'authenticated'
          return loginUser.value
        }
        clearLoginUser('anonymous')
        return null
      } catch (error) {
        clearLoginUser('error')
        console.error('Failed to initialize login session', error)
        return null
      } finally {
        pendingRequest = null
      }
    })()

    return pendingRequest
  }

  function setLoginUser(newLoginUser: API.LoginUserVO | null) {
    if (newLoginUser?.id) {
      loginUser.value = newLoginUser
      status.value = 'authenticated'
      return
    }
    clearLoginUser('anonymous')
  }

  return {
    loginUser,
    status,
    isAuthenticated,
    isInitialized,
    fetchLoginUser,
    setLoginUser,
    clearLoginUser,
  }
})
