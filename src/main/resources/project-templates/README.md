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

## 模板列表

- `vue-web-basic`: 通用 Web 应用，适合信息站、轻应用、内容型产品。
- `vue-web-admin`: 后台管理应用，包含指标、筛选、表格、弹窗和设置区。
- `vue-web-mobile`: 移动端 H5，基于 Vue 3 + Vant 4。
- `vue-web-landing`: 展示型官网/活动页，包含首屏、亮点、案例、流程和 FAQ。

## AI 改造约定

- 优先修改 `src/data` 中的数据和文案。
- 页面级结构放在 `src/pages`。
- 可复用 UI 放在 `src/components`。
- 全局主题和响应式规则放在 `src/styles`。
- 默认只接入主页面路由，其他页面作为“可扩展样板”保留，不要为了减少文件而提前删除。
- 列表型业务优先复用 `ProTable`，只传 `columns` 和 `rows`。
- 后端未完成时先补 Mock，再切到真实接口。
- 不要删除 `vite.config.js`、`index.html`、`src/main.js`、`src/router/index.js` 这些稳定工程文件，除非明确需要。
