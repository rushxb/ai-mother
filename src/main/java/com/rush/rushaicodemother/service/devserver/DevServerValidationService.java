package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dev Server 运行时验证服务。
 *
 * <p>启动成功即表示回环端口已经稳定就绪；本服务只在可配置的窗口内
 * 继续收集首次编译错误，并且只停止由当前调用新建的会话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevServerValidationService {

    private final DevServerManager devServerManager;
    private final DevServerRuntimeProperties runtimeProperties;

    /**
     * 执行 Dev Server 运行时验证。
     */
    public DevServerValidationResult validate(
            String taskId,
            Long appId,
            Long userId,
            CodeGenTypeEnum codeGenType
    ) {
        validateRequest(taskId, appId, userId, codeGenType);
        long startNanos = System.nanoTime();
        log.info("开始 Dev Server 运行时验证，taskId: {}, appId: {}, codeGenType: {}",
                taskId, appId, codeGenType);

        DevServerErrorCollector collector = new DevServerErrorCollector();
        devServerManager.registerErrorCollector(appId, collector);

        DevServerStartResult startResult = null;
        try {
            App app = buildAppForValidation(appId, codeGenType);
            try {
                startResult = devServerManager.startDevServer(app, userId);
            } catch (BusinessException exception) {
                return mapStartFailure(taskId, appId, startNanos, exception);
            } catch (RuntimeException exception) {
                log.warn("Dev Server 启动异常，taskId: {}, error: {}", taskId, exception.getMessage());
                return DevServerValidationResult.startupFailed(
                        taskId,
                        appId,
                        elapsedSince(startNanos),
                        exception.getMessage()
                );
            }

            log.info("Dev Server 已就绪，taskId: {}, port: {}，进入错误收集窗口",
                    taskId, startResult.port());
            if (!awaitErrorCollectionWindow(runtimeProperties.getValidationErrorCollectionWindow())) {
                return DevServerValidationResult.interrupted(taskId, appId, elapsedSince(startNanos));
            }

            List<DevServerError> errors = collector.getErrors();
            long elapsed = elapsedSince(startNanos);
            log.info("Dev Server 验证完成，taskId: {}, criticalErrors: {}, warnings: {}, duration: {}ms",
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

    private boolean awaitErrorCollectionWindow(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
            return true;
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
            log.info("验证用 Dev Server 已停止，appId: {}", appId);
        } catch (RuntimeException exception) {
            log.warn("停止验证用 Dev Server 失败，appId: {}, error: {}",
                    appId, exception.getMessage());
        }
    }

    private void validateRequest(String taskId, Long appId, Long userId, CodeGenTypeEnum codeGenType) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "任务 ID 不能为空");
        }
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID 必须大于 0");
        }
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
    }

    private App buildAppForValidation(Long appId, CodeGenTypeEnum codeGenType) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(codeGenType == null ? null : codeGenType.getValue());
        return app;
    }

    private long elapsedSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
