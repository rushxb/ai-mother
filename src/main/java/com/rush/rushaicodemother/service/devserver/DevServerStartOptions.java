package com.rush.rushaicodemother.service.devserver;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Task-scoped controls for a managed Dev Server startup.
 *
 * <p>The Dev Server module depends only on generic timeout and cancellation signals. Generation
 * runtime concerns are adapted by the validation service, keeping process lifecycle code reusable
 * for interactive preview sessions.</p>
 */
public record DevServerStartOptions(
        String taskId,
        Duration startupTimeout,
        BooleanSupplier cancellationRequested
) {

    public DevServerStartOptions {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Managed Dev Server task ID cannot be blank");
        }
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("Dev Server startup timeout must be greater than zero");
        }
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }

    boolean isCancellationRequested() {
        return cancellationRequested.getAsBoolean();
    }
}
