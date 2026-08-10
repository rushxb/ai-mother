# 数据库 Schema 卫生审计

只读审计，未修改任何文件。行号对应审计时的 `dev` 分支。

## 覆盖范围与置信度

本次审计深度不均，结论强度也不同：

- **完整读取**：`sql/create_table.sql`、`sql/migrations/` 全部 36 个文件、4 个 mapper XML、
  全部 23 个 mapper 接口。DDL 与 SQL 层结论（第 2、3、4、5 节）基于完整证据。
- **仅顺链抽查**：service / application / controller 层。只追踪了与具体发现相关的调用链
  （积分预授权、工具审批、app 与 chat_history 权限、排序白名单）。
- **未覆盖**：Sa-Token 全局登录配置、测试代码、前端。这直接限制了第 1 条发现的可利用性判定。

因此「未发现问题」的表述在 SQL 层可信，在应用层只代表抽查范围内未发现。

---

## 按优先级排列的发现

### 1. `/app/get/vo` 无租户/所有者约束的读取 — CONFIRMED（暴露面待确认）

`/root/ai-mother/src/main/java/com/rush/rushaicodemother/controller/AppController.java:83-87`

```java
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(@RequestParam @Positive long id) {
```

该入口既无 `@AuthCheck`，也不调用 `AppAccessPolicy`，直接按 id 返回任意应用。对照同文件
`:129-134` 的管理端同名接口显式加了 `@AuthCheck(mustRole = ADMIN_ROLE)`，普通入口的缺失更像遗漏
而非设计。底层 `AppMapper.selectActiveById`
(`/root/ai-mother/src/main/java/com/rush/rushaicodemother/mapper/AppMapper.java:29-37`)
只有 `where id = #{appId} and isDelete = 0`，无 userId/tenantId 谓词。

泄露字段（`/root/ai-mother/src/main/java/com/rush/rushaicodemother/model/vo/AppVO.java:32,42,82`）：
`initPrompt`、`deployKey`、`userId`，以及嵌套的
`AppDatabaseResourceVO.databaseUrl`（`.../model/vo/AppDatabaseResourceVO.java:22`）。
`id` 为自增主键，可直接枚举遍历全站应用。

**未能确认**：是否可匿名访问。仓库内唯一鉴权组件是
`/root/ai-mother/src/main/java/com/rush/rushaicodemother/aop/AuthInterceptor.java:20`，
它是绑定 `@AuthCheck` 注解的切面，非全局拦截器；未找到注册全局登录校验的 `WebMvcConfigurer`。
是否需要登录取决于我未审阅的 Sa-Token 配置。即便要求登录，跨租户越权读取依然成立。

建议：若应用详情需公开（精选展示），从匿名视图剔除 `initPrompt`、`deployKey`、`databaseUrl`；
否则按 `ChatHistoryQueryApplicationService.listForApp:57-58` 的既有范式补 `AppAccessPolicy` 校验。

### 2. `uk_userAccount` 忽略 isDelete，注销账号永久不可再注册 — CONFIRMED

- 约束：`/root/ai-mother/sql/create_table.sql:28` — `UNIQUE KEY uk_userAccount (userAccount)`
  （生产同源：`/root/ai-mother/sql/migrations/B20260716_5__production_schema_baseline.sql:22`）
- 软删实现：`/root/ai-mother/src/main/java/com/rush/rushaicodemother/mapper/UserMapper.java:68-75`

```java
            UPDATE `user`
            SET isDelete = 1,
```

软删只置标记，行仍占用 `userAccount`。重新注册时
`UserMapper.selectActiveByAccount:30-37` 带 `AND isDelete = 0` 查不到该行，前置校验通过，
随后 `insertUser` 撞唯一键，在
`/root/ai-mother/src/main/java/com/rush/rushaicodemother/service/user/DefaultUserPersistenceService.java:128-130`
被捕获为「账号重复」。用户看到的是账号被他人占用，实际是自己注销的账号永久锁死。

同库已有正确范式：`ai_model` 用生成列把软删行摘出唯一键
（`/root/ai-mother/sql/create_table.sql:605-611`），`isDelete = 1` 时生成列为 `null`，不占唯一空间。
`user` 表未采用。

### 3. `generation_task` 缺 `creditCharged` 索引，结算扫描每 30 秒全表扫 — CONFIRMED

`/root/ai-mother/src/main/java/com/rush/rushaicodemother/mapper/UserCreditMapper.java:96-105`

```sql
            WHERE status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
              AND creditCharged = 0
            ORDER BY endTime ASC, id ASC
```

`creditCharged` 未出现在 `generation_task` 任何索引中（已核对
`/root/ai-mother/sql/create_table.sql:331-350` 全部 14 个索引）。可用的
`idx_status_createTime` 对终态几乎无选择性——绝大多数历史任务都是终态且已结算——且 `endTime`
不在索引内需额外 filesort。

