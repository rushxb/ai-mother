# Go SQLite Backend Template

Go 1.23 + net/http + SQLite 企业级后端模板，面向 AI 生成场景优化。

## 技术栈

| 组件 | 选型 |
|------|------|
| Go | 1.23 |
| HTTP | net/http (标准库) |
| 数据库 | SQLite (modernc.org/sqlite) |
| 认证 | Token + sessions 表 |
| 参数校验 | go-playground/validator |
| 日志 | log/slog (JSON) |
| 密码 | golang.org/x/crypto/bcrypt |

## 运行

```bash
go mod tidy
go run ./cmd/server
```

默认端口：`:18000`
默认数据库：`data/app.db`

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| SERVER_ADDR | :18000 | 监听地址 |
| DATABASE_DSN | data/app.db | 数据库路径 |
| LOG_LEVEL | info | 日志级别 (debug/info/warn/error) |

## 目录结构

```
cmd/server/          # 程序入口
internal/
├── config/          # 配置加载
├── database/        # 数据库初始化
├── middleware/       # HTTP 中间件
│   ├── cors.go      # CORS
│   ├── logger.go    # 请求日志
│   ├── recovery.go  # Panic 恢复
│   ├── security.go  # 安全头
│   ├── ratelimit.go # 限流
│   └── middleware.go # 中间件链
├── domain/          # 跨模块共享契约
│   └── model.go     # 分页与共享 DTO
├── modules/         # 业务模块目录
│   └── sample/      # 可被 AI 替换的示例模块
│       ├── model.go
│       ├── repository.go
│       ├── service.go
│       ├── handler.go
│       └── handler_test.go
├── response/        # 统一响应
└── validator/       # 参数校验
sql/
└── schema.sql       # 数据库迁移
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/health | 健康检查 |
| POST | /api/user/register | 用户注册 |
| POST | /api/user/login | 用户登录 |
| POST | /api/user/logout | 退出登录 |
| GET | /api/user/current | 获取当前用户 |
| POST | /api/user/list/page | 用户列表（分页） |

## 中间件链

```
请求 → Recovery → Logger → Security → RateLimit → CORS → Handler
```

- **Recovery**: panic 恢复，返回 500
- **Logger**: 请求日志（方法、路径、状态码、耗时）
- **Security**: 安全响应头
- **RateLimit**: 基于 IP 的限流（100 次/分钟）
- **CORS**: 跨域支持

## @AI_INJECT 标记

| 标记 | 用途 | 位置 |
|------|------|------|
| `@AI_INJECT_MODULE_WIRING` | 模块依赖装配注入 | `cmd/server/main.go` |
| `@AI_INJECT_ROUTE: sample` | 模块路由注入 | `internal/modules/sample/handler.go` |

## AI 改造约定

- 共享字段契约放在 `internal/domain`
- 新增模块放在 `internal/modules/{name}` 下独立目录
- 每个模块包含 model、repository、service、handler
- 模板负责目录结构，业务细节由 slot 或 recipe 填充
- 使用 validator 标签进行参数校验
- 使用 slog 记录结构化日志
- 使用 response 包返回统一响应
- 新增路由优先通过锚点 patch 注入

## 测试

```bash
go test ./...
```
