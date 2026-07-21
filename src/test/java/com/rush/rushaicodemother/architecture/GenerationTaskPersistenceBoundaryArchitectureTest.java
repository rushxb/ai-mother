package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable generation-task runtime 的 port/adapter、并发 SQL 与数据库契约架构门禁。 */
class GenerationTaskPersistenceBoundaryArchitectureTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path JAVA_ROOT = PROJECT_ROOT.resolve(Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    ));
    private static final Path RUNTIME_TASK_ROOT = JAVA_ROOT.resolve(Path.of(
            "orchestration", "runtime", "task"
    ));

    @Test
    void runtimeTaskModuleMustDependOnPersistencePortInsteadOfAdapters() throws IOException {
        String runtimeSources;
        try (Stream<Path> paths = Files.walk(RUNTIME_TASK_ROOT)) {
            runtimeSources = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .reduce("", (left, right) -> left + "\n" + right);
        }

        assertFalse(runtimeSources.contains("com.rush.rushaicodemother.mapper"));
        assertFalse(runtimeSources.contains("com.rush.rushaicodemother.infrastructure.persistence"));
        assertFalse(runtimeSources.contains("com.mybatisflex"));
    }

    @Test
    void runtimeMapperMustUseExplicitOwnerScopedAndVersionedSql() throws IOException {
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationTaskRuntimeMapper.class));
        String mapperSource = readJava("mapper", "GenerationTaskRuntimeMapper.java");

        assertTrue(mapperSource.contains("leaseOwner = #{leaseOwner}"));
        assertTrue(mapperSource.contains("leaseUntil >= #{now}"));
        assertTrue(mapperSource.contains("version = #{expectedVersion}"));
        assertTrue(mapperSource.contains("status = #{terminalStatus}"));
        assertTrue(mapperSource.contains("status IN ('queued', 'running')"));
        assertTrue(mapperSource.contains("status = 'waiting_approval'"));
        assertTrue(mapperSource.contains("leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL"));
        assertTrue(mapperSource.contains("AND (deadlineAt IS NULL OR deadlineAt > #{now})"));
        assertTrue(mapperSource.contains("status IN ('queued', 'running', 'waiting_approval')"));
        assertTrue(mapperSource.contains("restoreQueuedTaskToWaitingApproval"));
        assertTrue(mapperSource.contains("AND status = 'queued'"));
        assertFalse(mapperSource.contains("QueryWrapper"));
        assertFalse(mapperSource.contains("BaseMapper<"));
    }

    @Test
    void schemaAndMigrationMustKeepDurableRuntimeColumnsAndRecoveryIndexes() throws IOException {
        String schema = Files.readString(PROJECT_ROOT.resolve(Path.of("sql", "create_table.sql")));
        String migration = Files.readString(PROJECT_ROOT.resolve(Path.of(
                "sql", "migrations", "V20260716__generation_task_runtime_lease.sql"
        )));

        for (String column : List.of(
                "route", "submittedAt", "deadlineAt", "cancellationRequested",
                "cancellationReason", "leaseOwner", "leaseUntil", "heartbeatAt",
                "attempt", "version"
        )) {
            assertTrue(schema.contains(column), "基线缺少 generation_task 字段: " + column);
            assertTrue(migration.contains(column), "迁移缺少 generation_task 字段: " + column);
        }
        assertTrue(schema.contains("idx_runtime_lease (status, leaseUntil, isDelete)"));
        assertTrue(schema.contains("idx_app_runtime_status (appId, status, submittedAt)"));
        assertTrue(migration.contains("idx_runtime_lease (status, leaseUntil, isDelete)"));
        assertTrue(migration.contains("idx_app_runtime_status (appId, status, submittedAt)"));
        assertTrue(migration.contains("不声明支持 checkpoint 断点续跑"));
    }

    @Test
    void recoveryMustRemainHonestUntilVersionedCheckpointsExist() throws IOException {
        String recoveryServiceSource = readJava(
                "orchestration", "runtime", "task", "GenerationTaskRecoveryService.java"
        );
        String recoveryPolicySource = readJava(
                "orchestration", "runtime", "task", "GenerationTaskRecoveryPolicy.java"
        );

        assertTrue(recoveryPolicySource.contains("worker_lease_expired_non_recoverable"));
        assertTrue(recoveryPolicySource.contains("GenerationTaskStatus.CANCELLED"));
        assertTrue(recoveryPolicySource.contains("GenerationTaskStatus.DEADLINE_EXCEEDED"));
        assertTrue(recoveryServiceSource.contains("no versioned checkpoint exists"));
        assertFalse(recoveryServiceSource.contains("resumeExpiredTask"));
    }

    private String readJava(String... relativePath) throws IOException {
        return Files.readString(JAVA_ROOT.resolve(Path.of("", relativePath)));
    }

    private String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取架构门禁源码: " + path, exception);
        }
    }
}
