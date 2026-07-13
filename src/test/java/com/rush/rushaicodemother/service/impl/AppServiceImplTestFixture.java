package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.ai.PromptOptimizerServiceFactory;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.service.AiModelService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.deployment.AppDeploymentService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.workspace.AppCodeWorkspaceService;

import static org.mockito.Mockito.mock;

/**
 * 为 {@link AppServiceImpl} 测试集中提供构造器依赖。
 *
 * <p>测试必须通过真实生产构造器创建服务，禁止使用反射写入业务依赖。</p>
 */
final class AppServiceImplTestFixture {

    private final UserService userService = mock(UserService.class);
    private final AiModelService aiModelService = mock(AiModelService.class);
    private final PromptOptimizerServiceFactory promptOptimizerServiceFactory =
            mock(PromptOptimizerServiceFactory.class);
    private final AppDatabaseResourceService appDatabaseResourceService =
            mock(AppDatabaseResourceService.class);
    private final AppCodeWorkspaceService appCodeWorkspaceService =
            mock(AppCodeWorkspaceService.class);
    private final AppDeploymentService appDeploymentService =
            mock(AppDeploymentService.class);
    private final AppDeletionService appDeletionService = mock(AppDeletionService.class);

    private GenerationTaskOrchestrator generationTaskOrchestrator =
            mock(GenerationTaskOrchestrator.class);
    private GenerationEventPublisher generationEventPublisher =
            mock(GenerationEventPublisher.class);

    AppServiceImplTestFixture withGenerationTaskOrchestrator(
            GenerationTaskOrchestrator generationTaskOrchestrator) {
        this.generationTaskOrchestrator = generationTaskOrchestrator;
        return this;
    }

    AppServiceImplTestFixture withGenerationEventPublisher(
            GenerationEventPublisher generationEventPublisher) {
        this.generationEventPublisher = generationEventPublisher;
        return this;
    }

    AppServiceImpl createService() {
        return new AppServiceImpl(
                userService,
                aiModelService,
                promptOptimizerServiceFactory,
                generationTaskOrchestrator,
                generationEventPublisher,
                appDatabaseResourceService,
                appCodeWorkspaceService,
                appDeploymentService,
                appDeletionService
        );
    }

    AppCodeWorkspaceService workspaceService() {
        return appCodeWorkspaceService;
    }

    AppDeploymentService deploymentService() {
        return appDeploymentService;
    }
}
