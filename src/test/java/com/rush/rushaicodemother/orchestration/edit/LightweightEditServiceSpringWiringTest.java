package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextTrustService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LightweightEditServiceSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GenerationEditRouteService.class, () -> mock(GenerationEditRouteService.class))
            .withBean(AiCodeEditServiceFactory.class, () -> mock(AiCodeEditServiceFactory.class))
            .withBean(GenerationEditModelInvoker.class, () -> mock(GenerationEditModelInvoker.class))
            .withBean(EditFileLocatorService.class, () -> mock(EditFileLocatorService.class))
            .withBean(EditContextPackageBuilder.class, () -> mock(EditContextPackageBuilder.class))
            .withBean(EditValidationPolicyService.class, () -> mock(EditValidationPolicyService.class))
            .withBean(DevServerManager.class, () -> mock(DevServerManager.class))
            .withBean(RepositoryContextTrustService.class,
                    () -> mock(RepositoryContextTrustService.class))
            .withBean(GenerationPatchApplyService.class, () -> mock(GenerationPatchApplyService.class))
            .withBean(EditFileSnapshotService.class, () -> mock(EditFileSnapshotService.class))
            .withBean(WorkspaceSemanticIndexService.class, () -> mock(WorkspaceSemanticIndexService.class))
            .withBean(BackgroundValidationService.class, () -> mock(BackgroundValidationService.class))
            .withBean(GenerationEventPublisher.class, () -> mock(GenerationEventPublisher.class))
            .withBean(GenerationWorkspaceService.class, () -> mock(GenerationWorkspaceService.class))
            .withBean(GenerationAppStateService.class, () -> mock(GenerationAppStateService.class))
            .withBean(GenerationTaskLifecycleService.class, () -> mock(GenerationTaskLifecycleService.class))
            .withBean(ChatHistoryService.class, () -> mock(ChatHistoryService.class))
            .withBean(EditStatePersistenceService.class, () -> mock(EditStatePersistenceService.class))
            .withUserConfiguration(
                    LightweightEditAiService.class,
                    LightweightEditContextAssembler.class,
                    LightweightEditOperationConverter.class,
                    LightweightEditPatchExecutor.class,
                    LightweightEditTaskLifecycleService.class,
                    LightweightRuntimeValidationService.class,
                    LightweightEditService.class
            );

    @Test
    void shouldCreateLightweightEditModulesWithoutCircularDependencies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LightweightEditService.class);
            assertThat(context).hasSingleBean(LightweightRuntimeValidationService.class);
            assertThat(context).hasSingleBean(LightweightEditPatchExecutor.class);
            assertThat(context).hasSingleBean(LightweightEditTaskLifecycleService.class);
        });
    }
}
