package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 在同一资源持有窗口内验证生成全栈项目的后端与浏览器运行时。 */
@Slf4j
@Service
public class GeneratedFullStackRuntimeVerifier {

    private final GeneratedBackendRuntime backendRuntime;
    private final DevServerValidationService frontendRuntimeVerifier;

    public GeneratedFullStackRuntimeVerifier(
            GeneratedBackendRuntime backendRuntime,
            DevServerValidationService frontendRuntimeVerifier
    ) {
        this.backendRuntime = backendRuntime;
        this.frontendRuntimeVerifier = frontendRuntimeVerifier;
    }

    public FullStackRuntimeValidationResult verify(
            Path backendProjectDirectory,
            DevServerValidationRequest frontendRequest,
            BrowserRuntimeValidationPolicy browserPolicy
    ) {
        return verify(
                GeneratedBackendRuntimeRequest.unmanaged(backendProjectDirectory),
                frontendRequest,
                browserPolicy);
    }

    /** 执行任务范围的全栈联合验证，后端启动与前端验证共享同一取消状态。 */
    public FullStackRuntimeValidationResult verify(
            GeneratedBackendRuntimeRequest backendRequest,
            DevServerValidationRequest frontendRequest,
            BrowserRuntimeValidationPolicy browserPolicy
    ) {
        long startedAt = System.nanoTime();
        if (backendRequest == null || backendRequest.projectDirectory() == null) {
            return failed(startedAt, "backend_project_directory_missing");
        }
        if (frontendRequest == null) {
            return failed(startedAt, "fullstack_frontend_request_missing");
        }
        BrowserRuntimeValidationPolicy resolvedPolicy = browserPolicy == null
                ? BrowserRuntimeValidationPolicy.productionRuntime()
                : browserPolicy;
        try (GeneratedBackendRuntimeHandle handle = backendRuntime.start(backendRequest)) {
            BackendRuntimeValidationResult initialBackend =
                    BackendRuntimeValidationResult.observe(handle, elapsedSince(startedAt));
            if (!initialBackend.passed()) {
                return new FullStackRuntimeValidationResult(
                        initialBackend,
                        null,
                        elapsedSince(startedAt)
                );
            }
            DevServerValidationRequest connectedFrontend = frontendRequest
                    .withEnvironmentOverrides(Map.of(
                            "VITE_API_BASE_URL",
                            "http://127.0.0.1:" + initialBackend.port() + "/api"
                    ))
                    .withBrowserValidation(resolvedPolicy);
            DevServerValidationResult frontend = frontendRuntimeVerifier.validate(
                    connectedFrontend);
            BackendRuntimeValidationResult finalBackend =
                    BackendRuntimeValidationResult.observe(handle, elapsedSince(startedAt));
            return new FullStackRuntimeValidationResult(
                    finalBackend,
                    frontend,
                    elapsedSince(startedAt)
            );
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            log.warn("全栈运行时验证执行失败: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
            return failed(startedAt, "fullstack_runtime_verifier_failed");
        }
    }

    private FullStackRuntimeValidationResult failed(long startedAt, String violation) {
        long durationMs = elapsedSince(startedAt);
        return new FullStackRuntimeValidationResult(
                BackendRuntimeValidationResult.failed(durationMs, violation),
                null,
                durationMs
        );
    }

    private long elapsedSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
