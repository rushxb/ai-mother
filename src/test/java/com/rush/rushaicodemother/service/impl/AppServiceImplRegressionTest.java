package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.UserCreditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AppServiceImplRegressionTest {

    @Test
    void shouldIncludeLifecycleArtifactsInGenerationErrorPayload() throws Exception {
        Map<String, GenerationArtifact> artifacts = lifecycleArtifacts();
        GenerationPreparation preparation = newPreparation(artifacts, List.of(), Map.of());
        GenerationTaskOrchestrator orchestrator = newOrchestrator(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) invoke(
                orchestrator,
                "buildGenerationErrorData",
                new Class<?>[]{GenerationPreparation.class, String.class, String.class, boolean.class, Map.class},
                preparation,
                "build_failed",
                "构建失败",
                true,
                Map.of("projectPath", "/tmp/project")
        );

        assertEquals("build_failed", data.get("category"));
        assertEquals("构建失败", data.get("message"));
        assertEquals("task-1", data.get("taskId"));
        assertEquals(Boolean.TRUE, data.get("recoverable"));
        assertEquals("/tmp/project", data.get("projectPath"));
        assertSame(artifacts.get("rollback_point").payload(), data.get("rollback_point"));
        assertSame(artifacts.get("diff_summary").payload(), data.get("diff_summary"));
        assertSame(artifacts.get("patch_result").payload(), data.get("patch_result"));
        assertSame(artifacts.get("generation_commit").payload(), data.get("generation_commit"));
        assertSame(artifacts.get("rollback_restore").payload(), data.get("rollback_restore"));
    }

    @Test
    void shouldBuildStableLifecycleEventPayloads() throws Exception {
        GenerationPreparation preparation = newPreparation(lifecycleArtifacts(), List.of(), Map.of());
        GenerationTaskOrchestrator orchestrator = newOrchestrator(new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()));

        assertEventPayload(orchestrator, preparation, "buildDiffSummaryEventData",
                lifecycleArtifacts().get("diff_summary"), "diff", "created", "生成后差异摘要已生成");
        assertEventPayload(orchestrator, preparation, "buildPatchResultEventData",
                lifecycleArtifacts().get("patch_result"), "patch", "applied", "Patch 实际落盘结果已对齐");
        assertEventPayload(orchestrator, preparation, "buildCommitResultEventData",
                lifecycleArtifacts().get("generation_commit"), "commit", "committed", "生成结果已提交到本地 Git");
        assertEventPayload(orchestrator, preparation, "buildRollbackRestoreEventData",
                lifecycleArtifacts().get("rollback_restore"), "rollback", "restored", "生成失败，已从本地回滚点恢复项目文件。");
    }

    @Test
    void shouldRecordUserWaitMetricOnceWhenSessionCompletes() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationTaskOrchestrator orchestrator = newOrchestrator(new GenerationOrchestrationMetricsCollector(meterRegistry));
        GenerationPreparation preparation = newPreparation(
                lifecycleArtifacts(),
                List.of(GenerationStreamEvent.agentEvent("route", Map.of("orchestrationMode", "light"))),
                Map.of("planner", 25L, "context", 10L)
        );
        GenerationSession session = new GenerationSession(preparation);

        invoke(
                orchestrator,
                "completeGenerationSession",
                new Class<?>[]{GenerationSession.class, GenerationPreparation.class, String.class},
                session,
                preparation,
                "success"
        );
        invoke(
                orchestrator,
                "completeGenerationSession",
                new Class<?>[]{GenerationSession.class, GenerationPreparation.class, String.class},
                session,
                preparation,
                "success"
        );

        assertEquals(1, meterRegistry.find("generation_orchestration_user_wait_duration_seconds")
                .tag("orchestration_mode", "light")
                .tag("target_type", CodeGenTypeEnum.VUE_PROJECT.getValue())
                .tag("status", "success")
                .timer()
                .count());
    }

    private GenerationTaskOrchestrator newOrchestrator(GenerationOrchestrationMetricsCollector metricsCollector) {
        return new GenerationTaskOrchestrator(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                metricsCollector,
                null,
                null,
                null,
                null,
                new NoopGenerationTraceService(),
                null,
                null,
                null,
                new NoopUserCreditService(),
                null
        );
    }

    private void assertEventPayload(GenerationTaskOrchestrator orchestrator,
                                    GenerationPreparation preparation,
                                    String methodName,
                                    GenerationArtifact artifact,
                                    String stage,
                                    String status,
                                    String summary) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) invoke(
                orchestrator,
                methodName,
                new Class<?>[]{GenerationPreparation.class, GenerationArtifact.class},
                preparation,
                artifact
        );

        assertEquals("Orchestrator", data.get("agent"));
        assertEquals(stage, data.get("stage"));
        assertEquals(status, data.get("status"));
        assertEquals(summary, data.get("summary"));
        assertEquals("task-1", data.get("taskId"));
        assertSame(artifact.payload(), data.get("artifact"));
    }

    private Map<String, GenerationArtifact> lifecycleArtifacts() {
        Map<String, GenerationArtifact> artifacts = new LinkedHashMap<>();
        artifacts.put("rollback_point", GenerationArtifact.of("rollback_point", "Orchestrator", "回滚点",
                RollbackPoint.created(1L, "task-1", "snapshot-1", "/tmp/snapshot", "/tmp/project",
                        "html", "vue_project", 2).toPayload()));
        artifacts.put("diff_summary", GenerationArtifact.of("diff_summary", "Orchestrator", "差异摘要",
                DiffSummary.created(1L, "task-1", "/tmp/snapshot", "/tmp/project",
                        List.of("src/App.vue"), List.of(), List.of(), List.of()).toPayload()));
        artifacts.put("patch_result", GenerationArtifact.of("patch_result", "Orchestrator", "Patch 结果",
                new PatchResult("v1", "local_diff", "applied", 1L, "task-1",
                        List.of("src/App.vue"), List.of(), List.of(),
                        List.of("src/App.vue"), List.of(), List.of(),
                        List.of(), List.of(), 1, 0, 0, "", null).toPayload()));
        artifacts.put("generation_commit", GenerationArtifact.of("generation_commit", "Orchestrator", "本地提交",
                GenerationCommitResult.committed(1L, "task-1", "/tmp/project",
                        "abcdef1234567890", "main", List.of("src/App.vue")).toPayload()));
        artifacts.put("rollback_restore", GenerationArtifact.of("rollback_restore", "Orchestrator", "回滚恢复",
                RollbackRestore.restored(1L, "task-1", "snapshot", "/tmp/snapshot",
                        "/tmp/project", "/tmp/backup", 2).toPayload()));
        return artifacts;
    }

    private GenerationPreparation newPreparation(Map<String, GenerationArtifact> artifacts,
                                                 List<GenerationStreamEvent> events,
                                                 Map<String, Long> timings) {
        return new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.VUE_PROJECT,
                true,
                "agent",
                "增强提示词",
                events,
                artifacts,
                QualityGateResult.passed(List.of(), List.of("ok")),
                timings,
                "task-1"
        );
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        Object result = method.invoke(target, args);
        if (method.getReturnType() != Void.TYPE) {
            assertNotNull(result);
        }
        return result;
    }

    private static class NoopGenerationTraceService implements GenerationTraceService {

        @Override
        public void startTask(String taskId, Long appId, Long userId, CodeGenTypeEnum originalType, CodeGenTypeEnum targetType, String userPrompt, String enhancedPrompt, boolean requiresBuildValidation, String qualityGate, String orchestrationMode) {
        }

        @Override
        public void updateStage(String taskId, String stage, String message) {
        }

        @Override
        public void updateMemorySummary(String taskId, String memorySummary) {
        }

        @Override
        public void completeTask(String taskId, String status, Instant startedAt, String errorMessage) {
        }

        @Override
        public void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
        }

        @Override
        public void recordBuildResult(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
        }

        @Override
        public void recordModelCall(String taskId, Long appId, Long userId, Map<String, Object> metadata) {
        }

        @Override
        public GenerationTask getByTaskId(String taskId) {
            return null;
        }

        @Override
        public List<GenerationTask> listRecentTasksByAppId(Long appId, int limit) {
            return List.of();
        }

        @Override
        public List<GenerationBuildLog> listRecentBuildLogsByAppId(Long appId, int limit) {
            return List.of();
        }

        @Override
        public List<GenerationBuildLog> listBuildLogsByTaskId(String taskId, int limit) {
            return List.of();
        }
    }

    private static class NoopUserCreditService implements UserCreditService {

        @Override
        public long calculateCreditCost(long totalTokens) {
            return 0;
        }

        @Override
        public long adjustCredit(Long userId, Long changeAmount, String type, String bizId, String remark, Long adminUserId, Long tokenCount) {
            return 0;
        }

        @Override
        public void chargeGenerationTask(String taskId) {
        }
    }
}
