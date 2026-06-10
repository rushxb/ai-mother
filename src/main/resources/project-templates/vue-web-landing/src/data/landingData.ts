export interface LandingBrand {
  name: string
  headline: string
  description: string
  cta: string
  secondary: string
}

export interface Stat {
  value: string
  label: string
}

export interface Highlight {
  title: string
  text: string
  icon?: string
}

export interface Case {
  title: string
  text: string
  image?: string
}

export interface Plan {
  name: string
  price: string
  desc: string
  features: string[]
}

export interface FAQ {
  q: string
  a: string
}

export const brand: LandingBrand = {
  name: 'Nexa Studio',
  headline: '把复杂业务变成清晰体验',
  description: '我们专注于产品设计、技术落地和业务增长，帮助团队把想法变成真正可用的数字产品。',
  cta: '预约咨询',
  secondary: '查看案例'
}

export const nav = ['亮点', '案例', '流程', '价格', 'FAQ']

export const stats: Stat[] = [
  { value: '120+', label: '交付项目' },
  { value: '98%', label: '客户满意度' },
  { value: '30天', label: '平均上线周期' }
]

export const highlights: Highlight[] = [
  { title: '产品设计', text: '从用户研究到交互原型，构建清晰、可用、有吸引力的产品体验。' },
  { title: '技术落地', text: '前后端开发、架构设计、性能优化，确保产品稳定高效运行。' },
  { title: '业务增长', text: '数据分析、用户增长、转化优化，帮助产品实现商业价值。' }
]

export const cases: Case[] = [
  { title: '企业官网', text: '为某科技公司设计开发的品牌官网，提升品牌形象和用户转化。' },
  { title: 'SaaS 平台', text: '为某创业团队设计开发的 SaaS 产品，实现用户快速增长。' },
  { title: '电商系统', text: '为某零售企业设计开发的电商系统，提升销售效率和用户体验。' }
]

export const process = [
  '需求沟通',
  '方案设计',
  '开发实施',
  '上线运营'
]

export const plans: Plan[] = [
  {
    name: '基础版',
    price: '¥9,800',
    desc: '适合单页官网和活动页快速上线。',
    features: ['单页面设计开发', '响应式布局', '基础 SEO 优化', '3 个月技术支持'],
  },
  {
    name: '专业版',
    price: '¥29,800',
    desc: '适合多页面官网、案例沉淀和线索转化。',
    features: ['多页面设计开发', '后台管理系统', '数据统计分析', '6 个月技术支持'],
  },
  {
    name: '企业版',
    price: '¥59,800',
    desc: '适合复杂业务展示和定制能力集成。',
    features: ['全栈解决方案', '定制功能开发', '专属技术顾问', '12 个月技术支持'],
  }
]

export const faqs: FAQ[] = [
  { q: '项目周期一般多长？', a: '根据项目复杂度，一般 2-8 周不等。基础版 2-3 周，专业版 4-6 周，企业版 6-8 周。' },
  { q: '支持哪些技术栈？', a: '前端支持 Vue、React、小程序，后端支持 Node.js、Java、Go，数据库支持 MySQL、PostgreSQL、MongoDB。' },
  { q: '售后服务如何？', a: '提供技术支持、Bug 修复、功能迭代和运维监控，具体服务内容根据方案等级确定。' },
  { q: '如何保证项目质量？', a: '采用敏捷开发流程，持续集成、代码审查、自动化测试和性能监控，确保项目质量。' }
]

export const contact = {
  email: 'contact@example.com',
  phone: '400-xxx-xxxx',
  address: '北京市朝阳区 xxx 大厦'
}
