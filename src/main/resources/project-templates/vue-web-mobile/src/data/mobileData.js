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

// ============================================================
// 在线商城数据（移动端视角）
// ============================================================

export const products = [
  {
    id: 1,
    name: '极简机械键盘',
    price: 599,
    originalPrice: 799,
    image: 'https://picsum.photos/seed/keyboard/400/400',
    category: '外设',
    tags: ['热卖', '新品'],
    rating: 4.8,
    sales: 1234,
    description: '87 键紧凑布局，Cherry MX 红轴，PBT 键帽，支持热插拔。'
  },
  {
    id: 2,
    name: '无线降噪耳机',
    price: 899,
    originalPrice: 1299,
    image: 'https://picsum.photos/seed/headphone/400/400',
    category: '音频',
    tags: ['爆款'],
    rating: 4.9,
    sales: 2567,
    description: '主动降噪，40 小时续航，Hi-Res 认证音质。'
  },
  {
    id: 3,
    name: '便携显示器',
    price: 1299,
    originalPrice: 1599,
    image: 'https://picsum.photos/seed/monitor/400/400',
    category: '显示器',
    tags: ['新品'],
    rating: 4.7,
    sales: 890,
    description: '15.6 英寸 4K OLED，Type-C 一线通，重量仅 680g。'
  },
  {
    id: 4,
    name: '人体工学椅',
    price: 1999,
    originalPrice: 2599,
    image: 'https://picsum.photos/seed/chair/400/400',
    category: '家具',
    tags: ['热卖'],
    rating: 4.6,
    sales: 567,
    description: '自适应腰靠，4D 扶手，网布透气，12 年质保。'
  },
  {
    id: 5,
    name: '桌面氛围灯',
    price: 199,
    originalPrice: 299,
    image: 'https://picsum.photos/seed/light/400/400',
    category: '配件',
    tags: ['特价'],
    rating: 4.5,
    sales: 3456,
    description: 'RGB 可调色温，支持音乐律动，Type-C 供电。'
  },
  {
    id: 6,
    name: '编程专用鼠标',
    price: 399,
    originalPrice: 499,
    image: 'https://picsum.photos/seed/mouse/400/400',
    category: '外设',
    tags: ['推荐'],
    rating: 4.7,
    sales: 1890,
    description: '静音微动，8 个可编程按键，16000 DPI，适合长时间使用。'
  }
]

export const productCategories = [
  { name: '全部', count: 6 },
  { name: '外设', count: 2 },
  { name: '音频', count: 1 },
  { name: '显示器', count: 1 },
  { name: '家具', count: 1 },
  { name: '配件', count: 1 }
]

export const cartConfig = {
  currency: '¥',
  freeShippingThreshold: 299,
  shippingFee: 10,
  maxQuantityPerItem: 10
}

export const storeInfo = {
  name: '极客好物',
  slogan: '为程序员精选的好物',
  description: '我们精选每一件商品，只为给你最好的使用体验。',
  contact: {
    email: 'support@geekstore.com',
    phone: '400-123-4567',
    address: '上海市浦东新区张江高科技园区'
  },
  policies: {
    returnPolicy: '7 天无理由退换',
    warrantyPolicy: '官方正品，全国联保',
    shippingPolicy: '满 299 包邮，48 小时发货'
  }
}

// ============================================================
// 作品展示数据
// ============================================================

