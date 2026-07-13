/**
 * 环境地址配置。生产环境优先使用同源相对路径，避免把开发机地址带到用户浏览器。
 */
import { CodeGenTypeEnum } from '@/utils/codeGenTypes'

const getCurrentOrigin = () => {
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin
  }
  return 'http://localhost:91'
}

const resolveBaseUrl = (configuredValue: string | undefined, fallbackPath: string) => {
  const value = configuredValue?.trim() || fallbackPath
  if (/^https?:\/\//i.test(value)) {
    return value.replace(/\/$/, '')
  }
  const normalizedPath = value.startsWith('/') ? value : `/${value}`
  return `${getCurrentOrigin()}${normalizedPath}`.replace(/\/$/, '')
}

export const DEPLOY_DOMAIN = resolveBaseUrl(import.meta.env.VITE_DEPLOY_DOMAIN, '/deploy')
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL?.trim() || '/api').replace(/\/$/, '')
export const STATIC_BASE_URL = `${API_BASE_URL}/static`

export const getDeployUrl = (deployKey: string) => {
  return `${DEPLOY_DOMAIN}/${encodeURIComponent(deployKey)}/`
}

export const getStaticPreviewUrl = (codeGenType: string, appId: string | number) => {
  const baseUrl = `${STATIC_BASE_URL}/${encodeURIComponent(codeGenType)}_${encodeURIComponent(String(appId))}/`
  return codeGenType === CodeGenTypeEnum.VUE_PROJECT ? `${baseUrl}dist/index.html` : baseUrl
}

/**
 * Vue/全栈预览统一走鉴权代理。浏览器绝不能访问服务器的 localhost 端口。
 * 端口由后端根据 appId 查找并转发，前端不参与网络拓扑决策。
 */
export const getDevServerPreviewUrl = (appId: string | number) => {
  return `${API_BASE_URL}/app/dev-server/proxy/${encodeURIComponent(String(appId))}/`
}

export const getPreviewUrl = (codeGenType: string, appId: string | number, devServerPort?: number | null) => {
  if (
    (codeGenType === CodeGenTypeEnum.VUE_PROJECT || codeGenType === CodeGenTypeEnum.FULL_STACK_PROJECT) &&
    devServerPort
  ) {
    return getDevServerPreviewUrl(appId)
  }
  return getStaticPreviewUrl(codeGenType, appId)
}
