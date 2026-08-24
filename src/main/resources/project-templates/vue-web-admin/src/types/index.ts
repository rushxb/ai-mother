// API Response Types
export interface ApiResponse<T = unknown> {
  code: number
  data: T
  message: string
}

export interface PageResult<T = unknown> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

// User Types
export interface UserInfo {
  id: number
  userAccount: string
  userName: string
  userAvatar: string
  userRole: 'user' | 'admin'
  createTime: string
}

export interface LoginParams {
  userAccount: string
  userPassword: string
}

export interface LoginResult {
  token: string
  user: UserInfo
}

// Navigation Types
export interface NavItem {
  label: string
  path: string
  icon?: string
  children?: NavItem[]
}

// Route Types
export interface RouteRedirectManifest {
  path: string
  redirect: string
}

export interface RouteViewManifest {
  path: string
  name: string
  component: string
  title: string
  layout?: string
  meta?: {
    requiresAuth?: boolean
    roles?: string[]
    icon?: string
  }
}

export type RouteManifest = RouteRedirectManifest | RouteViewManifest

// Admin Types
export interface DashboardMetrics {
  label: string
  value: string | number
  trend: string
  trendType: 'up' | 'down' | 'flat'
}

export interface OrderInfo {
  no: string
  product: string
  buyer: string
  status: string
  amount: number
  createTime: string
}

export interface TableColumn {
  key: string
  title: string
  width?: number
  fixed?: 'left' | 'right'
  render?: (row: any) => string
}
