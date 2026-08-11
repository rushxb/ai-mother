# 暂定预览 Dev Server 所有权上提 —— 设计评审页

**状态**：待评审，尚未写代码
**关联**：`docs/AI生成链路优化蓝图.md` §5.5 / §6（P0-2 后续项）
**目标**：让暂定预览从「指标上成立」变成「用户真的点得开」

---

## 1. 问题陈述

暂定预览已经能在验证窗口内发出 `first_preview_ready`（第二批落地），指标也已按等级分序列
（第六批 S1/S3）。但**用户仍然点不开**：

`DevServerValidationService.validate` 的 `finally` 块无条件调用 `stopOwnedSession`，
而错误采集窗口 `VALIDATION_ERROR_COLLECTION_WINDOW = 5s`。就绪回调在窗口开始前触发，
所以从「用户收到可预览通知」到「dev server 被停掉」只有约 5 秒。

```java
// DevServerValidationService.validate，简化
try {
    startResult = devServerManager.startDevServer(app, userId, startOptions);
    notifyDevServerReady(taskId, readyCallback);   // ← 暂定预览事件在这里发出
    awaitErrorCollectionWindow(taskId, collectionWindow, collector);  // 约 5s
    ...
} finally {
    stopOwnedSession(appId, startResult);          // ← 无条件停掉
}
```

所有权边界是**验证方法的作用域**。要让预览可用，必须把它上提。

---

## 2. 调查中推翻的两个前提

写代码前先纠正蓝图 §6 里两处不准确的表述，它们会把设计带向错误方向。

### 2.1 「上提到任务作用域」是错的 —— 上界是发布点

`GenerationWorkspacePublicationService:270` 在发布时执行
`pathMover.move(source, destination)`，把**执行工作区整体移到版本目录**。

暂定预览的 dev server 以执行工作区为 root（`DevServerProjectLocator.locate` 在
`startOptions.executionFence() != null` 时走 `resolveExecution`）。Linux 上 Vite 进程会继续
持有旧 inode：目录被移走后预览**既不报错也不更新**，用户看到一份无声的过期内容。

所以不存在「一个 dev server 从暂定预览活到任务结束」这种形态。真实的所有权边界是：

```
启动（验证开始）─────── 暂定预览可用 ─────── 发布点（必须停）
                                              │
                                              └─→ 若要继续预览，以「已发布工作区」为 root 重启
```

跨发布点不是延长同一会话，而是**换 root 重启**。这一点必须写进设计，否则会实现出一个
在发布后静默失效的预览。

### 2.2 「`requireRunningRoute` 不校验 fence」—— 需要更精确的说法

`requireRunningRoute` 确实不校验 `GenerationExecutionFence`，但它并非全无防护：

- `hasCurrentLease` 校验 `state().isActive()` 且 `leaseUntil` 未过期；
- 本地分支还校验 `identityProvider.ownerId().equals(record.leaseOwner())`、端口有效且与记录一致。

真正缺的是**执行纪元**维度：租约所有者可以不变（同一 worker 连续跑两个纪元），
而工作区已经被上一个纪元发布走了。第五批的 `workspace_directory_missing` 回收能兜住这个情形，
但它是**轮询兜底**（心跳 10s），存在窗口。fence 校验是把它变成**即时拒绝**。

结论：fence 校验仍然要做，但它是「收紧」而非「从零开始」，且第五批的回收是它的兜底而非替代。

---

## 3. 设计

### 3.1 引入预览会话的持有者概念

现状是二值的：`startedByCaller` 为 true 就由调用者在 `finally` 停。改为显式声明**持有意图**。

```java
/** Dev Server 会话的持有者语义：决定谁、在什么时点停止会话。 */
public enum DevServerSessionOwnership {

    /** 调用方作用域持有：方法返回即停（现状语义，BUILD 路径与后台校验沿用）。 */
    CALLER_SCOPED,

    /** 生成任务持有：验证返回后不停，由发布收口或任务终态显式停止。 */
    TASK_SCOPED
}
```

`DevServerStartOptions` 增加该字段（默认 `CALLER_SCOPED`，保持既有调用点语义不变），
`DevServerValidationService` 据此决定 `finally` 里是否调用 `stopOwnedSession`。

**为什么用枚举而不是 boolean**：后续可能出现「用户手动预览持有」（当前由
`AppDevServerApplicationService` 走另一条路径）等第三种语义。枚举让新增持有者不必改签名。

### 3.2 停止责任的唯一收口点

`TASK_SCOPED` 的会话必须有且只有一处收口，否则会重现「漏掉 stop 导致进程泄漏」。
候选落点只有一个合适：**`GenerationWorkspaceReleaseService.releaseVerified`**，
它已经是「发布 + 首预览里程碑」的统一收口，且在 `publicationService.publishWithMetadata`
之前有天然的插入点。

**它确实是唯一收口，已核实**：全仓只有两处调用 —— `HeavyGenerationCoordinator:680`（EXPERT）
与 `GenerationPipelineExecutor:219`（同步流水线），且后者仅在 `status == SUCCESS`
且通过 `requireCompletable` 之后调用。没有第三条发布路径绕过它。

