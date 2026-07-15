package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.ai.tools.DiffSummaryTool;
import com.rush.rushaicodemother.ai.tools.SnapshotRollbackTool;
import com.rush.rushaicodemother.ai.tools.ToolWorkspaceFileService;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.git.GitCommandExecutor;
import com.rush.rushaicodemother.infrastructure.git.GitTransactionResourceManager;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SnapshotWorkspaceConsumerBeanWiringTest {

    @Test
    void shouldWireSnapshotBoundaryAndAllProductionConsumersWithoutCycles() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CodeStorageProperties.class,
                    WorkspaceFileSystemProperties.class,
                    WorkspaceFileSystemService.class,
                    GenerationWorkspaceService.class,
                    SnapshotNamePolicy.class,
                    GenerationSnapshotWorkspaceService.class,
                    GenerationRollbackPointService.class,
                    GenerationDiffSummaryService.class,
                    GenerationRollbackRestoreService.class,
                    GenerationCommitProperties.class,
                    GitTransactionResourceManager.class,
                    GenerationCommitService.class,
                    DiffSummaryTool.class,
                    SnapshotRollbackTool.class
            );
            context.registerBean(
                    GenerationOrchestrationMetricsCollector.class,
                    () -> new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry())
            );
            context.registerBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class));
            context.registerBean(ToolWorkspaceFileService.class, () -> mock(ToolWorkspaceFileService.class));
            context.registerBean(
                    GenerationToolExecutionContextService.class,
                    () -> mock(GenerationToolExecutionContextService.class)
            );

            context.refresh();

            assertNotNull(context.getBean(GenerationSnapshotWorkspaceService.class));
            assertNotNull(context.getBean(GenerationRollbackPointService.class));
            assertNotNull(context.getBean(GenerationDiffSummaryService.class));
            assertNotNull(context.getBean(GenerationRollbackRestoreService.class));
            assertNotNull(context.getBean(GenerationCommitService.class));
            assertNotNull(context.getBean(DiffSummaryTool.class));
            assertNotNull(context.getBean(SnapshotRollbackTool.class));
        }
    }
}
