package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 用于托管开发服务器启动的任务范围控件。
 *
 * <p>Dev Server 模块仅依赖于通用超时和取消信号。一代
 * 运行时问题由验证服务进行调整，保持流程生命周期代码可重用
 * 用于交互式预览会话。</p>
 */
public record DevServerStartOptions(
        String taskId,
        Duration startupTimeout,
        BooleanSupplier cancellationRequested,
        GenerationExecutionFence executionFence,
        Map<String, String> environmentOverrides
) {

    public DevServerStartOptions(String taskId,
                                 Duration startupTimeout,
                                 BooleanSupplier cancellationRequested) {
        this(taskId, startupTimeout, cancellationRequested, null, Map.of());
    }

    public DevServerStartOptions(
            String taskId,
            Duration startupTimeout,
            BooleanSupplier cancellationRequested,
            GenerationExecutionFence executionFence
    ) {
        this(taskId, startupTimeout, cancellationRequested, executionFence, Map.of());
    }

    public DevServerStartOptions {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Managed Dev Server task ID cannot be blank");
        }
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("Dev Server startup timeout must be greater than zero");
        }
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
        environmentOverrides = validateEnvironmentOverrides(environmentOverrides);
    }

    boolean isCancellationRequested() {
        return cancellationRequested.getAsBoolean();
    }

    private static Map<String, String> validateEnvironmentOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        if (overrides.size() != 1 || !overrides.containsKey("VITE_API_BASE_URL")) {
            throw new IllegalArgumentException("Dev Server 仅允许覆盖 VITE_API_BASE_URL");
        }
        String value = overrides.get("VITE_API_BASE_URL");
        if (!isSafeLoopbackApiBase(value)) {
            throw new IllegalArgumentException("VITE_API_BASE_URL 必须是受控回环地址");
        }
        return Map.of("VITE_API_BASE_URL", value.trim());
    }

    private static boolean isSafeLoopbackApiBase(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() >= 1
                    && uri.getPort() <= 65_535
                    && "/api".equals(uri.getPath())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