```
releaseVerified(session, targetType):
    ├─ 前置校验（现状）
    ├─ [新增] stopTaskScopedPreview(session)   ← 发布前必须停，因为发布会 move 目录
    ├─ publicationService.publishWithMetadata(...)
    └─ publishFirstPreviewSafely(...)          ← 已验证预览事件
```

失败路径的收口由**任务终态**负责。这里不新增第二条清理链路，而是复用第五批已落地的
`reclaimIfUnusable`：任务失败时执行工作区被丢弃 → 目录消失 → 下一次心跳回收。
代价是最长约 10s（`HEARTBEAT_INTERVAL`）的滞留，可接受 —— 它本来就是兜底路径。

**这是一个有意的取舍**：不为失败路径新增显式停止调用。理由是每一处显式 stop 都是一个
可能被漏掉的分支，而第五批的回收已经覆盖了「目录消失」这个失败路径的必然结果。

### 3.3 `requireRunningRoute` 的纪元校验

`DevServerSessionRecord` 已有 `version` 字段（乐观锁版本，非执行纪元），但注册表接口
没有暴露「按 fence 校验」的能力。方案：在 `claimStarting` / `markRunning` 时把执行纪元
写入会话记录，路由校验时比对。

需要评审的点：`version` 在 `V20260717_4__dev_server_session.sql` 里的注释就是
`optimistic fencing version`，**不能复用**为执行纪元 —— 它每次更新都自增，与纪元无关。
强校验需要新增字段 `execution_epoch`（可空，非生成任务启动的会话为 null，此时跳过校验）。

**成本比我预期的高**：`claimStarting` 当前签名是
`(appId, userId, projectDirectory, port)`，一路到 `DevServerSessionRegistration`
都不携带 fence。要把纪元落库，需要贯穿四层：
`DevServerStartOptions` → `DevServerManager.registerStartingSession`
→ `DevServerSessionLeaseCoordinator.claimStarting`（接口 + 实现 + noOp）
→ `DevServerSessionRegistration` → MyBatis 映射 + 迁移。

退路（**推荐**）：路由时复用 `unusableReason` 的同一判据 —— 校验
`record.projectDirectory()` 是否仍是存在的目录。它捕捉的正是「工作区已被发布移走」这个
唯一真正危险的情形，不需要迁移，也不需要改五个签名。

「同一路径被新纪元复用」这个理论弱点**已核实不成立**：
`GenerationExecutionWorkspaceService:123-126` 把执行工作区物化到
`.generation-executions/<appId>/<taskId>/epoch-<executionEpoch>/<codeGenType>/workspace`，
路径本身含纪元段，新纪元必然是新路径。因此目录存在性校验在实际语义上已经等价于纪元校验 ——
旧纪元的目录一旦被发布移走就不复存在，路由立即拒绝。

**这使强校验方案的额外收益接近于零**，除非未来出现「路径不含纪元」的工作区布局。
建议选退路，并在 `DevServerPreviewRoutingService` 注释里写明它依赖上述路径约定。

---

## 4. 影响面

| 文件 | 改动性质 |
|---|---|
| `DevServerSessionOwnership`（新增） | 枚举 |
| `DevServerStartOptions` | 加字段，默认值保持既有语义 |
| `DevServerValidationService` | `finally` 条件化 |
| `GenerationWorkspaceReleaseService` | 发布前插入停止 |
| `DevServerPreviewRoutingService` | 加目录存在性校验（推荐方案，约 10 行） |
| `DevServerSessionRegistry` + 实现 + 迁移 | **不改**（仅在评审改选强校验时才需要） |

既有调用点（`BackgroundValidationService`、`BrowserGenerationRuntimeEvaluator`、
`DevServerLogTool`、`AppDevServerApplicationService`）均不受影响 —— 默认值即现状语义。

---

## 5. 待评审决策

1. **§3.3 选型**：目录存在性即时校验（推荐，零迁移，已核实语义上等价于纪元校验）
   还是新增 `execution_epoch` 字段（需贯穿四层 + 一次迁移，额外收益接近于零）？
2. **§3.2 的取舍**：失败路径依赖心跳回收（最长约 10s 滞留）是否接受，
   还是要求显式停止？
3. **发布后是否自动以已发布工作区重启预览**？本设计默认**不自动重启**
   （用户点预览时按现有 canonical 路径正常启动即可），避免为一个可能没人看的页面占用进程配额。

---

## 6. 本设计不解决的

- 进程树泄漏（子进程 / esbuild 孙进程 / `SO_REUSEADDR` 残留端口）—— 蓝图 §6 遗留项。
- 多实例部署下暂定预览的跨节点路由 —— `DevServerPreviewRoutingService` 已有远端分支，
  但暂定预览期的工作区只存在于执行节点，跨节点无法代理。单实例部署下不受影响。
- 5 秒窗口之外的体验问题（预览页首屏白屏、HMR 首次连接延迟）未测量。