调用方是定时任务：
`/root/ai-mother/src/main/java/com/rush/rushaicodemother/service/credit/GenerationCreditSettlementCoordinator.java:22-25`，
间隔 30 秒（`UserCreditProperties.SETTLEMENT_SCAN_INTERVAL = "30s"`，见
`/root/ai-mother/src/main/java/com/rush/rushaicodemother/config/UserCreditProperties.java:19`）。
代价随任务表增长而线性上升，且永不衰减。

建议索引：`(creditCharged, status, isDelete, endTime, id)`。

### 4. `app` 表缺复合索引，登录首屏 filesort — CONFIRMED

查询构造：
`/root/ai-mother/src/main/java/com/rush/rushaicodemother/service/app/DefaultAppPersistenceService.java:163-177`
（`eq userId` + `eq isDelete` + `orderBy createTime`），经
`AppQueryApplicationService.listMine:42-48` 强制覆盖 userId 为登录用户，入口
`AppController.java:90-97`。

`app` 表仅有单列 `INDEX idx_userId (userId)`（`/root/ai-mother/sql/create_table.sql:135`）。
现有 `idx_app_tenant_cursor (tenantId, isDelete, createTime, id)`（`:136`）前导列是 tenantId，
该查询不带 tenantId，用不上。结果是按 userId 取全部行后 filesort。

建议索引：`(userId, isDelete, createTime, id)`。

附带（SUSPECTED，低）：`listFeatured`（`AppQueryApplicationService.java:56-61`）按 `priority` 过滤，
`priority` 无索引；`user` 表管理端分页（`DefaultUserPersistenceService.java:185-197`）
按 `isDelete` 过滤 + `createTime` 排序亦无对应索引，但仅管理员可达。

### 5. `AiModelMapper` 的 `${}` 拼接 ORDER BY — SUSPECTED（当前安全，但脆弱）

`/root/ai-mother/src/main/java/com/rush/rushaicodemother/mapper/AiModelMapper.java:114`

```sql
            ORDER BY ${sortColumn} ${sortDirection}, id DESC
```

这是全仓库唯一的 `${}` SQL 插值（已 grep `src/main/java` 与 `src/main/resources`，其余
`${}` 全是 Spring 配置占位符）。当前不可利用：

- `sortColumn` 来自
  `/root/ai-mother/src/main/java/com/rush/rushaicodemother/service/aimodel/DefaultAiModelPersistenceService.java:86`
  的 `SORT_FIELDS.resolve(...)`，`SortFieldWhitelist.resolve`
  （`/root/ai-mother/src/main/java/com/rush/rushaicodemother/common/query/SortFieldWhitelist.java:41-46`）
  对未知字段返回默认列，不回显输入。
- `sortDirection` 是 `:87` 的三元表达式，只能取 `"ASC"` / `"DESC"` 两个字面量。
- 入口另有 `@AuthCheck(mustRole = ADMIN_ROLE)`。

风险在于防护完全依赖白名单被持续正确维护，而 `${}` 一旦白名单被绕过就是直接注入，且失败静默。
若要消除，可改为在 mapper 内用 `<choose>` 枚举有限排序分支。

同类已排除：`orchestration/create/recipe/BackendRepositoryTemplate.java` 拼接的是交付给用户项目的
Go 源码而非本平台查询，其 ORDER BY 亦经 `safeOrderByFunction:179-196` 白名单化，
表名经 `RecipeValueSupport` 的 `[^A-Za-z0-9_]` 清洗。

### 6. `create_table.sql` 与 migrations 漂移 — CONFIRMED（不影响生产）

`/root/ai-mother/sql/create_table.sql` 缺少：

- 整张 `dev_server_session` 表（仅存在于
  `/root/ai-mother/sql/migrations/V20260717_4__dev_server_session.sql`），
  而 `DevServerSessionMapper` 有 12 条 SQL 依赖它。
- `generation_task` 的 9 个 `publication*` 列及
  `idx_generation_task_publication_reconcile`（见
  `/root/ai-mother/sql/migrations/V20260720_1__generation_workspace_publication_journal.sql`），
  `GenerationWorkspacePublicationJournalMapper` 全部依赖这些列。

生产不受影响：`/root/ai-mother/sql/migrations/README.md:8` 明确「新数据库不执行
`sql/create_table.sql`」，由 Flyway 从 baseline 推进。但该文件仍是人读结构参考，
过期会误导后续索引与性能判断。本报告所有 DDL 结论均已逐条对标 migrations 复核。

---

## 分项结论

### 金额 / 积分列类型 — 无问题

全部为 `bigint`（整数分/积分语义），无 FLOAT/DOUBLE 用于金钱：

- `user.creditBalance` `bigint`（`create_table.sql:23`），
  附 `chk_user_credit_balance_nonnegative CHECK (creditBalance >= 0)`（`:30`）
- `user_credit_transaction.changeAmount` / `balanceAfter` `bigint`（`:83-84`），
  附 `balanceAfter >= 0` 与逐类型 shape 约束（`:95-107`）
- `generation_task.creditCost` / `totalTokens` `bigint`（`:325-326`）

