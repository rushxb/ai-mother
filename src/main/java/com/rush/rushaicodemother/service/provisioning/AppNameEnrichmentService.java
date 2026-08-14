package com.rush.rushaicodemother.service.provisioning;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AppNameGeneratorServiceFactory;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 在应用完成落库后异步润色标题，不阻塞创建接口。 */
@Slf4j
@Service
public class AppNameEnrichmentService {

    private final AppNameGeneratorServiceFactory appNameGeneratorServiceFactory;
    private final AppMapper appMapper;
    private final TaskExecutor taskExecutor;

    public AppNameEnrichmentService(
            AppNameGeneratorServiceFactory appNameGeneratorServiceFactory,
            AppMapper appMapper,
            @Qualifier(AppNameEnrichmentConfiguration.APP_NAME_ENRICHMENT_EXECUTOR)
            TaskExecutor taskExecutor
    ) {
        this.appNameGeneratorServiceFactory = appNameGeneratorServiceFactory;
        this.appMapper = appMapper;
        this.taskExecutor = taskExecutor;
    }

    /**
 * 处理调度。
 *
 * @param appId 应用编号
 * @param userId 用户编号
 * @param initPrompt {@code initPrompt} 对应的调用参数
 * @param initialName {@code initialName} 对应的调用参数
 */
    public void schedule(Long appId, Long userId, String initPrompt, String initialName) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0
                || StrUtil.isBlank(initPrompt)
                || StrUtil.isBlank(initialName)) {
            return;
        }
        try {
            taskExecutor.execute(() -> enrich(appId, userId, initPrompt, initialName));
        } catch (RuntimeException rejection) {
            log.warn("应用标题润色任务提交失败，保留初始标题，appId: {}",
                    appId, LogExceptionSanitizer.sanitize(rejection));
        }
    }

    /** 补全应用名称补全。 */
    private void enrich(Long appId, Long userId, String initPrompt, String initialName) {
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            MonitorContextHolder.setContext(MonitorContext.builder()
                    .appId(appId.toString())
                    .userId(userId.toString())
                    .taskId("app-name:" + UUID.randomUUID())
                    .invocationPurpose(ModelInvocationPurpose.APP_NAME_ENRICHMENT)
                    .billingMode(ModelInvocationBillingMode.EXEMPT)
                    .billingExemptionReason("product_managed_enrichment")
                    .build());
            String generatedName = appNameGeneratorServiceFactory
                    .createAppNameGeneratorService()
                    .generateAppName(initPrompt);
            String normalizedName = AppNamePolicy.normalizeGeneratedName(generatedName);
            if (StrUtil.isBlank(normalizedName) || normalizedName.equals(initialName)) {
                return;
            }
            int updated = appMapper.updateGeneratedNameIfUnchanged(
                    appId, initialName, normalizedName);
            if (updated == 0) {
                log.debug("应用标题已被修改或应用已删除，跳过 AI 润色结果，appId: {}", appId);
            }
        } catch (RuntimeException failure) {
            log.warn("AI 润色应用标题失败，保留初始标题，appId: {}",
                    appId, LogExceptionSanitizer.sanitize(failure));
        } finally {
            restoreMonitorContext(previousContext);
        }
    }

    private void restoreMonitorContext(MonitorContext previousContext) {
        if (previousContext == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(previousContext);
        }
    }
}
