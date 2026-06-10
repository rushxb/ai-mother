---
name: Database Boundary
description: Keep database and backend access isolated behind API or service layers.
keywords: database,数据库,sqlite,后端,backend,接口,api,service,数据服务
modules: database,api
contextFileHints: backend,src/api,src/services,src/views,src/pages
implementationHints: 后端放在独立 backend 目录; 前端通过 HTTP API 访问; 先打通最小可运行链路
validationHints: 验证后端启动入口存在; 验证前端不直接写数据库逻辑; 验证危险 SQL 不外泄
databaseRequired: true
---
- 前端页面不要直接持有数据库逻辑。
- 先把服务边界和 API 适配层定清楚。
- SQL、连接和初始化流程要在服务侧闭合。