全库唯一 `double` 是 `ai_model.temperature`（`:591`），模型采样参数，与金钱无关。

预授权逻辑（`/root/ai-mother/src/main/java/com/rush/rushaicodemother/service/impl/UserCreditServiceImpl.java:184-219`）
质量高：`@Transactional` + `selectActiveCreditAccountForUpdate` 行锁
（`UserCreditMapper.java:25-32`）+ 余额写绝对值 + DB 层非负约束兜底 +
`uk_type_bizId` 保证同一 taskId 预授权不重复。

### UNIQUE 约束 — 关键幂等键均已就位

- 提交幂等：`uk_generation_task_submission_idempotency (tenantId, userId, appId, idempotencyKeyHash)`
  （`create_table.sql:332-333`）。MySQL 允许 NULL 重复，故未带 Idempotency-Key 的提交互不冲突，
  与 `chk_generation_task_idempotency_pair`（`:357-360`）配合正确。
- `uk_deployKey`（`:133`）、`uk_taskId`（`:331`）、`uk_userAccount`（`:28`）
- 工具审批：`uk_task_approval (taskId, approvalId)` + `uk_task_tool_request (taskId, toolRequestId)`（`:467-468`）
- outbox 去重：`uk_semantic_memory_deletion_operation (operationId)` +
  `uk_semantic_memory_deletion_scope (operationType, tenantId, appId)`（`:382-383`）
- 积分流水去重：`uk_type_bizId (type, bizId)`（`:92`）

两处唯一键忽略 `isDelete` 但不构成缺陷，因为软删在这两条路径上从不发生：
`app_database_resource.uk_appId`（`:191`）——`isDelete` 只被写 0，且
`AppDatabaseResourceMapper.xml:63-64` 的 `ON DUPLICATE KEY UPDATE` 显式重置为 0；
`generation_task` 幂等键——应用删除走
`AppLifecycleDataMapper.java:34` 的硬删级联。

### 软删一致性 — 仅 1 处轻微不一致

19 张表带 `isDelete`。逐条核对全部 mapper SQL，查询侧未发现遗漏 `isDelete = 0` 的泄露。
唯一不一致：`/root/ai-mother/src/main/java/com/rush/rushaicodemother/mapper/DevServerSessionMapper.java:17`

```java
    @Select("SELECT id FROM user WHERE id = #{userId} FOR UPDATE")
```

未带 `isDelete = 0`，而对等的
`GenerationTaskRuntimeMapper.lockActiveUserForGenerationAdmission:46-52` 带了。
仅取锁不读业务字段，影响有限，但两处语义应统一。

`isDelete` 列存在却从未被置 1 的表（软删为死代码）：`generation_build_log`、
`generation_model_call`、`generation_task_span`、`app_database_resource`、`chat_history`。

### 租户 / 所有者作用域 — 设计上收敛在应用层

多数 mapper 按 id 查询不带 owner 谓词，这是刻意分层，补偿控制在应用层：
`AppAccessPolicy`（`/root/ai-mother/src/main/java/com/rush/rushaicodemother/application/app/AppAccessPolicy.java:26-50`）
经 `TenantAuthorizationService.requireRole` 做租户角色校验。

抽查的正确范例：

- `ChatHistoryQueryApplicationService.listForApp:57-58` — 先查 app 再 `requireOwnerOrAdmin`
- 工具审批双重校验 —
  `ToolApprovalService.requireDecidableTask:333-344` 比对 `actorId` 与 `task.userId()`，
  入口 `GenerationTaskController.java:172` 另有 `generationTaskQueryService.get(taskId, actor)`

唯一例外是第 1 条发现的 `/app/get/vo`。

### N+1 — 未发现真实 N+1

循环内的 DB 调用均属两类有意设计，不应合批：

1. **leased-outbox 逐行 claim**（逐行原子占用是租约语义的前提）：
   `GenerationMemoryOutboxService`、`SemanticMemoryDeletionOutboxProcessor`、
   `DevServerSessionRecoveryService`、`GenerationWorkspacePublicationReconciler`、
   `ToolApprovalExpirationCoordinator`
2. **资金结算独立事务**：`GenerationCreditSettlementCoordinator.java:24-27`
   每个 `chargeGenerationTask` 是独立事务 + 行锁，合批会破坏失败隔离与资金正确性。
   该处真正的问题是驱动查询缺索引（第 3 条），而非循环本身。

批量化已正确落地：`AppViewAssembler.toViewList:61-81` 用
`UserMapper.selectActiveByIds` 与 `AppDatabaseResourceMapper.selectActiveByAppIds` 批量装配；
`ChatHistoryMapper.copyActiveHistory:18-25` 用单条 `INSERT ... SELECT`。
未发现应使用批量方法却逐 id 查询的站点。

附带（SUSPECTED，低）：`app_capability`、`app_git_repository`、`app_runtime_channel`、
`app_analytics_config` 四表在 Java 侧只有 `AppLifecycleDataMapper.java:40-53` 的删除语句，
无任何读写。其上 8 个索引当前纯属写入开销。
