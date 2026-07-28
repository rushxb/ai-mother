package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过托管开发服务器会话验证生成的应用程序。
 *
 * <p>A 启动成功表示环回端口稳定。然后该服务收集延迟
 * 有界窗口的编译错误，仅停止当前调用者拥有的会话。</p>
 *
 * <p>Generation Runtime 策略在此适应通用超时和取消控制，因此
 * Dev Server生命周期模块在生成任务之外仍然可重用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevServerValidationService {

    private final DevServerManager devServerManager;
    private final DevServerRuntimeProperties runtimeProperties;
    private final GenerationExecutionContextService generationExecutionContextService;

    /**
     * 运行任务感知的开发服务器验证。
     */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType
    ) {
        return validate(taskId, appId, userId, codeGenType, null);
    }

    /** 针对所提供的栅栏拥有的确切隔离工作区运行验证。 */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType,
            GenerationExecutionFence executionFence
    ) {
        validateRequest(taskId, appId, userId, codeGenType);
        generationExecutionContextService.assertCanContinue(taskId);
        Duration startupTimeout = generationExecutionContextService.clampTimeout(
                taskId,
                runtimeProperties.getStartupTimeout()
        );

        long startNanos = System.nanoTime();
        log.info("Starting Dev Server validation, taskId={}, appId={}, codeGenType={}",
                taskId, appId, codeGenType);

        DevServerErrorCollector collector = new DevServerErrorCollector();
        devServerManager.registerErrorCollector(appId, collector);

        DevServerStartResult startResult = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            App app = buildAppForValidation(appId, codeGenType);
            DevServerStartOptions startOptions = new DevServerStartOptions(
                    taskId,
                    startupTimeout,
                    () -> generationExecutionContextService.shouldStop(taskId),
                    executionFence
            );
            try {
                startResult = devServerManager.startDevServer(app, userId, startOptions);
            } catch (GenerationExecutionPolicyException exception) {
                throw exception;
            } catch (BusinessException exception) {
                generationExecutionContextService.assertCanContinue(taskId);
                return mapStartFailure(taskId, appId, startNanos, exception);
            } catch (RuntimeException exception) {
                generationExecutionContextService.assertCanContinue(taskId);
                log.warn("Dev Server startup failed, taskId={}, error={}",
                        taskId, LogExceptionSanitizer.sanitizeMessage(exception));
                return DevServerValidationResult.startupFailed(
                        taskId,
                        appId,
                        elapsedSince(startNanos),
                        exception.getMessage()
                );
            }

            generationExecutionContextService.assertCanContinue(taskId);
            Duration collectionWindow = generationExecutionContextService.clampTimeout(
                    taskId,
                    runtimeProperties.getValidationErrorCollectionWindow()
            );
            log.info("Dev Server is ready, taskId={}, port={}; collecting delayed errors",
                    taskId, startResult.port());
            if (!awaitErrorCollectionWindow(taskId, collectionWindow, collector)) {
                return DevServerValidationResult.interrupted(taskId, appId, elapsedSince(startNanos));
            }

            List<DevServerError> errors = collector.getErrors();
            long elapsed = elapsedSince(startNanos);
            log.info("Dev Server validation completed, taskId={}, criticalErrors={}, warnings={}, duration={}ms",
                    taskId, collector.getCriticalErrorCount(), collector.getWarningCount(), elapsed);

            if (collector.hasCriticalError()) {
                return DevServerValidationResult.failed(taskId, appId, errors, elapsed);
            }
            if (collector.hasWarning()) {
                return DevServerValidationResult.warning(taskId, appId, errors, elapsed);
            }
            return DevServerValidationResult.passed(taskId, appId, elapsed);
        } finally {
            stopOwnedSession(appId, startResult);
            devServerManager.unregisterErrorCollector(appId, collector);
        }
    }

    /** 将输入映射为开始失败。 */
    private DevServerValidationResult mapStartFailure(
            String taskId,
            Long appId,
            long startNanos,
            BusinessException exception
    ) {
        long elapsed = elapsedSince(startNanos);
        if (exception.getCode() == ErrorCode.NOT_FOUND_ERROR.getCode()) {
            return DevServerValidationResult.skipped(taskId, appId, exception.getMessage());
        }
        if (exception.getCause() instanceof DevServerStartException startException
                && startException.reason() == DevServerStartException.Reason.STARTUP_TIMEOUT) {
            return DevServerValidationResult.timeout(taskId, appId, elapsed);
        }
        return DevServerValidationResult.startupFailed(taskId, appId, elapsed, exception.getMessage());
    }

    /** 等待错误集合窗口完成。 */
    private boolean awaitErrorCollectionWindow(String taskId,
                                               Duration duration,
                                               DevServerErrorCollector collector) {
        long collectionWindowNanos = duration.toNanos();
        long criticalDrainWindowNanos = runtimeProperties
                .getValidationCriticalErrorDrainWindow()
                .toNanos();
        long pollNanos = runtimeProperties.getValidationPollInterval().toNanos();
        long startedNanos = System.nanoTime();
        long criticalStopAfterNanos = Long.MAX_VALUE;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            while (true) {
                generationExecutionContextService.assertCanContinue(taskId);
                long elapsedNanos = System.nanoTime() - startedNanos;
                if (criticalStopAfterNanos == Long.MAX_VALUE && collector.hasCriticalError()) {
                    criticalStopAfterNanos = Math.min(
                            collectionWindowNanos,
                            elapsedNanos + criticalDrainWindowNanos
                    );
                }
                long stopAfterNanos = Math.min(collectionWindowNanos, criticalStopAfterNanos);
                long remainingNanos = stopAfterNanos - elapsedNanos;
                if (remainingNanos <= 0) {
                    generationExecutionContextService.assertCanContinue(taskId);
                    return true;
                }
                TimeUnit.NANOSECONDS.sleep(Math.max(1, Math.min(pollNanos, remainingNanos)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 停止{@code Owned}会话。 */
    private void stopOwnedSession(Long appId, DevServerStartResult startResult) {
        if (startResult == null || !startResult.startedByCaller()) {
            return;
        }
        try {
            devServerManager.stopDevServer(appId);
            log.info("Stopped validation-owned Dev Server, appId={}", appId);
        } catch (RuntimeException exception) {
            log.warn("Failed to stop validation-owned Dev Server, appId={}, error={}",
                    appId, LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }

    /** 校验{@code ate}请求是否有效。 */
    private void validateRequest(String taskId, Long appId, Long userId, CodeGenTypeEnum codeGenType) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Task ID cannot be blank");
        }
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Application ID must be greater than zero");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "User ID must be greater than zero");
        }
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Code generation type cannot be null");
        }
    }

    private App buildAppForValidation(Long appId, CodeGenTypeEnum codeGenType) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(codeGenType.getValue());
        return app;
    }

    private long elapsedSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
