/**
 * Landing & Blog 数据文件
 *
 * 包含五类数据，覆盖常见个人创意项目场景：
 * 1. 落地页数据：brand, nav, stats, highlights, cases, process, plans, faqs
 * 2. 博客数据：author, featuredPosts, categories, newsletter, projects
 * 3. 企业官网数据：enterprise, team, services, testimonials
 * 4. 在线商城数据：products, cartConfig, storeInfo
 * 5. 作品展示数据：portfolioWorks, skills, experience
 *
 * AI 生成页面时可直接从本文件导入所需数据，无需额外创建 .ts 文件。
 * 所有业务内容集中在此，便于 AI 快速替换。
 */

// ============================================================
// 落地页数据
// ============================================================

export const brand = {
  name: 'Atlas Launch',
  headline: '让产品发布页在一天内拥有完整转化结构',
  description:
    '这是一个为 AI 改造准备的展示型模板。首屏、亮点、案例、流程、价格、FAQ 都已经预置，后续可以快速替换成任意品牌或活动主题。',
  cta: '预约演示',
  secondary: '查看案例'
}

export const nav = ['亮点', '案例', '流程', '价格', 'FAQ']

export const stats = [
  { value: '42%', label: '平均转化提升' },
  { value: '1 天', label: '首版上线周期' },
  { value: '8+', label: '可替换区块' }
]

export const highlights = [
  { title: '完整首屏', text: '标题、说明、行动按钮、信任数据和视觉区域都已就位。' },
  { title: '转化路径', text: '从认知、兴趣、验证到行动，页面区块有明确顺序。' },
  { title: '内容友好', text: '所有业务内容集中在 data 文件，便于 AI 快速替换。' },
  { title: '部署友好', text: '使用 hash 路由和相对 base，适合子路径静态部署。' }
]

export const cases = [
  { title: 'SaaS 产品发布', text: '用于呈现功能、客户证言、价格套餐和预约入口。' },
  { title: '线下活动招募', text: '用于活动介绍、日程、讲师、席位和报名转化。' },
  { title: '品牌服务官网', text: '用于展示方法论、案例、流程和咨询入口。' }
]

export const process = ['定位核心受众', '整理卖点和证据', '生成页面内容', '上线并收集反馈']

export const plans = [
  { name: 'Starter', price: '¥999', desc: '适合单个页面或活动验证。', features: ['1 个落地页', '基础响应式', '静态部署'] },
  { name: 'Growth', price: '¥2999', desc: '适合产品发布和中小型官网。', features: ['多区块页面', '案例和 FAQ', '转化表单'] },
  { name: 'Custom', price: '定制', desc: '适合品牌系统和多页面站点。', features: ['主题定制', '多页面扩展', '数据接入'] }
]

export const faqs = [
  { q: '模板是否只能做官网？', a: '不是。它也适合活动页、招募页、产品介绍页、课程页和服务页。' },
  { q: '后续 AI 应该主要改哪里？', a: '优先改 src/data/landingData.js，再根据需要调整 pages 和组件。' },
  { q: '可以接接口吗？', a: '可以。建议新增 src/api 目录，保持页面只消费结构化数据。' }
]

// ============================================================
// 博客/个人站点数据
// ============================================================

/**
 * 作者信息
 * 用于个人博客、作品展示等场景
 */
export const author = {
  name: '林一',
  avatar: 'https://api.dicebear.com/7.x/notionists/svg?seed=linyi&scale=200',
  bio: '独立开发者 / 产品设计师，关注前端技术、交互设计和数字产品构建。记录学习与实践中的思考。',
  role: '独立开发者 & 设计师',
  social: { twitter: '@linyi_dev', github: 'linyi-dev', email: 'hi@linyi.me' },
  about: [
    '你好，我是林一。一名独立开发者与产品设计师，目前居住在上海。',
    '我在前端开发领域工作超过 8 年，曾任职于多家互联网公司和创业团队。2023 年开始以独立开发者身份工作，为客户构建数字产品的同时，也在开发自己的 side project。',
    '这个博客是我记录技术思考、设计心得和独立开发实践的地方。我相信写下来是最好的学习方式——把模糊的想法变成清晰的文字，既是对自己的梳理，也希望能为同行者提供一点参考。'
  ],
  values: [
    { title: '简洁', text: '无论是代码还是界面，追求恰到好处的简洁——不多不少，刚刚好。' },
    { title: '实用', text: '写有实际操作价值的内容，不堆概念，不写空洞的理论。' },
    { title: '开放', text: '开源工具和知识，相信社区的集体智慧大于个人。' }
  ]
}

