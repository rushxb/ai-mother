package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.BaseMapper;
import com.rush.rushaicodemother.mapper.GenerationToolApprovalMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalPersistenceBoundaryArchitectureTest {

    @Test
    void approvalDomainMustUseDurablePortInsteadOfRedisOrMapper() throws Exception {
        Path root = Path.of("src", "main", "java", "com", "rush", "rushaicodemother",
                "orchestration", "tool");
        try (var files = Files.walk(root)) {
            String source = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertFalse(source.contains("StringRedisTemplate"));
            assertFalse(source.contains("com.rush.rushaicodemother.mapper"));
            assertTrue(source.contains("ToolApprovalRepository"));
        }
    }

    @Test
    void approvalMapperMustUseExplicitAtomicTransitions() throws Exception {
        assertFalse(BaseMapper.class.isAssignableFrom(GenerationToolApprovalMapper.class));
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationToolApprovalMapper.java"));
        assertTrue(source.contains("status = 'pending'"));
        assertTrue(source.contains("status = 'approved'"));
        assertTrue(source.contains("status = 'rejected'"));
        assertTrue(source.contains("status = 'executing'"));
        assertTrue(source.contains("status = 'consumed'"));
        assertTrue(source.contains("status = 'expired'"));
        assertTrue(source.contains("AND status = 'pending'"));
        assertTrue(source.contains("AND status = 'approved'"));
        assertTrue(source.contains("expiresAt > #{decidedAt}"));
        assertTrue(source.contains("expiresAt > #{executionStartedAt}"));
        assertTrue(source.contains("executionResult = #{executionResult}"));
        assertTrue(source.contains("toolRequestId IS NULL"));
        assertTrue(source.contains("argumentsDigest = #{argumentsDigest}"));
        assertTrue(source.contains("requestExecutionEpoch = #{requestExecutionEpoch}"));
        assertTrue(source.contains("task.status = 'waiting_approval'"));
        assertTrue(source.contains("approval.status IN ('approved', 'rejected', 'consumed', 'expired')"));
        assertTrue(source.contains("LIMIT #{limit}"));
    }

    @Test
    void approvalIdentityMustBindTaskEpochToolAndArgumentsDigest() throws Exception {
        String mapper = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "mapper", "GenerationToolApprovalMapper.java"));
        String migration = Files.readString(Path.of(
                "sql", "migrations", "V20260828_1__tool_approval_request_epoch.sql"));
        String record = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "orchestration", "tool", "ToolApprovalRecord.java"));
        String invocation = Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother",
                "orchestration", "tool", "GenerationToolExecutionContextService.java"));

        assertTrue(record.contains("long requestExecutionEpoch"));
        assertTrue(invocation.contains("long requestExecutionEpoch"));
        assertTrue(mapper.contains("AND requestExecutionEpoch = #{requestExecutionEpoch}"));
        assertTrue(mapper.contains("approval.requestExecutionEpoch = task.executionEpoch"));
        assertTrue(mapper.contains("newer.requestExecutionEpoch > approval.requestExecutionEpoch"));
        assertTrue(migration.contains("requestExecutionEpoch bigint default 0 not null"));
        assertTrue(migration.contains("task.status = 'waiting_approval'"));
        assertTrue(migration.contains(
                "uk_task_epoch_approval (taskId, requestExecutionEpoch, approvalId)"));
        assertTrue(migration.contains(
                "uk_task_epoch_tool_request (taskId, requestExecutionEpoch, toolRequestId)"));
    }

    @Test
    void approvalSchemaMustEnforceExpiryAndStateShape() throws Exception {
        String schema = Files.readString(Path.of("sql", "create_table.sql"));
        String migration = Files.readString(Path.of(
                "sql", "migrations", "V20260716_6__generation_tool_approval.sql"));
        for (String contract : java.util.List.of(
                "expiresAt > requestedAt",
                "status = 'pending' AND decidedBy IS NULL",
                "status = 'approved' AND decidedBy IS NOT NULL",
                "status = 'executing' AND decidedBy IS NOT NULL",
                "status = 'consumed' AND decidedBy IS NOT NULL",
                "status = 'expired' AND consumedAt IS NULL",
                "uk_task_tool_request (taskId, toolRequestId)",
                "toolRequestId IS NULL AND toolName IS NULL",
                "argumentsDigest IS NOT NULL AND checkpointJson IS NOT NULL",
                "executionStartedAt IS NOT NULL AND executionResult IS NULL",
                "executionStartedAt IS NOT NULL AND executionResult IS NOT NULL"
        )) {
            assertTrue(schema.contains(contract));
            assertTrue(migration.contains(contract));
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
