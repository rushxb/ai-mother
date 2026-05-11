export const appInfo = {
  name: 'Console Pro',
  org: '运营管理中心',
  operator: '管理员 张明'
}

export const navItems = [
  { label: '总览', path: '#overview', icon: '▦' },
  { label: '趋势', path: '#trend', icon: '◎' },
  { label: '订单', path: '#orders', icon: '◇' },
  { label: '设置', path: '#settings', icon: '○' }
]

export const metrics = [
  { label: '今日访问', value: '24,890', trend: '+12.6%', tone: 'green' },
  { label: '新增用户', value: '1,284', trend: '+8.1%', tone: 'blue' },
  { label: '待处理工单', value: '36', trend: '-4.2%', tone: 'orange' },
  { label: '成交金额', value: '¥438k', trend: '+18.4%', tone: 'dark' }
]

export const users = [
  { id: 1001, name: '林若溪', role: '会员', status: '活跃', city: '上海', amount: 12880, date: '2026-05-09' },
  { id: 1002, name: '周一鸣', role: '运营', status: '待审核', city: '杭州', amount: 8320, date: '2026-05-08' },
  { id: 1003, name: '陈念', role: '会员', status: '冻结', city: '南京', amount: 4190, date: '2026-05-06' },
  { id: 1004, name: '许知远', role: '管理员', status: '活跃', city: '深圳', amount: 23560, date: '2026-05-04' },
  { id: 1005, name: '王可', role: '会员', status: '活跃', city: '成都', amount: 6590, date: '2026-05-01' }
]

export const orders = [
  { no: 'SO20260511001', product: '企业版套餐', buyer: '星河科技', status: '已支付', total: 59800 },
  { no: 'SO20260510018', product: '咨询服务', buyer: '南风设计', status: '待确认', total: 12800 },
  { no: 'SO20260509036', product: '数据看板', buyer: '禾木零售', status: '交付中', total: 36800 }
]

export const activities = [
  '完成 5 个用户审核任务',
  '新增 2 条系统公告',
  '订单 SO20260510018 等待财务确认',
  '数据同步任务在 09:30 成功执行'
]
