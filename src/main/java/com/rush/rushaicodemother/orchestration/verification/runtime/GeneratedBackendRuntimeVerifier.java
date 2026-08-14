package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 对生成的 Go 后端执行真实进程、端口、HTTP health 与存活验证。
 *
 * <p>该 module 复用 Benchmark 已在使用的托管 runtime adapter，因此线上和评测运行
 * 同一个进程启动及 HTTP 契约实现；调用方只消费归一化事实，不接触进程生命周期。</p>
 */
@Slf4j
@Service
public class GeneratedBackendRuntimeVerifier {

    private static final String COMMAND_SUMMARY = "go run -mod=readonly ./cmd/server";

    private final GeneratedBackendRuntime backendRuntime;

    public GeneratedBackendRuntimeVerifier(GeneratedBackendRuntime backendRuntime) {
        this.backendRuntime = backendRuntime;
    }

    public BackendRuntimeValidationResult verify(Path backendProjectDirectory) {
        long startedAt = System.nanoTime();
        if (backendProjectDirectory == null) {
            return BackendRuntimeValidationResult.failed(0, "backend_project_directory_missing");
        }
        try (GeneratedBackendRuntimeHandle handle = backendRuntime.start(backendProjectDirectory)) {
            if (handle == null) {
                return BackendRuntimeValidationResult.failed(
                        elapsedSince(startedAt), "backend_runtime_handle_missing");
            }
            GeneratedBackendRuntimeObservation observation = handle.observation();
            List<String> violations = new ArrayList<>(observation == null
                    ? List.of("backend_observation_missing")
                    : observation.violations());
            boolean processAlive = handle.processAlive();
            if (observation != null && observation.passedValidation() && !processAlive) {
                violations.add("backend_process_exited_after_health");
            }
            return new BackendRuntimeValidationResult(
                    handle.port(),
                    processAlive,
                    elapsedSince(startedAt),
                    COMMAND_SUMMARY,
                    violations);
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            log.warn("后端运行时验证执行失败: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
            return BackendRuntimeValidationResult.failed(
                    elapsedSince(startedAt), "backend_runtime_verifier_failed");
        }
    }

    private long elapsedSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
