/**
 * 移动端页面共享的唯一运行时数据源。
 * CREATE recipe 会按用户规格整体替换本文件，页面与布局不得改为读取平行的影子数据文件。
 */
export const themeVars = {
  primaryColor: '#ef5a2f',
  buttonPrimaryBackground: '#ef5a2f',
  navBarBackground: '#fffaf5',
  navBarTitleTextColor: '#1f1a17',
  tabsBottomBarColor: '#ef5a2f'
}

export const tabs = [
  { label: '首页', path: '/', icon: '⌂' },
  { label: '分类', path: '/category', icon: '◫' },
  { label: '订单', path: '/orders', icon: '▣' },
  { label: '我的', path: '/profile', icon: '◎' }
]

export const banners = [
  { title: '轻享生活', desc: '便捷服务与精选内容，一站式触达' },
  { title: '本周精选', desc: '热门内容与限时体验活动' },
  { title: '会员专享', desc: '持续解锁更多会员权益' }
]

export const quickEntries = [
  { title: '今日推荐', icon: '✨' },
  { title: '新人专区', icon: '🎁' },
  { title: '热门活动', icon: '🔥' },
  { title: '预约服务', icon: '📅' },
  { title: '会员中心', icon: '💳' },
  { title: '优惠权益', icon: '🎫' },
  { title: '订单进度', icon: '🚚' },
  { title: '客服帮助', icon: '🎧' }
]

export const productSections = [
  {
    title: '热门服务',
    items: [
      { id: 1, name: '入门体验', price: 49, tag: '新人推荐' },
      { id: 2, name: '进阶方案', price: 99, tag: '本周热门' },
      { id: 3, name: '专属服务', price: 199, tag: '会员专享' }
    ]
  },
  {
    title: '限时活动',
    items: [
      { id: 4, name: '精选组合包', price: 129, originalPrice: 169, tag: '限时优惠' },
      { id: 5, name: '新人体验课', price: 29.9, tag: '立即预约' }
    ]
  }
]

export const categories = [
  { title: '推荐', count: 12 },
  { title: '服务分类', count: 18 },
  { title: '热门活动', count: 8 },
  { title: '会员专享', count: 6 }
]

export const orderSteps = ['已下单', '处理中', '服务中', '已完成']

export const orders = [
  { id: 'APP20260824001', title: '入门体验', status: 2, amount: 49, eta: '今天 18:20 前完成' },
  { id: 'APP20260823009', title: '进阶方案', status: 3, amount: 99, eta: '已完成' }
]

export const profileCards = [
  { title: '优惠券', value: '6 张可用' },
  { title: '会员积分', value: '2,480' },
  { title: '已购服务', value: '8 项' }
]
