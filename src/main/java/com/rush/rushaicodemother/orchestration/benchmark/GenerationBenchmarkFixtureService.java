package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/** 为端到端生成基准创建真实的关系和工作区固定装置。 */
@Slf4j
@Service
public class GenerationBenchmarkFixtureService {

    static final String BENCHMARK_ACCOUNT = "generation-benchmark";
    private static final long BENCHMARK_CREDITS = 1_000_000L;

    private final UserPersistenceService userPersistenceService;
    private final AppPersistenceService appPersistenceService;
    private final PasswordHashService passwordHashService;
    private final GenerationBenchmarkRequestFactory requestFactory;
    private final VueProjectTemplateBootstrapService vueBootstrapService;
    private final BackendProjectTemplateBootstrapService backendBootstrapService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationBenchmarkValidationEngine validationEngine;
    private final AppDeletionService appDeletionService;
    private final TenantProvisioningService tenantProvisioningService;

    public GenerationBenchmarkFixtureService(UserPersistenceService userPersistenceService,
                                             AppPersistenceService appPersistenceService,
                                             PasswordHashService passwordHashService,
                                             GenerationBenchmarkRequestFactory requestFactory,
                                             VueProjectTemplateBootstrapService vueBootstrapService,
                                             BackendProjectTemplateBootstrapService backendBootstrapService,
                                             GenerationWorkspaceService generationWorkspaceService,
                                             GenerationBenchmarkValidationEngine validationEngine,
                                             AppDeletionService appDeletionService,
                                             TenantProvisioningService tenantProvisioningService) {
        this.userPersistenceService = userPersistenceService;
        this.appPersistenceService = appPersistenceService;
        this.passwordHashService = passwordHashService;
        this.requestFactory = requestFactory;
        this.vueBootstrapService = vueBootstrapService;
        this.backendBootstrapService = backendBootstrapService;
        this.generationWorkspaceService = generationWorkspaceService;
        this.validationEngine = validationEngine;
        this.appDeletionService = appDeletionService;
        this.tenantProvisioningService = tenantProvisioningService;
    }

    public GenerationBenchmarkFixture create(GenerationBenchmarkTask task) {
        if (task == null || task.prompt() == null || task.prompt().isBlank()) {
            throw new IllegalArgumentException("benchmark task and prompt are required");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(task.codeGenType());
        if (codeGenType == null) {
            throw new IllegalArgumentException("benchmark code generation type is unsupported");
        }
        User user = benchmarkUser();
        Long tenantId = tenantProvisioningService.requirePersonalTenantId(user);
        long appId = appPersistenceService.createPrepared(new AppPersistenceService.NewApp(
                "benchmark-" + normalizedTaskId(task.id()),
                task.prompt(),
                codeGenType.getValue(),
                AppConstant.DEFAULT_APP_PRIORITY,
                user.getId(),
                tenantId
        ));
        App app = appPersistenceService.findActiveById(appId);
        if (app == null) {
            throw new IllegalStateException("persisted benchmark app cannot be loaded");
        }
        try {
            if (requiresExistingWorkspace(task)) {
                bootstrapWorkspace(appId, codeGenType, task.prompt());
            }
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, codeGenType);
            GenerationBenchmarkValidationPlan validationPlan = validationEngine.prepare(
                    task,
                    workspace,
                    user.getId()
            );
            return new GenerationBenchmarkFixture(
                    requestFactory.create(task, app, user),
                    validationPlan,
                    () -> deleteFixtureSafely(appId)
            );
        } catch (RuntimeException failure) {
            deleteFixtureSafely(appId);
            throw failure;
        }
    }

    private User benchmarkUser() {
        User existing = userPersistenceService.findActiveByAccount(BENCHMARK_ACCOUNT);
        if (existing != null) {
            return requireUser(existing);
        }
        try {
            long userId = userPersistenceService.createUser(new UserPersistenceService.NewUser(
                    BENCHMARK_ACCOUNT,
                    passwordHashService.hash(UUID.randomUUID().toString()),
                    "Generation Benchmark",
                    null,
                    "Isolated user for repeatable generation quality benchmarks",
                    UserConstant.DEFAULT_ROLE,
                    BENCHMARK_CREDITS
            ));
            return requireUser(userPersistenceService.findActiveById(userId));
        } catch (RuntimeException createFailure) {
            User raced = userPersistenceService.findActiveByAccount(BENCHMARK_ACCOUNT);
            if (raced != null) {
                return requireUser(raced);
            }
            throw createFailure;
        }
    }

    private User requireUser(User user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new IllegalStateException("benchmark user cannot be loaded");
        }
        return user;
    }

    private boolean requiresExistingWorkspace(GenerationBenchmarkTask task) {
        return task.mode() != null && !"CREATE".equalsIgnoreCase(task.mode());
    }

    private void bootstrapWorkspace(Long appId, CodeGenTypeEnum codeGenType, String prompt) {
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT || codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            vueBootstrapService.bootstrapIfNecessary(appId, codeGenType, prompt);
        }
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT || codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            backendBootstrapService.bootstrapIfNecessary(appId, codeGenType);
        }
    }

    private String normalizedTaskId(String taskId) {
        String normalized = taskId == null ? "unknown" : taskId.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.isBlank() ? "unknown" : normalized.substring(0, Math.min(64, normalized.length()));
    }

    private void deleteFixtureSafely(Long appId) {
        try {
            appDeletionService.delete(appId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Benchmark fixture cleanup failed, appId: {}, error: {}",
                    appId, LogExceptionSanitizer.sanitizeMessage(cleanupFailure));
        }
    }
}