/**
 * 精选文章列表
 * 每篇文章包含完整元数据，支持列表展示、详情页和标签过滤
 */
export const featuredPosts = [
  {
    slug: 'building-a-design-system',
    title: '从零搭建一个设计系统：我的实践与反思',
    excerpt: '经过三个项目的迭代，我总结了一套适合小团队的设计系统搭建方法，从 Token 体系到组件库，完整复盘。',
    date: '2025-05-15',
    readTime: '12 分钟',
    category: '设计',
    tags: ['设计系统', '组件库', '前端'],
    cover: 'https://picsum.photos/seed/designsys/800/450',
    content: [
      '经过三个项目的迭代，我总结了一套适合小团队的设计系统搭建方法。这篇文章将完整复盘整个流程——从 Design Token 到组件库，再到落地实践。',
      '很多人觉得设计系统是大公司的专利，只有几十上百人的团队才需要。但实际上，只要你的产品有两个以上的页面、两位以上的开发者，设计系统就能带来显著的效率提升。',
      '## 从 Token 开始\n\nDesign Token 是设计系统的原子单元。颜色、字号、间距、阴影、圆角——这些最基础的视觉元素需要先被定义和命名。我们使用 CSS 自定义属性实现，让开发侧零成本接入。\n\n```css\n:root {\n  --color-primary: #315f52;\n  --color-accent: #b9322b;\n  --color-text: #1a1a2e;\n  --color-bg: #fafaf9;\n  --space-xs: 4px;\n  --space-sm: 8px;\n  --space-md: 16px;\n  --space-lg: 24px;\n  --space-xl: 48px;\n  --radius-sm: 4px;\n  --radius-md: 8px;\n  --radius-lg: 16px;\n}\n```',
      '## 组件屋策略\n\n我采用了"组件屋"的策略：底层是基础组件（Button、Input、Card），上层是业务组件（UserCard、OrderTable），中间用组合层衔接。这样做的好处是底层足够稳定，上层足够灵活。',
      '## 落地与推广\n\n设计系统最难的不是搭建，而是推广和持续维护。我们建了一个内部文档站，每次更新都发 changelog，定期收集反馈。经过三个项目的迭代，这套体系让新功能开发速度提升了约 40%，设计还原度从 70% 提升到了 95% 以上。',
      '如果你也在考虑搭建设计系统，我的建议是：从小开始，解决实际问题，不要追求完美。一个能用但不够完美的系统，远胜于一个完美但永远在规划中的系统。'
    ]
  },
  {
    slug: 'vue3-composition-patterns',
    title: 'Vue 3 Composition API 的 6 个实用模式',
    excerpt: '组合式函数、上下文注入、异步状态管理等经过生产验证的模式，帮你写出更清晰的 Vue 3 代码。',
    date: '2025-04-28',
    readTime: '10 分钟',
    category: '前端',
    tags: ['Vue', 'Composition API', 'TypeScript'],
    cover: 'https://picsum.photos/seed/vue3patterns/800/450',
    content: [
      'Composition API 是 Vue 3 最核心的变革之一。经过一年多的生产实践，我总结出了 6 个经过验证的实用模式。',
      '## 1. 组合式函数（Composables）\n\n将可复用的逻辑提取到 useXxx 函数中，这是 Composition API 最基本的模式。关键在于粒度控制——一个 composable 只做一件事。',
      '## 2. 异步状态管理\n\n用 composable 封装 loading/error/data 三态，避免每个组件都写重复的状态判断。',
      '## 3. 依赖注入 + 组合\n\nprovide/inject 结合 composable，适合跨层级共享状态同时又不想引入 Pinia 的场景。',
      '## 4. 响应式数据工厂\n\n用 ref/reactive 创建数据的工厂函数，在需要重置状态时特别有用。',
      '## 5. 生命周期组合\n\n将多个生命周期逻辑归类到不同的 composable 中，避免 setup 函数变得臃肿。',
      '## 6. 类型安全的事件总线\n\n用 TypeScript 泛型约束事件的 payload 类型，让 EventBus 模式在大型项目中也可追溯。',
      '这 6 个模式基本覆盖了我日常开发的 90% 场景。掌握它们，你的 Vue 3 代码会清晰很多。'
    ]
  },
  {
    slug: 'indie-product-lessons',
    title: '做了 3 个独立产品后的 10 条教训',
    excerpt: '从第一个 MVP 到略有收入的 side project，分享我在产品定位、技术选型和推广上踩过的坑。',
    date: '2025-04-10',
    readTime: '8 分钟',
    category: '产品',
    tags: ['独立开发', '产品思维', 'MVP'],
    cover: 'https://picsum.photos/seed/indielessons/800/450',
    content: [
      '从第一个充满 Bug 的 MVP 到开始有用户主动付费的产品，这三年我踩了不少坑。这篇文章整理了 10 条最有价值的教训。',
      '1. **先找用户再写代码**：我的第一个产品是先写了三个月代码才去找用户的，结果发现需求完全是错的。',
      '2. **MVP 要真的"最小"**：第二版仍然功能太多。真正的 MVP 应该让核心用户在 5 分钟内体验完整流程。',
      '3. **定价从第一天开始**：免费用户不等于产品验证。愿意付费才是真实的用户需求信号。',
      '4. **Building in Public 是超能力**：把开发过程公开分享，不仅能积累早期用户，还能获得宝贵的反馈。',
      '5. **技术栈选熟悉的**：不要为了"酷"而选新技术。独立开发者最宝贵的资源是时间。',
      '6. **发布 ≠ 结束**：第一版发布后至少需要三个月的持续迭代才能看出产品是否 fit market。',
      '7. **写文档和帮助中心**：这是最容易被忽略但回报率最高的投入之一。',
      '8. **关注 1-2 个渠道**：不要试图覆盖所有社交媒体，在一个渠道深耕比广撒网有效得多。',
      '9. **拒绝完美主义**：发布一个有瑕疵的产品好过一个永远在开发中的完美产品。',
      '10. **保持健康**：独立开发是一场马拉松，不是短跑。规律作息和运动比任何生产力技巧都重要。'
    ]
  },
  {
    slug: 'css-animation-performance',
    title: 'CSS 动画性能优化实战指南',
    excerpt: '深入浏览器渲染流水线，理解 will-change、transform 与 composite 层的秘密，写出 60fps 的动画。',
    date: '2025-03-22',
    readTime: '15 分钟',
    category: '前端',
    tags: ['CSS', '性能', '动画'],
    cover: 'https://picsum.photos/seed/cssanim/800/450',
    content: [
      '在浏览器中实现流畅的 60fps 动画并不容易。这篇文章将带你深入浏览器的渲染流水线，理解性能瓶颈在哪里，以及如何优化。',
      '## 渲染流水线\n\n浏览器渲染一帧的流程：JavaScript → Style → Layout → Paint → Composite。要优化动画性能，关键是跳过 Layout 和 Paint 阶段。',
      '## transform 和 opacity 是你的朋友\n\n这两个属性只会触发 Composite 阶段，不会触发 Layout 和 Paint。任何时候优先使用 transform 来做位移和缩放。',
      '## will-change 的正确用法\n\nwill-change 可以提前告诉浏览器哪些属性会变化，但它是一把双刃剑。滥用会导致内存占用过高。只在确实需要时使用，并且记得在动画结束后移除。',
      '## 实战案例\n\n从实际项目中的几个动画性能问题出发，一步步分析和优化。包括列表动画、页面转场和滚动驱动的视差效果。',
      '优化前后的性能数据对比：从 30fps 到稳定 60fps，内存占用降低了 40%。'
    ]
  },
  {
    slug: 'tailwind-vs-handcrafted-css',
    title: 'Tailwind 与手写 CSS：我的选择框架',
    excerpt: '五年 CSS 经验，三个大型项目，从狂热 Tailwind 到理性回归，分享我的 CSS 方案取舍逻辑。',
    date: '2025-03-05',
    readTime: '9 分钟',
    category: '前端',
    tags: ['CSS', 'Tailwind', '工程化'],
    cover: 'https://picsum.photos/seed/tailwindcss/800/450',
    content: [
      '关于 Tailwind CSS 的争论已经持续了好几年。站在 2025 年回头看，我认为这两者并不是非此即彼的选择。',
      '## Tailwind 的优势\n\n一致性、约束、开发速度——Tailwind 在这三个维度上确实做得好。对于团队协作，Tailwind 的约束机制能有效防止 CSS 的熵增。',
      '## 手写 CSS 的价值\n\n灵活性、可维护性、设计表达——当需要精细控制动画、设计系统需要抽象 token、或者页面有大量自定义设计时，手写 CSS 仍然不可替代。',
      '## 我的选择框架\n\n项目类型决定了方案：\n- 运营后台 / 工具型应用 → Tailwind\n- 品牌官网 / 展示型 → 手写 CSS + CSS Modules\n- 产品型应用 → 混合使用，基础组件用 Tailwind，品牌部分手写',
      '现在我更愿意把这两者看作工具而非信仰。选择合适的方案解决问题，才是工程师的核心能力。'
    ]
  },
  {
    slug: 'building-in-public',
    title: 'Building in Public 一年，我学到了什么',
    excerpt: '公开构建产品、分享开发过程，这种方式的收获远超预期——不仅是用户，更是思维方式的转变。',
    date: '2025-02-18',
    readTime: '11 分钟',
    category: '产品',
    tags: ['Building in Public', '独立开发', '社区'],
    cover: 'https://picsum.photos/seed/buildingpublic/800/450',
    content: [
      '一年前我开始尝试 Building in Public——在 Twitter/X 上分享产品开发的过程、遇到的困难和学到的经验。这一年的收获远超预期。',
      '## 意外的收获\n\n最直接的好处是获得了第一批种子用户。但更大的收获是：公开自己的思考过程迫使我把模糊的想法梳理清楚。写 thread 的过程就是深度思考的过程。',
      '## 持续的压力\n\n当然也有负面的一面。在公众视野中失败是尴尬的，公开承诺后没有兑现会感到羞愧。但正是这种 accountability 让我保持前进。',
      '## 社区的力量\n\n很多陌生人的善意和建议帮了大忙。有人指出我的 landing page 文案有问题，有人帮我测试了第一个版本。这种"集体智慧"的体验是独自开发无法获得的。',
      '如果你也在考虑 Building in Public，我的建议是：从小事开始分享，找到自己的节奏，不要为了发布而发布。真实和持续，比完美重要得多。'
    ]
  }
]

