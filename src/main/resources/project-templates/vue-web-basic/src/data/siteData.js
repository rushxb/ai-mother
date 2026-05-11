export const site = {
  brand: 'Nexa Studio',
  slogan: '把复杂业务变成清晰体验',
  nav: [
    { label: '首页', path: '#hero' },
    { label: '模块', path: '#modules' },
    { label: '流程', path: '#timeline' },
    { label: '联系', path: '#contact' }
  ]
}

export const hero = {
  eyebrow: 'AI Friendly Vue Template',
  title: '一套适合快速改造成真实业务的 Vue 应用骨架',
  description:
    '模板预置了导航、首屏、信息卡片、流程、联系区和响应式样式。AI 后续只需要替换数据、调整页面区块、增加组件即可。',
  actions: [
    { label: '查看模块', path: '#modules', type: 'primary' },
    { label: '联系咨询', path: '#contact', type: 'secondary' }
  ]
}

export const features = [
  { title: '清晰结构', text: '数据、页面、组件和样式分层明确，便于局部替换。', stat: '4 层' },
  { title: '响应式布局', text: '默认覆盖桌面、平板和移动端，不依赖复杂 UI 库。', stat: '3 端' },
  { title: '业务可塑性', text: '适合官网、内容产品、轻量管理页和活动页改造。', stat: '多场景' }
]

export const cards = [
  { id: 'core', title: '核心服务', desc: '围绕业务目标整理信息架构、页面路径和内容表达。' },
  { id: 'growth', title: '增长运营', desc: '用结构化内容承载转化、留资、咨询和活动报名。' },
  { id: 'delivery', title: '交付看板', desc: '用轻量页面呈现进度、里程碑、数据和待办事项。' }
]

export const timeline = [
  '梳理目标用户和核心行动',
  '生成可运行页面和基础数据',
  '根据反馈微调文案、布局和组件',
  '构建产物并部署到子路径环境'
]
