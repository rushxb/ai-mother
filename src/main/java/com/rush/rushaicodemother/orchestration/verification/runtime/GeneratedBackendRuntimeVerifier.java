package com.rush.rushaicodemother.orchestration.verification.runtime;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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

    private final GeneratedBackendRuntime backendRuntime;

    public GeneratedBackendRuntimeVerifier(GeneratedBackendRuntime backendRuntime) {
        this.backendRuntime = backendRuntime;
    }

    public BackendRuntimeValidationResult verify(Path backendProjectDirectory) {
        return verify(GeneratedBackendRuntimeRequest.unmanaged(backendProjectDirectory));
    }

    /** 执行任务范围的后端运行时验证，并保留取消异常语义。 */
    public BackendRuntimeValidationResult verify(GeneratedBackendRuntimeRequest request) {
        long startedAt = System.nanoTime();
        if (request == null || request.projectDirectory() == null) {
            return BackendRuntimeValidationResult.failed(0, "backend_project_directory_missing");
        }
        try (GeneratedBackendRuntimeHandle handle = backendRuntime.start(request)) {
            if (handle == null) {
                return BackendRuntimeValidationResult.failed(
                        elapsedSince(startedAt), "backend_runtime_handle_missing");
            }
            return BackendRuntimeValidationResult.observe(
                    handle,
                    elapsedSince(startedAt)
            );
        } catch (GenerationExecutionPolicyException exception) {
            throw exception;
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
