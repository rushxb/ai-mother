# Project Templates

这些模板用于 AI 生成 Vue 工程前的预置骨架。模板目标不是最小脚手架，而是提供足够完整、结构清晰、易被 AI 局部改造的起点。

## 设计原则

- 默认只保留一个可运行主入口，避免 AI 为了单页场景被迫删除多余页面。
- 额外页面保留为可选示例，不参与默认路由，但保留代码结构，方便后续扩展。
- 导航优先使用页内锚点或局部区域跳转，减少单页项目的改造成本。
- 模板文件越丰富越好，但稳定入口要少而明确。
- 布局、路由、请求、Mock 和业务视图分层，不要让 AI 直接改整页骨架。
- 路由优先使用 `src/router/routeManifest.json`，由 `routeFactory` 自动注册。
- 页面优先放在 `src/views`，`src/pages` 只保留可复用业务样板。
- 关键位置保留 `@AI_INJECT_*` 锚点，供增量修改直接定位。

## 技术栈

| 模板 | 框架 | UI 库 | 状态管理 | 语言 |
|------|------|-------|----------|------|
| vue-web-basic | Vue 3 + Vite | Naive UI | Pinia | TypeScript |
| vue-web-admin | Vue 3 + Vite | Naive UI | Pinia | TypeScript |
| vue-web-mobile | Vue 3 + Vite | Vant 4 | Pinia | TypeScript |
| vue-web-landing | Vue 3 + Vite | 自定义 | - | TypeScript |

## 模板列表

### vue-web-basic
通用 Web 应用，适合信息站、轻应用、内容型产品。
- 预置组件：ProTable、SearchBar、FormModal、SectionTitle
- 预置页面：首页、登录页、403 页
- 状态管理：用户状态、应用状态
- 特性：响应式布局、主题定制、Mock 数据

### vue-web-admin
后台管理应用，包含指标、筛选、表格、弹窗和设置区。
- 预置组件：ProTable、SearchBar、FormModal、MetricGrid、DataTable
- 预置页面：工作台、用户管理、订单管理、登录页
- 状态管理：用户状态、应用状态
- 特性：侧边栏导航、数据表格、表单弹窗

### vue-web-mobile
移动端 H5，基于 Vue 3 + Vant 4。
- 预置组件：ProductCard、BannerSwipe
- 预置页面：首页、分类、购物车、个人中心
- 状态管理：用户状态、购物车状态
- 特性：底部导航、轮播图、商品列表

### vue-web-landing
展示型官网/活动页，包含首屏、亮点、案例、流程和 FAQ。
- 预置组件：LandingSection、PricingCard、FAQItem
- 预置页面：落地页
- 特性：单页滚动、价格方案、常见问题

## @AI_INJECT 标记

| 标记 | 用途 | 位置 |
|------|------|------|
| `@AI_INJECT_VIEW` | 视图内容注入 | `src/views/*.vue`, `src/pages/*.vue` |
| `@AI_INJECT_MOCK` | Mock 数据注入 | `src/mocks/index.ts` |
| `@AI_INJECT_API` | API 接口注入 | `src/services/api.ts` |
| `@AI_INJECT_TABLE_ACTION` | 表格操作注入 | `src/components/ProTable.vue` |
| `@AI_INJECT_STYLE_VARIABLES` | 样式变量注入 | `src/styles/theme.css` |
| `@AI_INJECT_STYLES` | 样式注入 | `src/styles/theme.css` |
| `@AI_INJECT_FILTER` | 筛选条件注入 | `src/components/SearchBar.vue` |
| `@AI_INJECT_MODAL` | 弹窗内容注入 | `src/components/FormModal.vue` |
| `@AI_INJECT_STORE_ACTION` | Store 方法注入 | `src/stores/*.ts` |
| `@AI_INJECT_ROUTE_FACTORY` | 路由工厂注入 | `src/router/routeFactory.ts` |
| `@AI_INJECT_ROUTER_GUARD` | 路由守卫注入 | `src/router/index.ts` |

## AI 改造约定

- 优先修改 `src/data` 中的数据和文案。
- 页面级结构放在 `src/pages`。
- 可复用 UI 放在 `src/components`。
- 全局主题和响应式规则放在 `src/styles`。
- 默认只接入主页面路由，其他页面作为"可扩展样板"保留，不要为了减少文件而提前删除。
- 列表型业务优先复用 `ProTable`，只传 `columns` 和 `rows`。
- 后端未完成时先补 Mock，再切到真实接口。
- 不要删除 `vite.config.ts`、`index.html`、`src/main.ts`、`src/router/index.ts` 这些稳定工程文件，除非明确需要。
- 使用 TypeScript 编写所有新代码，保持类型安全。
- 使用 Pinia 管理全局状态，启用持久化插件。
- 使用 Naive UI 组件库（vue-web-basic、vue-web-admin），保持 UI 一致性。

## 目录结构

```
src/
├── components/        # 可复用组件
├── data/              # 数据和配置
├── layouts/           # 布局组件
├── mocks/             # Mock 数据
├── pages/             # 页面组件
├── router/            # 路由配置
├── services/          # API 服务
├── stores/            # 状态管理
├── styles/            # 样式文件
├── types/             # 类型定义
├── views/             # 视图组件
├── App.vue            # 根组件
├── main.ts            # 入口文件
└── env.d.ts           # 环境变量类型
```
