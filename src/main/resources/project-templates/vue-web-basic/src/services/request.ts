import axios from 'axios'
import type { AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import type { ApiResponse } from '@/types'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000
})

// Request interceptor
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const userStore = useUserStore()
    const appStore = useAppStore()
    
    // Set loading state
    appStore.setLoading(true)
    
    // Add token
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    
    return config
  },
  (error) => {
    const appStore = useAppStore()
    appStore.setLoading(false)
    return Promise.reject(error)
  }
)

// Response interceptor
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const appStore = useAppStore()
    appStore.setLoading(false)
    
    const { data } = response
    
    // Business error
    if (data.code !== 0) {
      const error = new Error(data.message || '请求失败')
      error.name = 'BusinessError'
      return Promise.reject(error)
    }
    
    return data as any
  },
  (error) => {
    const appStore = useAppStore()
    appStore.setLoading(false)
    
    const userStore = useUserStore()
    
    if (error.code === 'ECONNABORTED') {
      error.message = '请求超时，请稍后重试'
    } else if (error.response) {
      const { status } = error.response
      switch (status) {
        case 401:
          error.message = '登录已过期，请重新登录'
          userStore.logout()
          break
        case 403:
          error.message = '没有权限访问'
          break
        case 404:
          error.message = '请求的资源不存在'
          break
        case 500:
          error.message = '服务器内部错误'
          break
        default:
          error.message = `请求失败 (${status})`
      }
    } else if (error.message?.includes('Network Error')) {
      error.message = '网络连接异常，请检查网络'
    }
    
    console.error('[request]', error.message)
    return Promise.reject(error)
  }
)

// Helper methods
export function get<T>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return request.get(url, config)
}

export function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return request.post(url, data, config)
}

export function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return request.put(url, data, config)
}

export function del<T>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>> {
  return request.delete(url, config)
}

export default { get, post, put, del }
