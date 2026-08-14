package com.rush.rushaicodemother.orchestration.verification.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 已完成启动探测的后端运行时句柄；关闭即回收进程、沙箱、端口和副本。 */
public final class GeneratedBackendRuntimeHandle implements AutoCloseable {

    private static final Runnable NO_OP = () -> { };

    private final int port;
    private final GeneratedBackendRuntimeObservation observation;
    private final BooleanSupplier processAlive;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public GeneratedBackendRuntimeHandle(
            int port,
            GeneratedBackendRuntimeObservation observation,
            Runnable cleanup
    ) {
        this(port, observation, () -> true, cleanup);
    }

    public GeneratedBackendRuntimeHandle(
            int port,
            GeneratedBackendRuntimeObservation observation,
            BooleanSupplier processAlive,
            Runnable cleanup
    ) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("后端运行时端口无效");
        }
        this.port = port;
        this.observation = Objects.requireNonNull(observation, "后端运行时探测结果不能为空");
        this.processAlive = processAlive == null ? () -> false : processAlive;
        this.cleanup = cleanup == null ? NO_OP : cleanup;
    }

    public static GeneratedBackendRuntimeHandle failed(
            GeneratedBackendRuntimeObservation observation
    ) {
        return new GeneratedBackendRuntimeHandle(0, observation, NO_OP);
    }

    public int port() {
        return port;
    }

    public GeneratedBackendRuntimeObservation observation() {
        return observation;
    }

    public boolean healthy() {
        return port > 0 && observation.passedValidation();
    }

    public boolean processAlive() {
        return healthy() && processAlive.getAsBoolean();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }
}
