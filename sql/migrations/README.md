# 数据库迁移规范

本目录是生产数据库结构的唯一版本化迁移来源。Maven 构建会将 `B*.sql`、`V*.sql`
复制到 `classpath:db/migration`，由 Flyway 执行并校验 checksum。

## 新数据库

新数据库不执行 `sql/create_table.sql`。Flyway 会选择最新的 `B` baseline migration，
再顺序执行版本号更高的 `V` migration。

当前 baseline：

```text
B20260716_5__production_schema_baseline.sql
```

## 已有数据库接入 Flyway

禁止在未核对数据库结构时直接启用 `baseline-on-migrate`。

1. 备份数据库并在预发布副本验证。
2. 对照目标环境确认已执行到哪个 migration 版本。
3. 设置 `FLYWAY_ENABLED=true`。
4. 首次接管时显式设置：

   ```text
   FLYWAY_BASELINE_ON_MIGRATE=true
   FLYWAY_BASELINE_VERSION=<目标库已经具备的最高版本>
   ```

5. 首次成功产生 `flyway_schema_history` 后，恢复：

   ```text
   FLYWAY_BASELINE_ON_MIGRATE=false
   ```

默认建议从 `20260716.5` 接管，使 Flyway 继续执行
`V20260716_6__generation_tool_approval.sql`。如果目标库状态不同，必须使用经过审计的实际版本，
不得为了启动成功伪造较高 baseline。

## 发布规则

1. 已在任一环境执行的 migration 禁止修改；修复必须新增更高版本文件。
2. migration 失败必须阻断发布，不允许删除历史记录或修改 checksum 绕过。
3. 应用生产账号原则上仅保留 DML 权限；迁移使用独立 DDL 账号执行。
4. 生产禁止 Flyway clean，项目已设置 `clean-disabled=true`。
5. 集成环境执行：

   ```powershell
   .\mvnw.cmd -Pintegration-test `
     -Dintegration.mysql.admin-url="jdbc:mysql://localhost:33306/?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC" `
     -Dintegration.mysql.username=root `
     -Dintegration.mysql.password=test `
     -Dintegration.redis.host=localhost `
     -Dintegration.redis.port=36379 test
   ```

6. 发布门禁至少验证：baseline 建库、全部 versioned migration、checksum validate、关键约束和索引。

`V20260718_7__generation_task_submission_idempotency.sql` adds hashed `Idempotency-Key`
storage, request fingerprints, and the tenant/user/app-scoped uniqueness contract used by
generation admission. Raw idempotency keys must never be stored or logged.

`V20260721_1__semantic_memory_production_contract.sql` adds leased/retryable claiming for
generation-task memory indexing and creates `semantic_memory_deletion_outbox`. Application
deletion must enqueue the Milvus delete operation in the same relational transaction that removes
the application rows. The worker then performs the tenant/app-scoped delete idempotently; operators
must not manually mark an outbox row complete without independently verifying the Milvus delete.

`V20260721_2__semantic_memory_v2_reindex_contract.sql` adds the non-negative
`memoryIndexContractVersion` marker and its claim index. Existing rows receive version `0`, so a
task previously indexed into the v1 collection is safely reclaimed for the v2 schema/embedding
contract even when `memoryIndexedAt` is already populated. The claim transition atomically switches
the row to the current contract, clears the legacy success timestamp and starts a fresh retry budget;
do not replace this mechanism with a bulk `UPDATE ... SET memoryIndexedAt = NULL` on production
tables. Any incompatible row schema, collection, embedding model or embedding version change must
increment `SemanticMemoryContract.INDEX_VERSION` in the same release.

`V20260721_3__ai_release_coordination_lock.sql` 新增全局 AI 发布事务协调行。
Prompt 发布、模型启停或删除、模型密钥迁移在读取或改变发布身份前必须先锁定该行，
并将证据重放与状态变更放在同一数据库事务内。禁止改成进程内锁，也禁止在无事务或只读事务中调用。

`V20260723_1__generation_benchmark_candidate_invocation_attestation.sql` 新增 Benchmark
证据签名协议版本和候选模型物理请求计数。历史证据标记为 `v1/0` 以保留审计可读性；
新发布只接受 `v2`。模型启用候选必须记录至少一次评测窗口内的真实物理请求，Prompt 候选必须为 `0`。
