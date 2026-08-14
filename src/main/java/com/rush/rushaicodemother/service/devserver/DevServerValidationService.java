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
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
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
    private final BrowserRuntimeVerifier browserRuntimeVerifier;

    /**
     * 运行任务感知的开发服务器验证。
     */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType
    ) {
        return validate(DevServerValidationRequest.of(taskId, appId, userId, codeGenType));
    }

    /** 针对所提供的栅栏拥有的确切隔离工作区运行验证。 */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType,
            GenerationExecutionFence executionFence
    ) {
        return validate(DevServerValidationRequest.of(taskId, appId, userId, codeGenType)
                .withExecutionFence(executionFence));
    }

    /**
     * 运行验证；就绪后按请求声明的持有者语义决定是否在返回前停止会话。
     *
     * <p>就绪回调点选在「已就绪、仍在运行」的窗口内：{@link DevServerSessionOwnership#CALLER_SCOPED}
     * 时验证结束的 {@code finally} 会停掉本次调用创建的会话，若等到验证返回后再通知，
     * 用户拿到的预览地址已经失效。回调异常被吞掉并记录，因为它只承载体验增强，
     * 不得影响验证结论。</p>
     *
     * @param request 验证入参，含隔离栅栏、就绪回调与会话持有者语义
     */
    public DevServerValidationResult validate(DevServerValidationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Dev Server 验证请求不能为空");
        }
        String taskId = request.taskId();
        Long appId = request.appId();
        Long userId = request.userId();
        CodeGenTypeEnum codeGenType = request.codeGenType();
        validateRequest(taskId, appId, userId, codeGenType);
        Runnable readyCallback = request.onDevServerReady();
        // 持有者语义在进入 try 之前读出：finally 必须能无条件拿到它，
        // 且它由调用方显式声明而非从「有没有传就绪回调」反推 —— 后者会让持有权
        // 随着一个体验增强参数被悄悄改变。
        DevServerSessionOwnership ownership = request.ownership();
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
                    request.executionFence(),
                    request.environmentOverrides()
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
            notifyDevServerReady(taskId, readyCallback);
            BrowserRuntimeValidationResult browserResult = validateBrowserIfRequired(
                    taskId,
                    startResult.port(),
                    request
            );
            if (!awaitErrorCollectionWindow(taskId, collectionWindow, collector)) {
                return DevServerValidationResult.interrupted(
                                taskId, appId, elapsedSince(startNanos))
                        .withBrowserValidation(browserResult);
            }

            List<DevServerError> errors = collector.getErrors();
            long elapsed = elapsedSince(startNanos);
            log.info("Dev Server validation completed, taskId={}, criticalErrors={}, warnings={}, duration={}ms",
                    taskId, collector.getCriticalErrorCount(), collector.getWarningCount(), elapsed);

            if (collector.hasCriticalError()) {
                return DevServerValidationResult.failed(taskId, appId, errors, elapsed)
                        .withBrowserValidation(browserResult);
            }
            if (collector.hasWarning()) {
                return DevServerValidationResult.warning(taskId, appId, errors, elapsed)
                        .withBrowserValidation(browserResult);
            }
            return DevServerValidationResult.passed(taskId, appId, elapsed)
                    .withBrowserValidation(browserResult);
        } finally {
            stopOwnedSession(appId, startResult, ownership);
            devServerManager.unregisterErrorCollector(appId, collector);
        }
    }

    private BrowserRuntimeValidationResult validateBrowserIfRequired(
            String taskId,
            int port,
            DevServerValidationRequest request
    ) {
        if (request.browserValidationPolicy() == null) {
            return null;
        }
        generationExecutionContextService.assertCanContinue(taskId);
        BrowserRuntimeValidationResult result = browserRuntimeVerifier.verify(
                URI.create("http://127.0.0.1:" + port + "/"),
                request.browserValidationPolicy()
        );
        generationExecutionContextService.assertCanContinue(taskId);
        return result;
    }

    /** 触发就绪回调；回调只承载体验增强，异常不得影响验证结论。 */
    private void notifyDevServerReady(String taskId, Runnable onDevServerReady) {
        try {
            onDevServerReady.run();
        } catch (RuntimeException exception) {
            log.warn("Dev Server 就绪回调失败，验证流程继续执行，taskId={}, error={}",
                    taskId, LogExceptionSanitizer.sanitizeMessage(exception));
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

    /**
     * 按持有者语义决定是否在本方法作用域内停止会话。
     *
     * <p>{@link DevServerSessionOwnership#TASK_SCOPED} 时刻意不停：暂定预览必须在验证返回后
     * 继续可用，停止责任已交给生成任务：发布前和任务终态都会按工作区或 execution fence
     * 精确停止；{@code DevServerManager} 心跳只承担进程异常情况下的最终兜底。</p>
     */
    private void stopOwnedSession(Long appId,
                                  DevServerStartResult startResult,
                                  DevServerSessionOwnership ownership) {
        if (startResult == null || !startResult.startedByCaller()) {
            return;
        }
        if (!ownership.stopsWithCaller()) {
            log.info("按任务作用域移交 Dev Server 持有权，验证返回后保持运行，appId={}", appId);
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
