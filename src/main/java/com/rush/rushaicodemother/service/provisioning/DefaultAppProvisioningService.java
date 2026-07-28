package com.rush.rushaicodemother.service.provisioning;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.intent.BackendIntentDetector;
import com.rush.rushaicodemother.ai.intent.DeterministicCodeGenTypeRouter;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.dto.app.AppAddRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import com.rush.rushaicodemother.service.lifecycle.AppOperationLockManager;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** 默认应用创建与复制供给实现。 */
@Service
@Slf4j
public class DefaultAppProvisioningService implements AppProvisioningService {

    private static final Duration AMBIGUOUS_ROUTING_TIMEOUT = Duration.ofSeconds(5);

    private final AppMapper appMapper;
    private final AiModelRuntimeService aiModelRuntimeService;
    private final BackendIntentDetector backendIntentDetector;
    private final DeterministicCodeGenTypeRouter deterministicCodeGenTypeRouter;
    private final AiCodeGenTypeRoutingServiceFactory routingServiceFactory;
    private final AppNameEnrichmentService appNameEnrichmentService;
    private final ChatHistoryService chatHistoryService;
    private final AppArtifactLifecycleService artifactLifecycleService;
    private final AppOperationLockManager operationLockManager;
    private final TenantProvisioningService tenantProvisioningService;
    private final TransactionOperations transactionOperations;

    /**
 * 创建默认应用{@code Provisioning}服务实例并完成必要的依赖和初始状态设置。
 *
 * @param appMapper 应用映射器
 * @param aiModelRuntimeService AI 模型运行时服务
 * @param backendIntentDetector {@code backendIntentDetector} 对应的调用参数
 * @param deterministicCodeGenTypeRouter {@code deterministicCodeGenTypeRouter} 对应的调用参数
 * @param routingServiceFactory 路由服务工厂
 * @param appNameEnrichmentService 应用名称补全服务
 * @param chatHistoryService 对话历史服务
 * @param artifactLifecycleService 制品生命周期服务
 * @param operationLockManager 操作锁管理器
 * @param tenantProvisioningService 处理该职责的领域服务
 * @param transactionManager 事务管理器
 */
    @Autowired
    public DefaultAppProvisioningService(AppMapper appMapper,
                                         AiModelRuntimeService aiModelRuntimeService,
                                         BackendIntentDetector backendIntentDetector,
                                         DeterministicCodeGenTypeRouter deterministicCodeGenTypeRouter,
                                         AiCodeGenTypeRoutingServiceFactory routingServiceFactory,
                                         AppNameEnrichmentService appNameEnrichmentService,
                                         ChatHistoryService chatHistoryService,
                                         AppArtifactLifecycleService artifactLifecycleService,
                                         AppOperationLockManager operationLockManager,
                                         TenantProvisioningService tenantProvisioningService,
                                         PlatformTransactionManager transactionManager) {
        this(
                appMapper,
                aiModelRuntimeService,
                backendIntentDetector,
                deterministicCodeGenTypeRouter,
                routingServiceFactory,
                appNameEnrichmentService,
                chatHistoryService,
                artifactLifecycleService,
                operationLockManager,
                tenantProvisioningService,
                new TransactionTemplate(transactionManager)
        );
    }

    /** 创建默认应用{@code Provisioning}服务实例并完成必要的依赖和初始状态设置。 */
    DefaultAppProvisioningService(AppMapper appMapper,
                                  AiModelRuntimeService aiModelRuntimeService,
                                  BackendIntentDetector backendIntentDetector,
                                  DeterministicCodeGenTypeRouter deterministicCodeGenTypeRouter,
                                  AiCodeGenTypeRoutingServiceFactory routingServiceFactory,
                                  AppNameEnrichmentService appNameEnrichmentService,
                                  ChatHistoryService chatHistoryService,
                                  AppArtifactLifecycleService artifactLifecycleService,
                                  AppOperationLockManager operationLockManager,
                                  TenantProvisioningService tenantProvisioningService,
                                  TransactionOperations transactionOperations) {
        this.appMapper = appMapper;
        this.aiModelRuntimeService = aiModelRuntimeService;
        this.backendIntentDetector = backendIntentDetector;
        this.deterministicCodeGenTypeRouter = deterministicCodeGenTypeRouter;
        this.routingServiceFactory = routingServiceFactory;
        this.appNameEnrichmentService = appNameEnrichmentService;
        this.chatHistoryService = chatHistoryService;
        this.artifactLifecycleService = artifactLifecycleService;
        this.operationLockManager = operationLockManager;
        this.tenantProvisioningService = tenantProvisioningService;
        this.transactionOperations = transactionOperations;
    }

