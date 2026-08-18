# ADR：生成工作区信任模型

## 状态

已接受。

## 决策

模型生成的文件、依赖声明和执行命令一律视为不可信输入。信任判断不由 Prompt、Agent 角色或业务 pipeline 自行实现，而是集中在写入 policy、进程环境 policy 与 sandbox adapter 三个 seam：

- `GeneratedWorkspaceTrustPolicy` 负责生成文件落盘前的内容判定；
- `NodeProcessEnvironment` 负责安装、构建与运行进程的最小环境；
- `GeneratedCodeProcessSandbox` 负责将同一执行请求映射到开发用主机 adapter 或生产容器 adapter。

业务 pipeline 只能调用这些 interface，不能通过新增写文件方法、直接启动进程或复制一份黑名单绕过信任模型。

## 写入不变量

所有 patch、Agent tool 和自动修复写入统一进入 `PatchOperationValidator`，并由 `GeneratedWorkspaceTrustPolicy` fail-closed 校验。策略拒绝：

- `.pnpmfile.mjs/.cjs`、项目级 `.npmrc`、`pnpm-workspace.yaml` 等包管理器控制文件；
- npm、pnpm、yarn 与 bun lockfile 的生成、替换或删除；
- `package.json` 的安装期生命周期脚本；
- URL、Git、本地文件、workspace、catalog 与 patch 等非受信依赖来源；
- 非 pnpm 或未固定完整版本的 `packageManager`。

拒绝结果使用稳定的机器可读原因，校验异常不得降级为放行。新增写入入口必须复用同一 policy，并由架构测试证明不存在旁路。

## 安装与执行不变量

依赖安装必须同时携带 `--ignore-scripts`、`--ignore-pnpmfile`，registry、代理、证书与网络由可信宿主配置注入，生成项目无权覆盖。安装、构建和运行进程只继承允许列表中的基础环境，平台模型、数据库、对象存储与租户密钥默认全部移除。

生产 profile 只允许 container sandbox，并在 runtime、镜像、内部网络、只读根文件系统或资源限制缺失时拒绝启动。`host-local` adapter 不提供安全隔离，只允许开发环境显式选择，不能作为生产故障回退。

网络按阶段隔离：依赖安装只访问受信依赖源，构建默认无外网，运行只进入内部预览网络。预览流量经独立 gateway 暴露，生成容器不能加入宿主网络或其他容器的网络命名空间。

## 验证口径

- 生产与 Benchmark 对同一 artifact 复用相同的写入 policy 和 verifier implementation；
- 控制文件、生命周期脚本和危险依赖协议必须保留负向测试；
- 容器集成测试验证只读根、资源限制、网络隔离、最小环境与清理语义；
- 任何新工程类型 adapter 在声明可安装、构建或运行前，必须证明其命令仍经过上述 seam。

## 已知边界

信任 policy 只能约束平台允许执行的生成内容，不能证明第三方依赖本身无供应链风险。依赖源仍需由宿主侧 allowlist、版本治理、镜像与制品扫描继续收敛；这些能力应扩展现有 sandbox 和依赖治理 module，不能回流到各业务 pipeline。
