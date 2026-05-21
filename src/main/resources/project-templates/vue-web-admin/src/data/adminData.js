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

// ============================================================
// 企业官网数据
// ============================================================

export const enterprise = {
  name: 'NovaTech',
  slogan: '用技术驱动创新，让产品改变世界',
  description: 'NovaTech 是一家专注于前端技术与产品设计的创新公司，为全球客户提供高质量的数字解决方案。',
  founded: '2020',
  headquarters: '上海',
  employees: '50-100',
  mission: '让每个创意都能被优雅地实现',
  vision: '成为全球领先的数字产品创新工作室',
  values: [
    { icon: 'Rocket', title: '创新驱动', text: '持续探索新技术，用创新解决真实问题。' },
    { icon: 'Users', title: '用户至上', text: '以用户需求为核心，打造极致体验。' },
    { icon: 'Shield', title: '品质保障', text: '严格的质量标准，交付值得信赖的产品。' },
    { icon: 'Heart', title: '开放协作', text: '相信团队的力量，开放透明地协作。' }
  ]
}

export const team = [
  {
    name: '张明',
    role: 'CEO & 创始人',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=zhangming&scale=200',
    bio: '10 年互联网产品经验，曾任某大厂产品总监。',
    social: { linkedin: '#', twitter: '#' }
  },
  {
    name: '李华',
    role: 'CTO',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=lihua&scale=200',
    bio: '全栈工程师，开源社区活跃贡献者。',
    social: { github: '#', twitter: '#' }
  },
  {
    name: '王芳',
    role: '设计总监',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=wangfang&scale=200',
    bio: '曾获红点设计奖，专注于交互设计与品牌视觉。',
    social: { dribbble: '#', behance: '#' }
  },
  {
    name: '陈强',
    role: '前端负责人',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=chenqiang&scale=200',
    bio: 'Vue 核心团队成员，热爱开源和技术分享。',
    social: { github: '#', twitter: '#' }
  }
]

export const services = [
  {
    icon: 'Code',
    title: 'Web 应用开发',
    description: '基于 Vue、React 等现代框架，构建高性能、可维护的 Web 应用。',
    features: ['SPA 单页应用', 'SSR 服务端渲染', 'PWA 渐进式应用']
  },
  {
    icon: 'Smartphone',
    title: '移动端开发',
    description: '使用 React Native、Flutter 或原生技术，打造流畅的移动体验。',
    features: ['iOS & Android', '跨平台方案', '性能优化']
  },
  {
    icon: 'Palette',
    title: 'UI/UX 设计',
    description: '从用户研究到视觉设计，提供完整的用户体验解决方案。',
    features: ['用户研究', '交互设计', '视觉设计']
  },
  {
    icon: 'Cloud',
    title: '云服务部署',
    description: '提供云端部署、CI/CD 流水线和运维监控一站式服务。',
    features: ['自动部署', '弹性扩缩', '监控告警']
  }
]

export const testimonials = [
  {
    name: '刘总',
    company: '某科技公司',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=liuzong&scale=200',
    text: 'NovaTech 团队专业、高效，交付的产品远超预期。强烈推荐！',
    rating: 5
  },
  {
    name: '赵经理',
    company: '某电商平台',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=zhaomingli&scale=200',
    text: '合作非常愉快，技术实力强，沟通效率高，项目按时交付。',
    rating: 5
  },
  {
    name: '孙总',
    company: '某创业公司',
    avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=sunzong&scale=200',
    text: '从设计到开发，NovaTech 帮助我们快速实现了产品 MVP。',
    rating: 4
  }
]

// ============================================================
// 在线商城数据（管理后台视角）
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
    stock: 567,
    status: '上架',
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
    stock: 1234,
    status: '上架',
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
    stock: 345,
    status: '上架',
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
    stock: 89,
    status: '上架',
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
    stock: 2345,
    status: '上架',
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
    stock: 678,
    status: '上架',
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
