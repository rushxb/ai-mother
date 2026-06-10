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
| vue-web-basic | Vue 3 + Vite | shadcn-vue + Inspira UI | Pinia | TypeScript |
| vue-web-admin | Vue 3 + Vite | shadcn-vue + Inspira UI | Pinia | TypeScript |
| vue-web-mobile | Vue 3 + Vite | Vant 4 + vaul-vue | Pinia | TypeScript |
| vue-web-landing | Vue 3 + Vite | Inspira UI | - | TypeScript |

## 核心库

| 库 | 版本 | 用途 | 模板 |
|----|------|------|------|
| shadcn-vue | latest | 基础 UI 组件（headless） | web-basic, web-admin |
| Inspira UI | latest | 视觉/动画组件 | web-basic, web-admin, web-landing |
| motion-v | ^0.5.0 | 物理动画引擎 | all |
| @tresjs/core | ^4.3.2 | 3D 场景渲染 | web-basic, web-admin, web-mobile |
| three | ^0.170.0 | Three.js 核心 | web-basic, web-admin, web-mobile |
| lenis | ^1.1.18 | 丝滑滚动 | all |
| vaul-vue | ^0.2.0 | iOS 风格底部抽屉 | web-mobile |
| Vant 4 | ^4.9.9 | 移动端 UI 组件 | web-mobile |

## 模板列表

### vue-web-basic
通用 Web 应用，适合个人创意项目、炫酷大胆风格。
- 预置组件：shadcn-vue (Button, Card, Dialog, Input, Label, Select, Badge) + Inspira UI (GradientText, AnimateOnScroll, ParticleField, TypewriterEffect, GlowingOrb) + TresJS (BasicScene, RotatingCube, ParticleSphere)
- 预置页面：首页、登录页、403 页
- 状态管理：用户状态、应用状态
- 特性：响应式布局、主题定制、Mock 数据、3D 场景、物理动画、丝滑滚动

### vue-web-admin
后台管理应用，包含指标、筛选、表格、弹窗和设置区。
- 预置组件：shadcn-vue + Inspira UI + TresJS（同 vue-web-basic）
- 预置页面：工作台、用户管理、订单管理、登录页
- 状态管理：用户状态、应用状态
- 特性：侧边栏导航、数据表格、表单弹窗、3D 场景

### vue-web-mobile
移动端 H5，基于 Vue 3 + Vant 4 + vaul-vue。
- 预置组件：Vant 4（auto-import）+ vaul-vue (DrawerRoot, DrawerTrigger, DrawerContent) + TresJS (BasicScene, RotatingCube)
- 预置页面：首页、分类、购物车、个人中心
- 状态管理：用户状态、购物车状态
- 特性：底部导航、轮播图、商品列表、iOS 风格抽屉、3D 场景

### vue-web-landing
展示型官网/活动页，包含首屏、亮点、案例、流程和 FAQ。
- 预置组件：Inspira UI (GradientText, AnimateOnScroll, ParticleField, TypewriterEffect, GlowingOrb)
- 预置页面：落地页
- 特性：单页滚动、价格方案、常见问题、粒子背景、渐变文字

## 组件库

### shadcn-vue 组件（vue-web-basic, vue-web-admin）

| 组件 | 描述 | 用途 |
|------|------|------|
| Button | 按钮 | 支持多种变体和尺寸 |
| Card | 卡片 | 包含 Header, Title, Description, Content, Footer |
| Dialog | 弹窗 | 包含 Header, Title, Description, Footer |
| Input | 输入框 | 支持 v-model |
| Label | 标签 | 表单标签 |
| Select | 选择器 | 包含 SelectTrigger |
| Badge | 徽章 | 支持多种变体 |

### Inspira UI 组件（vue-web-basic, vue-web-admin, vue-web-landing）

| 组件 | 描述 | 用途 |
|------|------|------|
| GradientText | 渐变文字 | 支持 sm, md, lg, xl 尺寸 |
| AnimateOnScroll | 滚动动画 | 元素进入视口时触发淡入动画 |
| ParticleField | 粒子场背景 | 随机分布的浮动粒子 |
| TypewriterEffect | 打字机效果 | 逐字显示文字，带光标闪烁 |
| GlowingOrb | 发光球体 | 渐变旋转的发光球体 |

### TresJS 组件（vue-web-basic, vue-web-admin, vue-web-mobile）

#### 基础组件

