import axios, { type AxiosError, type AxiosResponse } from 'axios'
import { message } from 'ant-design-vue'
import { API_BASE_URL } from '@/config/env'
import { getCurrentRoutePath } from '@/utils/safeRedirect'
import router from '@/router'

const UNAUTHORIZED_BUSINESS_CODE = 40100
const LOGIN_USER_ENDPOINT = '/user/get/login'
let unauthorizedRedirectPending = false

const isAuthPage = () =>
  typeof window !== 'undefined' &&
  (window.location.pathname.includes('/user/login') ||
    window.location.pathname.includes('/user/register'))

const isLoginUserRequest = (response?: AxiosResponse) =>
  response?.config.url?.includes(LOGIN_USER_ENDPOINT) ?? false

const redirectToLogin = (response?: AxiosResponse) => {
  if (
    typeof window === 'undefined' ||
    unauthorizedRedirectPending ||
    isAuthPage() ||
    isLoginUserRequest(response)
  ) {
    return
  }

  unauthorizedRedirectPending = true
  message.warning('登录状态已失效，请重新登录')
  const redirect = getCurrentRoutePath()

  void router.replace({ path: '/user/login', query: { redirect } }).finally(() => {
    window.setTimeout(() => {
      unauthorizedRedirectPending = false
    }, 1000)
  })
}

const myAxios = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true,
})

myAxios.interceptors.response.use(
  (response) => {
    const data = response.data as { code?: number } | undefined
    if (data?.code === UNAUTHORIZED_BUSINESS_CODE) {
      redirectToLogin(response)
    }
    return response
  },
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      redirectToLogin(error.response)
    }
    return Promise.reject(error)
  },
)

export default myAxios
