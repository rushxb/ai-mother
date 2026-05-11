---
name: Vue Admin Dashboard
description: Keep admin-style Vue generation consistent across layout, routes, menus, and page wiring.
keywords: 后台,管理系统,dashboard,仪表盘,路由,侧边栏,布局,菜单,admin
modules: dashboard,navigation,management
contextFileHints: src/layouts,src/router,src/components,src/views,src/pages,src/api,src/stores
implementationHints: 复用现有布局骨架; 页面、路由、菜单和接口适配要同步修改; 优先保证最小可运行改动
validationHints: 验证菜单跳转; 验证路由注册; 验证空状态和加载态
---
- 不要重建无关工程入口。
- 页面、路由和菜单要一起改。
- 把列表、表单和详情拆成独立组件。