export const portfolioWorks = [
  {
    id: 1,
    title: 'Nova Dashboard',
    category: 'Web 应用',
    image: 'https://picsum.photos/seed/dashboard/800/600',
    tags: ['Vue 3', 'TypeScript', 'Tailwind CSS'],
    description: '企业级数据可视化后台，支持实时数据监控和自定义仪表盘。',
    link: '#',
    github: '#',
    featured: true
  },
  {
    id: 2,
    title: 'ArtSpace',
    category: '创意网站',
    image: 'https://picsum.photos/seed/artspace/800/600',
    tags: ['Three.js', 'WebGL', 'GSAP'],
    description: '沉浸式 3D 艺术展览平台，支持虚拟漫游和互动体验。',
    link: '#',
    github: '#',
    featured: true
  },
  {
    id: 3,
    title: 'FitTrack',
    category: '移动应用',
    image: 'https://picsum.photos/seed/fittrack/800/600',
    tags: ['React Native', 'Firebase', 'HealthKit'],
    description: '智能健身追踪应用，支持运动数据分析和个性化训练计划。',
    link: '#',
    github: '#',
    featured: false
  },
  {
    id: 4,
    title: 'CodeCollab',
    category: '开发工具',
    image: 'https://picsum.photos/seed/codecollab/800/600',
    tags: ['WebSocket', 'Monaco Editor', 'Node.js'],
    description: '实时协作代码编辑器，支持多人同时编辑和终端共享。',
    link: '#',
    github: '#',
    featured: true
  },
  {
    id: 5,
    title: 'EcoTracker',
    category: '数据可视化',
    image: 'https://picsum.photos/seed/ecotracker/800/600',
    tags: ['D3.js', 'Python', 'PostgreSQL'],
    description: '环境数据监测平台，实时展示空气质量和碳排放数据。',
    link: '#',
    github: '#',
    featured: false
  },
  {
    id: 6,
    title: 'PixelForge',
    category: '设计工具',
    image: 'https://picsum.photos/seed/pixelforge/800/600',
    tags: ['Canvas API', 'WebAssembly', 'Vue 3'],
    description: '在线图片编辑器，支持图层管理、滤镜和批量处理。',
    link: '#',
    github: '#',
    featured: false
  }
]

export const portfolioCategories = [
  { name: '全部', count: 6 },
  { name: 'Web 应用', count: 2 },
  { name: '创意网站', count: 1 },
  { name: '移动应用', count: 1 },
  { name: '开发工具', count: 1 },
  { name: '数据可视化', count: 1 },
  { name: '设计工具', count: 1 }
]

export const skills = [
  { name: 'Vue 3', level: 95, category: '前端框架' },
  { name: 'React', level: 88, category: '前端框架' },
  { name: 'TypeScript', level: 92, category: '编程语言' },
  { name: 'JavaScript', level: 95, category: '编程语言' },
  { name: 'Node.js', level: 85, category: '后端' },
  { name: 'Python', level: 78, category: '后端' },
  { name: 'Three.js', level: 82, category: '3D/可视化' },
  { name: 'D3.js', level: 75, category: '3D/可视化' },
  { name: 'Tailwind CSS', level: 90, category: '样式' },
  { name: 'Figma', level: 88, category: '设计' },
  { name: 'Git', level: 92, category: '工具' },
  { name: 'Docker', level: 78, category: '运维' }
]

export const experience = [
  {
    period: '2022 - 至今',
    company: 'NovaTech',
    role: '高级前端工程师',
    description: '负责公司核心产品的前端架构设计和技术选型，带领 5 人团队完成多个大型项目。',
    achievements: ['主导前端微服务架构改造', '性能优化使首屏加载提升 60%', '建立组件库和设计系统']
  },
  {
    period: '2020 - 2022',
    company: '某互联网大厂',
    role: '前端工程师',
    description: '参与电商平台前端开发，负责商品详情页和购物车模块。',
    achievements: ['重构商品详情页，PV 提升 30%', '实现 SSR 首屏优化', '参与前端工程化建设']
  },
  {
    period: '2018 - 2020',
    company: '某创业公司',
    role: '全栈工程师',
    description: '作为早期成员参与产品从 0 到 1 的过程，负责前后端开发。',
    achievements: ['独立完成 MVP 开发', '搭建 CI/CD 流水线', '技术博客获得 10k+ 关注']
  }
]

export const socialLinks = [
  { name: 'GitHub', url: 'https://github.com', icon: 'Github' },
  { name: 'Twitter', url: 'https://twitter.com', icon: 'Twitter' },
  { name: 'Dribbble', url: 'https://dribbble.com', icon: 'Dribbble' },
  { name: 'LinkedIn', url: 'https://linkedin.com', icon: 'Linkedin' },
  { name: 'Email', url: 'mailto:hello@example.com', icon: 'Mail' }
]
