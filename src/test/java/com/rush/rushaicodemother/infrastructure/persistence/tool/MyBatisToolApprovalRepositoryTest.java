package com.rush.rushaicodemother.infrastructure.persistence.tool;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationToolApprovalMapper;
import com.rush.rushaicodemother.model.entity.GenerationToolApproval;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalStatus;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpoint;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisToolApprovalRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final String APPROVAL_ID = "a".repeat(64);
    private GenerationToolApprovalMapper mapper;
    private MyBatisToolApprovalRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationToolApprovalMapper.class);
        repository = new MyBatisToolApprovalRepository(mapper);
    }

    @Test
    void duplicateRequestMustBeIdempotentOnlyForSameTargetAndPayload() {
        ToolApprovalRecord request = request();
        when(mapper.selectOne("task-1", "rollbackSnapshot", APPROVAL_ID))
                .thenReturn(entity("{\"snapshotName\":\"safe\"}"));

        ToolApprovalRecord persisted = repository.createPending(request);

        assertEquals(ToolApprovalStatus.PENDING, persisted.status());
        verify(mapper).insertPending(any());

        when(mapper.selectOne("task-1", "rollbackSnapshot", APPROVAL_ID))
                .thenReturn(entity("{\"snapshotName\":\"other\"}"));
        assertThrows(BusinessException.class, () -> repository.createPending(request));
    }

    @Test
    void approveExecuteCompleteAndExpirationMustRemainAtomicAndBounded() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        when(mapper.approve("task-1", "rollbackSnapshot", APPROVAL_ID, 7L, now)).thenReturn(1);
        when(mapper.beginExecution(
                "task-1", "rollbackSnapshot", APPROVAL_ID, "call-1", now, 3)).thenReturn(1);
        when(mapper.completeExecution(
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("rollbackSnapshot"),
                org.mockito.ArgumentMatchers.eq(APPROVAL_ID),
                org.mockito.ArgumentMatchers.eq("call-1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(now))).thenReturn(1);
        when(mapper.expireBefore(now, 100)).thenReturn(4);

        assertTrue(repository.approve(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, APPROVAL_ID, 7L, NOW));
        assertTrue(repository.beginExecution(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                APPROVAL_ID, "call-1", NOW, 3));
        assertTrue(repository.completeExecution(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                APPROVAL_ID, "call-1", new com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome(false, "ok"), NOW));
        assertEquals(4, repository.expireBefore(NOW, 100));
        assertThrows(IllegalArgumentException.class, () -> repository.expireBefore(NOW, 1001));
    }

    @Test
    void invocationCheckpointMustBeImmutableAndTargetBound() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1",
                "manageSnapshot",
                "{\"action\":\"rollbackSnapshot\"}",
                "{\"taskId\":\"task-1\",\"stage\":\"codegen\"}",
                NOW
        );
        GenerationToolApproval persisted = entity("{\"snapshotName\":\"safe\"}");
        persisted.setToolRequestId(checkpoint.requestId());
        persisted.setToolName(checkpoint.toolName());
        persisted.setArgumentsDigest(checkpoint.argumentsDigest());
        String checkpointJson = checkpointJson(checkpoint);
        persisted.setCheckpointJson(checkpointJson);
        when(mapper.attachInvocationCheckpoint(
                "task-1", "rollbackSnapshot", APPROVAL_ID,
                checkpoint.requestId(), checkpoint.toolName(), checkpoint.argumentsDigest(),
                checkpointJson, now)).thenReturn(1);
        when(mapper.selectOne("task-1", "rollbackSnapshot", APPROVAL_ID)).thenReturn(persisted);

        ToolApprovalRecord result = repository.attachInvocationCheckpoint(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, APPROVAL_ID, checkpoint);

        assertEquals(checkpoint, result.invocationCheckpoint());

        ToolInvocationCheckpoint conflict = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-2",
                "manageSnapshot",
                checkpoint.argumentsJson(),
                checkpoint.runtimeStateJson(),
                NOW
        );
        assertThrows(BusinessException.class, () -> repository.attachInvocationCheckpoint(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, APPROVAL_ID, conflict));
    }

    @Test
    void executionOutcomeEvidenceMustRoundTripAndLegacyJsonMustRemainReadable() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        when(mapper.completeExecution(
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("rollbackSnapshot"),
                org.mockito.ArgumentMatchers.eq(APPROVAL_ID),
                org.mockito.ArgumentMatchers.eq("call-1"),
                resultCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(now))).thenReturn(1);

        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                false, "deleted", true, List.of("src/obsolete.ts"));
        assertTrue(repository.completeExecution(
                "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                APPROVAL_ID, "call-1", outcome, NOW));

        GenerationToolApproval persisted = entity("{}");
        persisted.setStatus("consumed");
        persisted.setExecutionResult(resultCaptor.getValue());
        when(mapper.selectOne("task-1", "rollbackSnapshot", APPROVAL_ID))
                .thenReturn(persisted);

        ToolExecutionOutcome restored = repository.find(
                        "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, APPROVAL_ID)
                .orElseThrow()
                .executionOutcome();

        assertEquals(outcome, restored);

        persisted.setExecutionResult("{\"error\":false,\"resultText\":\"legacy\"}");
        ToolExecutionOutcome legacy = repository.find(
                        "task-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, APPROVAL_ID)
                .orElseThrow()
                .executionOutcome();
        assertEquals(new ToolExecutionOutcome(false, "legacy"), legacy);
    }

    @Test
    void expiredContinuationQueryMustRemainBoundedAndRequireCompleteCheckpoint() {
        ToolInvocationCheckpoint checkpoint = new ToolInvocationCheckpoint(
                ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION,
                "call-1", "manageSnapshot", "{}", "{\"taskId\":\"task-1\"}", NOW);
        GenerationToolApproval expired = entity("{}");
        expired.setStatus("expired");
        expired.setToolRequestId(checkpoint.requestId());
        expired.setToolName(checkpoint.toolName());
        expired.setArgumentsDigest(checkpoint.argumentsDigest());
        expired.setCheckpointJson(checkpointJson(checkpoint));
        when(mapper.selectWaitingContinuations(25)).thenReturn(List.of(expired));

        List<ToolApprovalRecord> results = repository.findWaitingContinuations(25);

        assertEquals(1, results.size());
        assertEquals(ToolApprovalStatus.EXPIRED, results.getFirst().status());
        assertEquals(checkpoint, results.getFirst().invocationCheckpoint());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findWaitingContinuations(1001));
    }

    private ToolApprovalRecord request() {
        return new ToolApprovalRecord(
                APPROVAL_ID, "task-1", 11L, 7L,
                DestructiveToolAction.SNAPSHOT_ROLLBACK,
                "{\"snapshotName\":\"safe\"}", ToolApprovalStatus.PENDING,
                NOW, NOW.plusSeconds(600), null, null, null, 0, null
        );
    }

    private GenerationToolApproval entity(String requestJson) {
        return GenerationToolApproval.builder()
                .approvalId(APPROVAL_ID)
                .taskId("task-1")
                .appId(11L)
                .userId(7L)
                .action("rollbackSnapshot")
                .requestJson(requestJson)
                .status("pending")
                .requestedAt(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()))
                .expiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(600), ZoneId.systemDefault()))
                .version(0L)
                .build();
    }

    private String checkpointJson(ToolInvocationCheckpoint checkpoint) {
        java.util.Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("schemaVersion", checkpoint.schemaVersion());
        json.put("requestId", checkpoint.requestId());
        json.put("toolName", checkpoint.toolName());
        json.put("argumentsJson", checkpoint.argumentsJson());
        json.put("runtimeStateJson", checkpoint.runtimeStateJson());
        json.put("capturedAt", checkpoint.capturedAt().toString());
        return JSONUtil.toJsonStr(json);
    }
}
