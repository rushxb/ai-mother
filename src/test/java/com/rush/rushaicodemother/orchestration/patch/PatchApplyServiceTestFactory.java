package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.mockito.Mockito.mock;

/** Creates patch-service test fixtures with the same explicit dependency graph as production. */
public final class PatchApplyServiceTestFactory {

    private PatchApplyServiceTestFactory() {
    }

    public static GenerationPatchApplyService create() {
        return create(new SimpleMeterRegistry(), new PatchExecutionProperties());
    }

    public static GenerationPatchApplyService create(MeterRegistry meterRegistry) {
        return create(meterRegistry, new PatchExecutionProperties());
    }

    public static GenerationPatchApplyService create(PatchExecutionProperties properties) {
        return create(new SimpleMeterRegistry(), properties);
    }

    public static GenerationPatchApplyService create(MeterRegistry meterRegistry,
                                                     PatchExecutionProperties properties) {
        PatchWorkspaceFileService workspaceFileService = new PatchWorkspaceFileService(properties);
        PatchStructuredContentService structuredContentService = new PatchStructuredContentService();
        FrontendPatchImportPolicy frontendImportPolicy = new FrontendPatchImportPolicy(workspaceFileService);
        PatchOperationValidator operationValidator = new PatchOperationValidator(
                workspaceFileService,
                structuredContentService,
                frontendImportPolicy,
                new GeneratedWorkspaceTrustPolicy()
        );
        PatchOperationExecutor operationExecutor = new PatchOperationExecutor(
                workspaceFileService,
                structuredContentService,
                new PatchBatchRollbackService(workspaceFileService, properties)
        );
        return new GenerationPatchApplyService(
                new GenerationOrchestrationMetricsCollector(meterRegistry),
                new PatchOperationResourcePolicy(properties),
                operationValidator,
                operationExecutor,
                mock(GenerationTaskFenceGuard.class)
        );
    }
}
