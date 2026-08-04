package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.ai.PromptOptimizerServiceFactory;
import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.orchestration.GenerationTaskOrchestrator;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.experience.GenerationExperienceEventMapper;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotencyService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.deployment.AppDeploymentService;
import com.rush.rushaicodemother.service.workspace.AppCodeWorkspaceService;

import static org.mockito.Mockito.mock;

/**
 * 为 {@link AppServiceImpl} 测试集中提供构造器依赖。
 *
 * <p>测试必须通过真实生产构造器创建服务，禁止使用反射写入业务依赖。</p>
 */
final class AppServiceImplTestFixture {

    private final AppPersistenceService appPersistenceService = mock(AppPersistenceService.class);
    private final PromptOptimizerServiceFactory promptOptimizerServiceFactory =
            mock(PromptOptimizerServiceFactory.class);
    private final AppDatabaseResourceService appDatabaseResourceService =
            mock(AppDatabaseResourceService.class);
    private final AppCodeWorkspaceService appCodeWorkspaceService =
            mock(AppCodeWorkspaceService.class);
    private final AppDeploymentService appDeploymentService =
            mock(AppDeploymentService.class);
    private final AppAccessPolicy appAccessPolicy = mock(AppAccessPolicy.class);
    private GenerationTaskOrchestrator generationTaskOrchestrator =
            mock(GenerationTaskOrchestrator.class);
    private GenerationEventPublisher generationEventPublisher =
            mock(GenerationEventPublisher.class);
    private GenerationTaskQueryService generationTaskQueryService =
            mock(GenerationTaskQueryService.class);
    private final GenerationTaskIdempotencyService generationTaskIdempotencyService =
            mock(GenerationTaskIdempotencyService.class);

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

    AppServiceImplTestFixture withGenerationTaskQueryService(
            GenerationTaskQueryService generationTaskQueryService) {
        this.generationTaskQueryService = generationTaskQueryService;
        return this;
    }

    AppServiceImpl createService() {
        return new AppServiceImpl(
                appPersistenceService,
                promptOptimizerServiceFactory,
                generationTaskOrchestrator,
                generationEventPublisher,
                new GenerationExperienceEventMapper(),
                generationTaskQueryService,
                generationTaskIdempotencyService,
                appDatabaseResourceService,
                appCodeWorkspaceService,
                appDeploymentService,
                appAccessPolicy
        );
    }

    AppPersistenceService persistenceService() {
        return appPersistenceService;
    }

    AppCodeWorkspaceService workspaceService() {
        return appCodeWorkspaceService;
    }

    AppDeploymentService deploymentService() {
        return appDeploymentService;
    }

    AppAccessPolicy accessPolicy() {
        return appAccessPolicy;
    }

    AppDatabaseResourceService databaseResourceService() {
        return appDatabaseResourceService;
    }

    GenerationTaskIdempotencyService idempotencyService() {
        return generationTaskIdempotencyService;
    }

}
