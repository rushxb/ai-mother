package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Validates generated applications through a managed Dev Server session.
 *
 * <p>A successful start means that the loopback port is stable. The service then collects delayed
 * compilation errors for a bounded window and stops only sessions owned by the current caller.</p>
 *
 * <p>Generation Runtime policy is adapted here into generic timeout and cancellation controls, so
 * the Dev Server lifecycle module remains reusable outside generation tasks.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevServerValidationService {

    private final DevServerManager devServerManager;
    private final DevServerRuntimeProperties runtimeProperties;
    private final GenerationExecutionContextService generationExecutionContextService;

    /**
     * Runs task-aware Dev Server validation.
     */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType
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
        try {
            App app = buildAppForValidation(appId, codeGenType);
            DevServerStartOptions startOptions = new DevServerStartOptions(
                    taskId,
                    startupTimeout,
                    () -> generationExecutionContextService.shouldStop(taskId)
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
            if (!awaitErrorCollectionWindow(taskId, collectionWindow)) {
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

    private boolean awaitErrorCollectionWindow(String taskId, Duration duration) {
        long deadlineNanos = System.nanoTime() + duration.toNanos();
        long pollNanos = runtimeProperties.getValidationPollInterval().toNanos();
        try {
            while (true) {
                generationExecutionContextService.assertCanContinue(taskId);
                long remainingNanos = deadlineNanos - System.nanoTime();
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
