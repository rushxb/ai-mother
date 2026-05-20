/**
 * 环境变量配置
 */
import {CodeGenTypeEnum} from "@/utils/codeGenTypes.ts";

const getCurrentOrigin = () => {
  if (typeof window !== 'undefined' && window.location?.origin) {
    return window.location.origin
  }
  return 'http://localhost:8088'
}

// 应用部署域名
export const DEPLOY_DOMAIN = import.meta.env.VITE_DEPLOY_DOMAIN || getCurrentOrigin()

// API 基础地址
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

// 静态资源地址
export const STATIC_BASE_URL = `${API_BASE_URL}/static`

// 获取部署应用的完整URL
export const getDeployUrl = (deployKey: string) => {
  const deployDomain = DEPLOY_DOMAIN.replace(/\/$/, '')
  return `${deployDomain}/${deployKey}/`
}

// 获取静态资源预览URL（用于非 Vue 项目）
export const getStaticPreviewUrl = (codeGenType: string, appId: string) => {
  const baseUrl = `${STATIC_BASE_URL}/${codeGenType}_${appId}/`
  // 如果是 Vue 项目，浏览地址需要添加 dist 后缀
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) {
    return `${baseUrl}dist/index.html`
  }
  return baseUrl
}

// 获取 Vue 开发服务器预览URL（通过后端代理，保证同源以支持可视化编辑）
export const getDevServerPreviewUrl = (appId: string | number, port: number) => {
  // 使用完整 URL（包含当前域名），避免 Vue Router 误匹配
  const origin = getCurrentOrigin()
  const url = `${origin}${API_BASE_URL}/app/dev-server/proxy/${appId}/`
  console.log('getDevServerPreviewUrl:', url)
  return url
}

// 获取预览URL（根据项目类型自动选择）
export const getPreviewUrl = (codeGenType: string, appId: string, devServerPort?: number | null) => {
  // Vue 项目且有 dev server 端口时，使用 dev server（通过后端代理）
  if ((codeGenType === CodeGenTypeEnum.VUE_PROJECT || codeGenType === CodeGenTypeEnum.FULL_STACK_PROJECT) && devServerPort) {
    return getDevServerPreviewUrl(appId, devServerPort)
  }
  // 其他情况使用静态资源
  return getStaticPreviewUrl(codeGenType, appId)
}
