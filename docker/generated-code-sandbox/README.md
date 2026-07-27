# 生成代码沙箱镜像

生产启用 `GENERATED_CODE_SANDBOX_MODE=container` 前，必须从仓库根目录构建沙箱镜像。Go 基础镜像由 CI/发布配置通过 `GENERATED_CODE_SANDBOX_GO_BASE_IMAGE` 提供，且必须固定到真实的 `sha256` 摘要；仓库不保存未经验证或虚构的摘要。

```powershell
$env:GENERATED_CODE_SANDBOX_GO_BASE_IMAGE = "golang:<固定版本>-bookworm@sha256:<发布系统提供的64位摘要>"
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-generated-code-sandbox.ps1
```

仅构建镜像时使用下面的等价命令。末尾的 `.` 是仓库根目录构建上下文，不能改回 `docker/generated-code-sandbox`，因为构建阶段需要读取后端 Go 模板。

```powershell
docker build --pull `
  --build-arg "GO_BASE_IMAGE=$env:GENERATED_CODE_SANDBOX_GO_BASE_IMAGE" `
  --file docker/generated-code-sandbox/Dockerfile `
  --tag ai-code-mother/sandbox-node:1 `
  .
```

构建阶段会对 `src/main/resources/project-templates/go-sqlite-backend-basic` 执行 `go mod download`、`go mod verify` 和 `go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...`。Go 模板的 `go.mod` 或 `go.sum` 变化后必须重新构建并发布沙箱镜像，禁止让在线生成任务临时联网补依赖。运行阶段从镜像内只读的 `/opt/go/pkg/mod` 读取依赖，不使用跨租户共享的可写模块缓存。

Go 门禁运行时固定关闭网络，并设置 `GOENV=off`、`GOFLAGS=`、`GOPROXY=off`、`GOSUMDB=off`、`GOTOOLCHAIN=local`、`GOTELEMETRY=off`、`GOWORK=off` 和 `CGO_ENABLED=0`。普通命令继续使用带 `noexec` 的 `/tmp`；只有受控且禁用网络的 `go build`、`go install`、`go run`、`go test` 会获得 `/tmp/go-build` 可执行 tmpfs，用于 Go 构建缓存和临时二进制。该 tmpfs 受 `GENERATED_CODE_SANDBOX_GO_BUILD_TMPFS_SIZE` 限制，默认 `512m`。

应用启动时会验证镜像中的 Node.js、pnpm 和 Go 工具链；启用 Backend grading 的专用 Worker 还会通过当前沙箱离线执行内置 Go+SQLite 模板的完整测试，验证 SDK、模块缓存、挂载和清理链路。每个任务只挂载自己的生成工作区到 `/workspace`，容器使用非 root 用户、只读根文件系统、移除 Linux capabilities、启用 `no-new-privileges`，并限制内存、CPU 和 PID。除受控依赖安装外，生成代码不获得网络访问。

Dev Server 只连接内部 Docker 网络。独立、由平台控制且只读的预览网关同时连接内部网络和专用预览网络，并仅向宿主 `127.0.0.1` 发布分配端口。生成代码容器不会加入预览网关网络或依赖出口网络。

生产环境必须使用摘要固定的最终沙箱镜像，例如 `registry.example.com/ai-code/sandbox@sha256:...`，并使用受防火墙或代理策略约束的专用依赖出口网络，禁止使用 Docker 默认 `bridge`。数据库、Redis、Docker socket 和控制面服务不得连接任何沙箱网络。工作区必须允许配置的容器 UID/GID 写入，默认是 `1000:1000`。

生产还需要预先创建节点本地 pnpm store volume。只有命令严格匹配受控 `pnpm install` 且获得依赖出口能力时，该卷才以可写方式挂载；构建、测试、工具和 Dev Server 容器均不可见。缓存包复制到项目目录而不是硬链接，并保持 pnpm store 完整性校验。该卷只能作为可丢弃缓存使用，名称应绑定沙箱镜像和 pnpm 主版本，并配置节点磁盘水位告警；清理或轮换只能在没有活动安装任务的维护窗口执行。

## 运行时安全验证

后端或沙箱镜像发布前必须执行真实 Docker 集成契约。两个脚本都要求 `GENERATED_CODE_SANDBOX_GO_BASE_IMAGE` 已配置为带摘要的镜像引用。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-generated-code-sandbox.ps1
```

```bash
./scripts/verify-generated-code-sandbox.sh
```

该契约检查真实容器状态，而不只检查生成的 Docker 命令，包括：非 root UID、只读根目录、capability 移除、`no-new-privileges`、宿主密钥不继承、离线任务无默认路由、cgroup 资源上限、按能力开放挂载、Go 离线测试、Go 后端编译运行、受控监听地址改写、仅回环地址发布预览、Dev Server 内网隔离，以及相关容器清理。
