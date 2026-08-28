package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapResult;
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
    private final GenerationTemplateBootstrapRegistry templateBootstrapRegistry;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationBenchmarkValidationEngine validationEngine;
    private final AppDeletionService appDeletionService;
    private final TenantProvisioningService tenantProvisioningService;

    public GenerationBenchmarkFixtureService(UserPersistenceService userPersistenceService,
                                             AppPersistenceService appPersistenceService,
                                             PasswordHashService passwordHashService,
                                             GenerationBenchmarkRequestFactory requestFactory,
                                             GenerationTemplateBootstrapRegistry templateBootstrapRegistry,
                                             GenerationWorkspaceService generationWorkspaceService,
                                             GenerationBenchmarkValidationEngine validationEngine,
                                             AppDeletionService appDeletionService,
                                             TenantProvisioningService tenantProvisioningService) {
        this.userPersistenceService = userPersistenceService;
        this.appPersistenceService = appPersistenceService;
        this.passwordHashService = passwordHashService;
        this.requestFactory = requestFactory;
        this.templateBootstrapRegistry = templateBootstrapRegistry;
        this.generationWorkspaceService = generationWorkspaceService;
        this.validationEngine = validationEngine;
        this.appDeletionService = appDeletionService;
        this.tenantProvisioningService = tenantProvisioningService;
    }

    /**
 * 创建生成基准测试{@code Fixture}。
 *
 * @param task 任务
 * @return 生成基准测试{@code Fixture}
 */
    public GenerationBenchmarkFixture create(GenerationBenchmarkTask task) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GenerationWorkspace workspace = requiresExistingWorkspace(task)
                    ? bootstrapWorkspace(appId, codeGenType, task.prompt())
                    : generationWorkspaceService.resolve(appId, codeGenType);
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

    /** 返回基准测试用户。 */
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
        return task.fixtureKind() == GenerationBenchmarkFixtureKind.TEMPLATE_PROJECT;
    }

    /**
     * 复用生产链模板初始化模块，并直接采用其已校验的工作区快照。
     * 这样新增工程类型时，Benchmark 不需要复制类型分支，也不会二次解析工作区。
     */
    private GenerationWorkspace bootstrapWorkspace(
            Long appId,
            CodeGenTypeEnum codeGenType,
            String prompt
    ) {
        GenerationTemplateBootstrapResult result = templateBootstrapRegistry.bootstrap(
                appId, codeGenType, prompt);
        if (!result.supported()) {
            throw new IllegalArgumentException(
                    "benchmark code generation type has no template bootstrap adapter: "
                            + codeGenType.getValue());
        }
        return result.workspace();
    }

    private String normalizedTaskId(String taskId) {
        String normalized = taskId == null ? "unknown" : taskId.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.isBlank() ? "unknown" : normalized.substring(0, Math.min(64, normalized.length()));
    }

    /** 删除{@code Fixture}安全处理。 */
    private void deleteFixtureSafely(Long appId) {
        try {
            appDeletionService.delete(appId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Benchmark fixture cleanup failed, appId: {}, error: {}",
                    appId, LogExceptionSanitizer.sanitizeMessage(cleanupFailure));
        }
    }
}
