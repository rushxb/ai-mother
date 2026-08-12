package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationProvisionalPreviewLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void executionWorkspaceRootMustBeStoppedBeforePublication() {
        DevServerManager manager = mock(DevServerManager.class);
        GenerationSession session = session(CodeGenTypeEnum.VUE_PROJECT, "vue-stop");
        Path expectedRoot = session.executionWorkspace().workspace().canonicalRootPath();
        when(manager.stopDevServerIfRootedAt(1L, expectedRoot)).thenReturn(true);

        boolean stopped = new GenerationProvisionalPreviewLifecycle(manager)
                .stopBeforePublication(session);

        assertTrue(stopped);
        verify(manager).stopDevServerIfRootedAt(1L, expectedRoot);
    }

    @Test
    void fullStackPreviewRootMustMatchFrontendSubdirectory() {
        DevServerManager manager = mock(DevServerManager.class);
        GenerationSession session = session(CodeGenTypeEnum.FULL_STACK_PROJECT, "full-stack-stop");
        Path frontendRoot = session.executionWorkspace().workspace().frontendRootPath();
        when(manager.stopDevServerIfRootedAt(eq(1L), any(Path.class))).thenReturn(true);

        new GenerationProvisionalPreviewLifecycle(manager).stopBeforePublication(session);

        // 全栈项目的 Dev Server 跑在前端子目录；若这里传工作区根目录，
        // 目录比对永远不匹配，停止退化成静默空操作 —— 正是本轮要修的「无声失效」的另一种形态。
        verify(manager).stopDevServerIfRootedAt(1L, frontendRoot);
    }

    @Test
    void sessionWithoutExecutionWorkspaceMustNotTouchDevServer() {
        DevServerManager manager = mock(DevServerManager.class);
        GenerationSession session = new GenerationSession(null, context("no-workspace"));

        boolean stopped = new GenerationProvisionalPreviewLifecycle(manager)
                .stopBeforePublication(session);

        assertFalse(stopped);
        verifyNoInteractions(manager);
    }

    @Test
    void terminalCleanupMustUseExecutionFenceOwnership() {
        DevServerManager manager = mock(DevServerManager.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("terminal-stop", "worker-1", 3L);
        when(manager.stopDevServerIfOwnedBy(1L, fence)).thenReturn(true);

        boolean stopped = new GenerationProvisionalPreviewLifecycle(manager)
                .stopForTerminal(1L, fence);

        assertTrue(stopped);
        verify(manager).stopDevServerIfOwnedBy(1L, fence);
    }

    private GenerationSession session(CodeGenTypeEnum targetType, String taskId) {
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-1", 1L);
        GenerationExecutionContext context = context(taskId);
        context.bindExecutionFence(fence);

        Path epochRoot = tempDir.resolve(taskId).toAbsolutePath().normalize();
        Path typeRoot = epochRoot.resolve(targetType.getValue());
        Path canonicalRoot = typeRoot.resolve("project");
        Path frontendRoot = targetType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? canonicalRoot.resolve("frontend") : canonicalRoot;
        Path backendRoot = targetType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? canonicalRoot.resolve("backend") : canonicalRoot;
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, targetType, canonicalRoot, canonicalRoot, true,
                frontendRoot, backendRoot, Set.of(), Set.of());
        GenerationSession session = new GenerationSession(null, context);
        session.bindExecutionWorkspace(new GenerationExecutionWorkspace(
                1L, fence, targetType, epochRoot, typeRoot, workspace, null));
        return session;
    }

    private GenerationExecutionContext context(String taskId) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        return new GenerationExecutionContext(
                taskId,
                1L,
                2L,
                Instant.parse("2026-08-10T00:00:00Z"),
                new GenerationExecutionLimits(
                        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMillis(500), budgets),
                Clock.fixed(Instant.parse("2026-08-10T00:00:30Z"), ZoneOffset.UTC)
        );
    }
}