| 组件 | 描述 | 用途 |
|------|------|------|
| BasicScene | 基础 3D 场景 | 包含相机、光照、网格 |
| RotatingCube | 旋转立方体 | 自动旋转的 3D 立方体 |
| ParticleSphere | 粒子球体 | 球形分布的粒子系统 |

#### 视觉特效

| 组件 | 描述 | 用途 |
|------|------|------|
| BloomEffect | 辉光/霓虹 | 让发光物体产生梦幻泛光，赛博朋克风必备 |
| GlassRefraction | 玻璃折射 | 极具质感的毛玻璃、透镜效果，模拟真实物理光学 |
| VolumetricLight | 体积光 | 丁达尔效应，光束穿透感，营造神圣或神秘氛围 |
| MatcapRendering | 材质捕获 | 极低成本实现高级金属、陶瓷或丝绸质感 |
| EnvironmentMapping | 环境反射 | 让物体表面反射出虚拟世界的倒影 |

#### 交互维度

| 组件 | 描述 | 用途 |
|------|------|------|
| ScrollDriven | 滚动驱动 | 镜头随页面滚动穿梭，像拍电影一样讲故事 |
| MouseParallax | 鼠标视差 | 3D场景随鼠标轻微晃动，打破平面沉闷感 |
| RaycasterInteraction | 射线交互 | 点击或悬浮在3D物体上触发精准的UI反馈 |
| GravityPhysics | 重力碰撞 | 让网页元素像真实物体一样掉落、反弹、堆叠 |
| CameraTransition | 相机转场 | 页面切换时，镜头在3D空间中丝滑平移飞行 |

#### 创意粒子

| 组件 | 描述 | 用途 |
|------|------|------|
| ParticleSwarm | 粒子群 | 成千上万的点阵随音乐或鼠标流动，极具生命力 |
| Morphing | 形态畸变 | 物体从一个形状平滑流动地变成另一个形状 |
| InstancedMesh | 高性能群集 | 同时渲染成千上万个独立运动的物体而保持帧率稳定 |
| LiquidShader | 液态流体 | 利用着色器实现岩浆、水波或有机生物的律动 |
| Typography3D | 立体文字 | 让标题文字跳出平面，具备厚度、阴影和动态扭曲 |

#### 后处理艺术

| 组件 | 描述 | 用途 |
|------|------|------|
| GlitchEffect | 故障艺术 | 瞬间的电磁干扰、画面撕裂感，表达大胆前卫 |
| DepthOfField | 景深虚化 | 电影级微距效果，焦点跟随交互，突出视觉重心 |
| ASCIIEffect | 字符滤镜 | 将3D画面实时转化为矩阵代码或字符，极客味十足 |
| MotionBlur | 运动模糊 | 增强高速运动的平滑度，让动效更显"贵" |
| ChromaticAberration | 色散 | 边缘色彩溢出，模拟老式相机或高级胶片感 |

### vaul-vue 组件（vue-web-mobile）

| 组件 | 描述 | 用途 |
|------|------|------|
| DrawerRoot | 抽屉根组件 | iOS 风格底部抽屉 |
| DrawerTrigger | 触发器 | 打开抽屉的触发元素 |
| DrawerContent | 内容 | 抽屉内容区域 |
| DrawerOverlay | 遮罩层 | 抽屉背景遮罩 |

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
| `@AI_INJECT_UI_COMPONENTS` | shadcn-vue 组件注入 | `src/components/ui/index.ts` |
| `@AI_INSPIRA_COMPONENTS` | Inspira UI 组件注入 | `src/components/inspira/index.ts` |
| `@AI_TRES_COMPONENTS` | TresJS 组件注入 | `src/components/tres/index.ts` |

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
- 使用 shadcn-vue 组件库（vue-web-basic、vue-web-admin），保持 UI 一致性。
- 使用 Inspira UI 组件实现炫酷视觉效果和动画。
- 使用 TresJS 实现 3D 场景和交互。
- 使用 motion-v 实现物理动画效果。
- 使用 Lenis 实现丝滑滚动体验。

## 目录结构

```
src/
├── components/        # 可复用组件
│   ├── ui/            # shadcn-vue 组件（仅 web-basic, web-admin）
│   ├── inspira/       # Inspira UI 组件
│   ├── tres/          # TresJS 3D 组件
│   └── drawer/        # vaul-vue 组件（仅 web-mobile）
├── data/              # 数据和配置
├── layouts/           # 布局组件
├── lib/               # 工具函数（cn, utils）
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