/**
 * 文章分类
 */
export const categories = [
  { name: '前端', count: 18 },
  { name: '设计', count: 12 },
  { name: '产品', count: 9 },
  { name: '思考', count: 7 },
  { name: '工具', count: 5 }
]

/**
 * Newsletter 订阅配置
 */
export const newsletter = {
  title: '订阅我的 Newsletter',
  description: '每月一封邮件，分享最新文章、工具推荐和独立开发思考。不 spam，随时退订。'
}

/**
 * 个人项目展示
 */
export const projects = [
  { name: 'NotionBoard', description: '将 Notion 数据库可视化为看板的工具' },
  { name: 'TinyLog', description: '轻量级个人日志应用，支持 Markdown' },
  { name: 'Colorly', description: '配色方案生成器和调色板管理工具' }
]

// ============================================================
// 企业官网数据
// ============================================================

/**
 * 企业信息
 * 用于公司官网、团队介绍等场景
 */
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

/**
 * 团队成员
 */
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

/**
 * 服务项目
 */
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

/**
 * 客户评价
 */
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
// 在线商城数据
// ============================================================

/**
 * 商品列表
 */
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

/**
 * 商品分类
 */
export const productCategories = [
  { name: '全部', count: 6 },
  { name: '外设', count: 2 },
  { name: '音频', count: 1 },
  { name: '显示器', count: 1 },
  { name: '家具', count: 1 },
  { name: '配件', count: 1 }
]

/**
 * 商城配置
 */
export const cartConfig = {
  currency: '¥',
  freeShippingThreshold: 299,
  shippingFee: 10,
  maxQuantityPerItem: 10
}

/**
 * 商城信息
 */
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

/**
 * 作品集
 */
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

/**
 * 作品分类
 */
export const portfolioCategories = [
  { name: '全部', count: 6 },
  { name: 'Web 应用', count: 2 },
  { name: '创意网站', count: 1 },
  { name: '移动应用', count: 1 },
  { name: '开发工具', count: 1 },
  { name: '数据可视化', count: 1 },
  { name: '设计工具', count: 1 }
]

/**
 * 技能标签
 */
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

/**
 * 工作经历
 */
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

/**
 * 社交链接
 */
export const socialLinks = [
  { name: 'GitHub', url: 'https://github.com', icon: 'Github' },
  { name: 'Twitter', url: 'https://twitter.com', icon: 'Twitter' },
  { name: 'Dribbble', url: 'https://dribbble.com', icon: 'Dribbble' },
  { name: 'LinkedIn', url: 'https://linkedin.com', icon: 'Linkedin' },
  { name: 'Email', url: 'mailto:hello@example.com', icon: 'Mail' }
]
