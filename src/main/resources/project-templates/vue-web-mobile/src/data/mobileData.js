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
  { title: '鲜食到家', desc: '30 分钟送达，满 59 元减 10 元' },
  { title: '会员周卡', desc: '开通即送 8 张免配送券' },
  { title: '夏季新品', desc: '限定果饮与轻食组合上新' }
]

export const quickEntries = [
  { title: '新人专区', icon: '🎁' },
  { title: '优惠拼单', icon: '🛍' },
  { title: '即时配送', icon: '🚚' },
  { title: '下午茶', icon: '☕' },
  { title: '优选水果', icon: '🍊' },
  { title: '家庭套餐', icon: '🥗' },
  { title: '会员中心', icon: '💳' },
  { title: '客服帮助', icon: '🎧' }
]

export const productSections = [
  {
    title: '热销推荐',
    items: [
      { id: 1, name: '轻体果蔬盒', price: 29.9, tag: '月销 2k+' },
      { id: 2, name: '香草鸡胸能量餐', price: 36.8, tag: '新品' },
      { id: 3, name: '冷萃咖啡双杯装', price: 24.0, tag: '下午茶' }
    ]
  },
  {
    title: '今晚加餐',
    items: [
      { id: 4, name: '番茄牛腩饭', price: 28.8, tag: '门店现做' },
      { id: 5, name: '海盐可颂三件组', price: 18.5, tag: '加价购' },
      { id: 6, name: '牛油果沙拉杯', price: 22.8, tag: '低脂' }
    ]
  }
]

export const categories = [
  { title: '推荐', count: 18 },
  { title: '鲜食轻餐', count: 26 },
  { title: '果切饮品', count: 14 },
  { title: '零食甜点', count: 33 },
  { title: '生活百货', count: 21 }
]

export const orderSteps = ['已下单', '备货中', '配送中', '已送达']

export const orders = [
  { id: 'A20260511001', title: '轻体果蔬盒 + 冷萃咖啡', status: 2, amount: 53.9, eta: '预计 18:20 送达' },
  { id: 'A20260510019', title: '家庭套餐', status: 3, amount: 88.0, eta: '已完成' }
]

export const profileCards = [
  { title: '优惠券', value: '12 张可用' },
  { title: '积分', value: '8,460' },
  { title: '收藏', value: '28 个商品' }
]
