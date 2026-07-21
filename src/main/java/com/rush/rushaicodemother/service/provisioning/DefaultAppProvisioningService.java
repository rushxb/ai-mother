package com.rush.rushaicodemother.service.provisioning;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.AppNameGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.intent.BackendIntentDetector;
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

import java.util.concurrent.atomic.AtomicBoolean;

/** 默认应用创建与复制供给实现。 */
@Service
@Slf4j
public class DefaultAppProvisioningService implements AppProvisioningService {

    private static final int FALLBACK_APP_NAME_LENGTH = 12;
    private static final int MAX_APP_NAME_LENGTH = 16;

    private final AppMapper appMapper;
    private final AiModelRuntimeService aiModelRuntimeService;
    private final BackendIntentDetector backendIntentDetector;
    private final AiCodeGenTypeRoutingServiceFactory routingServiceFactory;
    private final AppNameGeneratorServiceFactory appNameGeneratorServiceFactory;
    private final ChatHistoryService chatHistoryService;
    private final AppArtifactLifecycleService artifactLifecycleService;
    private final AppOperationLockManager operationLockManager;
    private final TenantProvisioningService tenantProvisioningService;
    private final TransactionOperations transactionOperations;

    @Autowired
    public DefaultAppProvisioningService(AppMapper appMapper,
                                         AiModelRuntimeService aiModelRuntimeService,
                                         BackendIntentDetector backendIntentDetector,
                                         AiCodeGenTypeRoutingServiceFactory routingServiceFactory,
                                         AppNameGeneratorServiceFactory appNameGeneratorServiceFactory,
                                         ChatHistoryService chatHistoryService,
                                         AppArtifactLifecycleService artifactLifecycleService,
                                         AppOperationLockManager operationLockManager,
                                         TenantProvisioningService tenantProvisioningService,
                                         PlatformTransactionManager transactionManager) {
        this(
                appMapper,
                aiModelRuntimeService,
                backendIntentDetector,
                routingServiceFactory,
                appNameGeneratorServiceFactory,
                chatHistoryService,
                artifactLifecycleService,
                operationLockManager,
                tenantProvisioningService,
                new TransactionTemplate(transactionManager)
        );
    }

    DefaultAppProvisioningService(AppMapper appMapper,
                                  AiModelRuntimeService aiModelRuntimeService,
                                  BackendIntentDetector backendIntentDetector,
                                  AiCodeGenTypeRoutingServiceFactory routingServiceFactory,
                                  AppNameGeneratorServiceFactory appNameGeneratorServiceFactory,
                                  ChatHistoryService chatHistoryService,
                                  AppArtifactLifecycleService artifactLifecycleService,
                                  AppOperationLockManager operationLockManager,
                                  TenantProvisioningService tenantProvisioningService,
                                  TransactionOperations transactionOperations) {
        this.appMapper = appMapper;
        this.aiModelRuntimeService = aiModelRuntimeService;
        this.backendIntentDetector = backendIntentDetector;
        this.routingServiceFactory = routingServiceFactory;
        this.appNameGeneratorServiceFactory = appNameGeneratorServiceFactory;
        this.chatHistoryService = chatHistoryService;
        this.artifactLifecycleService = artifactLifecycleService;
        this.operationLockManager = operationLockManager;
        this.tenantProvisioningService = tenantProvisioningService;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public Long create(AppAddRequest request, User actor) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR, "创建应用请求不能为空");
        validateActor(actor);
        String initPrompt = StrUtil.trim(request.getInitPrompt());
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");

        aiModelRuntimeService.ensureGenerationModelsConfigured();
        CodeGenTypeEnum selectedCodeGenType = selectCodeGenType(initPrompt);
        Long tenantId = tenantProvisioningService.requirePersonalTenantId(actor);
        App app = App.builder()
                .appName(generateAppName(initPrompt))
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
        log.info("应用创建成功，appId: {}, userId: {}, codeGenType: {}",
                appId, actor.getId(), selectedCodeGenType.getValue());
        return appId;
    }

    @Override
    public Long copy(Long sourceAppId, User actor) {
        ThrowUtils.throwIf(sourceAppId == null || sourceAppId <= 0,
                ErrorCode.PARAMS_ERROR, "源应用 ID 错误");
        validateActor(actor);
        return operationLockManager.execute(sourceAppId, () -> copyLocked(sourceAppId, actor));
    }

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

    private CodeGenTypeEnum selectCodeGenType(String initPrompt) {
        BackendIntentDetector.BackendIntentResult intentResult =
                backendIntentDetector.detectIntent(initPrompt);
        ThrowUtils.throwIf(intentResult == null, ErrorCode.SYSTEM_ERROR, "后端意图检测未返回结果");
        AiCodeGenTypeRoutingService routingService =
                routingServiceFactory.createAiCodeGenTypeRoutingService();
        ThrowUtils.throwIf(routingService == null, ErrorCode.SYSTEM_ERROR, "应用类型路由服务不可用");
        CodeGenTypeEnum routedType = routingService.routeCodeGenType(initPrompt);
        ThrowUtils.throwIf(routedType == null, ErrorCode.SYSTEM_ERROR, "应用类型路由未返回结果");
        CodeGenTypeEnum selectedType = backendIntentDetector.constrainCodeGenType(intentResult, routedType);
        ThrowUtils.throwIf(selectedType == null, ErrorCode.SYSTEM_ERROR, "无法确定应用代码生成类型");
        log.info("应用类型路由完成，selectedType: {}, routedType: {}, intentLevel: {}",
                selectedType, routedType, intentResult.level());
        return selectedType;
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

    private String generateAppName(String initPrompt) {
        try {
            String generatedName = appNameGeneratorServiceFactory
                    .createAppNameGeneratorService()
                    .generateAppName(initPrompt);
            String normalizedName = normalizeAppName(generatedName);
            if (StrUtil.isNotBlank(normalizedName)) {
                return normalizedName;
            }
        } catch (Exception exception) {
            log.warn("AI 生成应用标题失败，使用本地兜底标题", LogExceptionSanitizer.sanitize(exception));
        }
        return fallbackAppName(initPrompt);
    }

    private String normalizeAppName(String appName) {
        if (StrUtil.isBlank(appName)) {
            return null;
        }
        String normalized = StrUtil.trim(appName)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("^(标题|应用名|应用名称)\\s*[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\"'“”‘’《》【】\\s]+", "")
                .replaceAll("[\"'“”‘’《》【】\\s]+$", "");
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        return truncateByCodePoints(normalized, MAX_APP_NAME_LENGTH);
    }

    private String fallbackAppName(String initPrompt) {
        String normalizedPrompt = StrUtil.trim(initPrompt)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ");
        if (StrUtil.isBlank(normalizedPrompt)) {
            return "未命名应用";
        }
        return truncateByCodePoints(normalizedPrompt, FALLBACK_APP_NAME_LENGTH);
    }

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

    private String truncateByCodePoints(String value, int maximumCodePoints) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maximumCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maximumCodePoints);
        return value.substring(0, endIndex);
    }

    private void validateActor(User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null || actor.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
    }
}
