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
