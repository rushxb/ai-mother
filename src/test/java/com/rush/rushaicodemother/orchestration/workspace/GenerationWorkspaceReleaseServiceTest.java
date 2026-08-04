package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationWorkspaceReleaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void backendMilestoneMustBePublishedAfterAtomicWorkspacePublication() {
        ReleaseFixture fixture = fixture(CodeGenTypeEnum.BACKEND_PROJECT, "backend-release");
        GenerationPreviewMilestoneService milestoneService = mock(GenerationPreviewMilestoneService.class);
        GenerationWorkspaceReleaseService service = fixture.service(milestoneService);

        GenerationWorkspacePublicationResult actual =
                service.releaseVerified(fixture.session(), CodeGenTypeEnum.BACKEND_PROJECT);

        assertSame(fixture.result(), actual);
        InOrder order = inOrder(fixture.publicationService(), milestoneService);
        order.verify(fixture.publicationService()).publishWithMetadata(
                fixture.session(), fixture.metadataService());
        order.verify(milestoneService).publishBuildReady(
                fixture.session(), CodeGenTypeEnum.BACKEND_PROJECT);
    }

    @ParameterizedTest
    @EnumSource(value = CodeGenTypeEnum.class, names = {"VUE_PROJECT", "FULL_STACK_PROJECT"})
    void browserProjectMilestoneMustUseRuntimeReadiness(CodeGenTypeEnum targetType) {
        ReleaseFixture fixture = fixture(targetType, "runtime-release-" + targetType.getValue());
        GenerationPreviewMilestoneService milestoneService = mock(GenerationPreviewMilestoneService.class);
        GenerationWorkspaceReleaseService service = fixture.service(milestoneService);

        service.releaseVerified(fixture.session(), targetType);

        verify(milestoneService).publishRuntimeReady(fixture.session(), targetType);
    }

    @Test
    void publicationFailureMustNotMarkFirstPreviewReady() {
        ReleaseFixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT, "failed-release");
        GenerationPreviewMilestoneService milestoneService = mock(GenerationPreviewMilestoneService.class);
        IllegalStateException publicationFailure = new IllegalStateException("发布失败");
        when(fixture.publicationService().publishWithMetadata(
                fixture.session(), fixture.metadataService())).thenThrow(publicationFailure);

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> fixture.service(milestoneService).releaseVerified(
                        fixture.session(), CodeGenTypeEnum.VUE_PROJECT));

        assertSame(publicationFailure, actual);
        verifyNoInteractions(milestoneService);
    }

    @Test
    void repeatedPublicationMustEmitFirstPreviewExactlyOnce() {
        ReleaseFixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT, "repeated-release");
        GenerationEventPublisher eventPublisher = mock(GenerationEventPublisher.class);
        GenerationPreviewMilestoneService milestoneService = new GenerationPreviewMilestoneService(
                mock(GenerationPerformanceMonitorService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                eventPublisher
        );
        GenerationWorkspaceReleaseService service = fixture.service(milestoneService);

        service.releaseVerified(fixture.session(), CodeGenTypeEnum.VUE_PROJECT);
        service.releaseVerified(fixture.session(), CodeGenTypeEnum.VUE_PROJECT);

        assertNotNull(fixture.session().executionContext().firstPreviewReadyAt());
        verify(eventPublisher, times(1)).publishSafely(
                any(GenerationTaskRequest.class),
                eq(GenerationEventType.FIRST_PREVIEW_READY),
                eq("首个可运行预览已就绪"),
                any());
    }

    @Test
    void milestoneFailureMustNotTurnPublishedWorkspaceIntoTaskFailure() {
        ReleaseFixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT, "milestone-failure");
        GenerationPreviewMilestoneService milestoneService = mock(GenerationPreviewMilestoneService.class);
        when(milestoneService.publishRuntimeReady(
                fixture.session(), CodeGenTypeEnum.VUE_PROJECT))
                .thenThrow(new IllegalStateException("里程碑通知失败"));

        GenerationWorkspacePublicationResult actual = fixture.service(milestoneService)
                .releaseVerified(fixture.session(), CodeGenTypeEnum.VUE_PROJECT);

        assertSame(fixture.result(), actual);
    }

    private ReleaseFixture fixture(CodeGenTypeEnum targetType, String taskId) {
        GenerationWorkspacePublicationService publicationService =
                mock(GenerationWorkspacePublicationService.class);
        GenerationWorkspacePublicationMetadataService metadataService =
                mock(GenerationWorkspacePublicationMetadataService.class);
        GenerationWorkspacePublicationResult result = mock(GenerationWorkspacePublicationResult.class);
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
                1L, targetType, canonicalRoot, canonicalRoot, false,
                frontendRoot, backendRoot, Set.of(), Set.of());
        GenerationExecutionWorkspace executionWorkspace = new GenerationExecutionWorkspace(
                1L, fence, targetType, epochRoot, typeRoot, workspace, null);

        App app = new App();
        app.setId(1L);
        User user = new User();
        user.setId(2L);
        GenerationSession session = new GenerationSession(null, context);
        session.bindExecutionWorkspace(executionWorkspace);
        session.bindTaskRequest(new GenerationTaskRequest(app, "生成应用", user));
        session.recordRoute("create");
        when(publicationService.publishWithMetadata(session, metadataService)).thenReturn(result);
        return new ReleaseFixture(publicationService, metadataService, result, session);
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
                Instant.parse("2026-07-23T00:00:00Z"),
                new GenerationExecutionLimits(
                        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMillis(500), budgets),
                Clock.fixed(Instant.parse("2026-07-23T00:00:30Z"), ZoneOffset.UTC)
        );
    }

    private record ReleaseFixture(
            GenerationWorkspacePublicationService publicationService,
            GenerationWorkspacePublicationMetadataService metadataService,
            GenerationWorkspacePublicationResult result,
            GenerationSession session
    ) {
        private GenerationWorkspaceReleaseService service(
                GenerationPreviewMilestoneService milestoneService
        ) {
            return new GenerationWorkspaceReleaseService(
                    publicationService, metadataService, milestoneService);
        }
    }
}