    /**
 * 创建默认应用{@code Provisioning}。
 *
 * @param request 请求参数
 * @param actor 操作发起人
 * @return 计算或处理后的数值结果
 */
    @Override
    public Long create(AppAddRequest request, User actor) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "创建应用请求不能为空");
        validateActor(actor);
        String initPrompt = StrUtil.trim(request.getInitPrompt());
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");

        aiModelRuntimeService.ensureGenerationModelsConfigured();
        CodeGenTypeEnum selectedCodeGenType = selectCodeGenType(initPrompt);
        Long tenantId = tenantProvisioningService.requirePersonalTenantId(actor);
        String initialName = AppNamePolicy.initialName(initPrompt);
        App app = App.builder()
                .appName(initialName)
                .initPrompt(initPrompt)
                .codeGenType(selectedCodeGenType.getValue())
                .priority(AppConstant.DEFAULT_APP_PRIORITY)
                .userId(actor.getId())
                .tenantId(tenantId)
                .build();

        Long appId = transactionOperations.execute(status -> {
            insertApp(app, "创建应用失败");
            return requirePersistedAppId(app, "创建应用后未生成有效 ID");
        });
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.SYSTEM_ERROR, "创建应用事务未返回有效 ID");
        appNameEnrichmentService.schedule(appId, actor.getId(), initPrompt, initialName);
        log.info("应用创建成功，appId: {}, userId: {}, codeGenType: {}",
                appId, actor.getId(), selectedCodeGenType.getValue());
        return appId;
    }

    /**
 * 复制默认应用{@code Provisioning}。
 *
 * @param sourceAppId 来源应用编号
 * @param actor 操作发起人
 * @return 计算或处理后的数值结果
 */
    @Override
    public Long copy(Long sourceAppId, User actor) {
        ThrowUtils.throwIf(sourceAppId == null || sourceAppId <= 0,
                ErrorCode.PARAMS_ERROR, "源应用 ID 错误");
        validateActor(actor);
        return operationLockManager.execute(sourceAppId, () -> copyLocked(sourceAppId, actor));
    }

    /** 复制{@code Locked}。 */
    private Long copyLocked(Long sourceAppId, User actor) {
        App sourceApp = appMapper.selectCopySourceState(sourceAppId);
        ThrowUtils.throwIf(sourceApp == null, ErrorCode.NOT_FOUND_ERROR, "源应用不存在");
        validateCopySource(sourceApp);
        Long targetTenantId = tenantProvisioningService.requirePersonalTenantId(actor);
        App targetApp = App.builder()
                .appName(sourceApp.getAppName())
                .cover(sourceApp.getCover())
                .initPrompt(sourceApp.getInitPrompt())
                .codeGenType(sourceApp.getCodeGenType())
                .priority(AppConstant.DEFAULT_APP_PRIORITY)
                .userId(actor.getId())
                .tenantId(targetTenantId)
                .build();
        AtomicBoolean artifactCopied = new AtomicBoolean(false);

        try {
            Long targetAppId = transactionOperations.execute(status -> {
                insertApp(targetApp, "复制应用失败");
                Long persistedTargetId = requirePersistedAppId(targetApp, "复制应用后未生成有效 ID");
                artifactLifecycleService.copyGeneratedArtifact(sourceApp, targetApp);
                artifactCopied.set(true);
                chatHistoryService.copyByAppId(
                        sourceApp.getId(),
                        persistedTargetId,
                        actor.getId()
                );
                return persistedTargetId;
            });
            ThrowUtils.throwIf(targetAppId == null || targetAppId <= 0,
                    ErrorCode.SYSTEM_ERROR, "复制应用事务未返回有效 ID");
            return targetAppId;
        } catch (RuntimeException copyFailure) {
            compensateCopiedArtifact(targetApp, artifactCopied.get(), copyFailure);
            throw copyFailure;
        }
    }

    /** 从候选项中选择代码生成类型。 */
    private CodeGenTypeEnum selectCodeGenType(String initPrompt) {
        BackendIntentDetector.BackendIntentResult intentResult =
                backendIntentDetector.detectIntent(initPrompt);
        ThrowUtils.throwIf(intentResult == null, ErrorCode.SYSTEM_ERROR, "后端意图检测未返回结果");
        CodeGenTypeEnum localType = deterministicCodeGenTypeRouter
                .route(initPrompt, intentResult)
                .orElse(null);
        if (localType != null) {
            log.info("应用类型由本地规则确定，selectedType: {}, intentLevel: {}",
                    localType, intentResult.level());
            return localType;
        }
        return routeAmbiguousCodeGenType(initPrompt, intentResult);
    }

    /** 为{@code Ambiguous}代码生成类型选择处理路由。 */
    private CodeGenTypeEnum routeAmbiguousCodeGenType(
            String initPrompt,
            BackendIntentDetector.BackendIntentResult intentResult) {
        try {
            AiCodeGenTypeRoutingService routingService =
                    routingServiceFactory.createAiCodeGenTypeRoutingService(AMBIGUOUS_ROUTING_TIMEOUT);
            if (routingService == null) {
                log.warn("应用类型 AI 路由服务不可用，使用前端默认类型");
                return CodeGenTypeEnum.VUE_PROJECT;
            }
            CodeGenTypeEnum routedType = routingService.routeCodeGenType(initPrompt);
            if (routedType == null) {
                log.warn("应用类型 AI 路由未返回结果，使用前端默认类型");
                return CodeGenTypeEnum.VUE_PROJECT;
            }
            CodeGenTypeEnum selectedType = backendIntentDetector
                    .constrainCodeGenType(intentResult, routedType);
            if (selectedType == null) {
                log.warn("应用类型 AI 路由约束未返回结果，使用前端默认类型");
                return CodeGenTypeEnum.VUE_PROJECT;
            }
            log.info("应用类型由 AI 路由确定，selectedType: {}, routedType: {}, intentLevel: {}",
                    selectedType, routedType, intentResult.level());
            return selectedType;
        } catch (RuntimeException routingFailure) {
            log.warn("应用类型 AI 路由失败，使用前端默认类型",
                    LogExceptionSanitizer.sanitize(routingFailure));
            return CodeGenTypeEnum.VUE_PROJECT;
        }
    }

    private void insertApp(App app, String failureMessage) {
        int insertedRows = appMapper.insert(app);
        ThrowUtils.throwIf(insertedRows != 1, ErrorCode.OPERATION_ERROR, failureMessage);
    }

    private Long requirePersistedAppId(App app, String failureMessage) {
        Long appId = app.getId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.SYSTEM_ERROR, failureMessage);
        return appId;
    }

    /** 处理补偿{@code Copied}制品。 */
    private void compensateCopiedArtifact(App targetApp,
                                          boolean artifactCopied,
                                          RuntimeException copyFailure) {
        if (!artifactCopied) {
            return;
        }
        try {
            artifactLifecycleService.deleteGeneratedArtifact(targetApp);
        } catch (RuntimeException cleanupFailure) {
            BusinessException consistencyFailure = new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "复制应用失败且目标代码目录清理失败，请联系管理员处理",
                    copyFailure
            );
            consistencyFailure.addSuppressed(cleanupFailure);
            throw consistencyFailure;
        }
    }

    private void validateCopySource(App sourceApp) {
        ThrowUtils.throwIf(sourceApp.getId() == null || sourceApp.getId() <= 0,
                ErrorCode.SYSTEM_ERROR, "源应用数据异常");
        ThrowUtils.throwIf(CodeGenTypeEnum.getEnumByValue(sourceApp.getCodeGenType()) == null,
                ErrorCode.SYSTEM_ERROR, "源应用代码生成类型异常");
    }

    private void validateActor(User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null || actor.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
    }
}
