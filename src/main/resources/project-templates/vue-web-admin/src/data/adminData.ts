import type { DashboardMetrics, OrderInfo } from '@/types'

export interface AdminSite {
  brand: string
  slogan: string
  nav: AdminNavItem[]
}

export interface AdminNavItem {
  label: string
  path: string
  icon?: string
  children?: AdminNavItem[]
}

export const site: AdminSite = {
  brand: 'Admin Pro',
  slogan: '企业级后台管理系统',
  nav: [
    { label: '工作台', path: '/dashboard', icon: 'Dashboard' },
    { label: '用户管理', path: '/users', icon: 'People' },
    { label: '订单管理', path: '/orders', icon: 'ShoppingCart' },
    { label: '系统设置', path: '/settings', icon: 'Settings' }
  ]
}

export const metrics: DashboardMetrics[] = [
  { label: '今日访问', value: '24,890', trend: '+12.6%', trendType: 'up' },
  { label: '新增用户', value: '1,024', trend: '+8.2%', trendType: 'up' },
  { label: '订单数量', value: '3,456', trend: '+15.3%', trendType: 'up' },
  { label: '转化率', value: '3.24%', trend: '-0.4%', trendType: 'down' }
]

export const orders: OrderInfo[] = [
  { no: 'ORD-2024-001', product: '企业版套餐', buyer: '张三', status: '已完成', amount: 9800, createTime: '2024-01-15 10:30:00' },
  { no: 'ORD-2024-002', product: '专业版套餐', buyer: '李四', status: '待支付', amount: 4800, createTime: '2024-01-15 11:20:00' },
  { no: 'ORD-2024-003', product: '基础版套餐', buyer: '王五', status: '已取消', amount: 1800, createTime: '2024-01-15 12:10:00' },
  { no: 'ORD-2024-004', product: '企业版套餐', buyer: '赵六', status: '进行中', amount: 9800, createTime: '2024-01-15 13:45:00' },
  { no: 'ORD-2024-005', product: '专业版套餐', buyer: '钱七', status: '已完成', amount: 4800, createTime: '2024-01-15 14:30:00' }
]

export const activities: string[] = [
  '张三 提交了新的订单 #ORD-2024-001',
  '李四 完成了企业版套餐的支付',
  '系统自动发送了月度报告',
  '王五 更新了个人资料',
  '新用户注册：赵六'
]

// @AI_INJECT_DATA
