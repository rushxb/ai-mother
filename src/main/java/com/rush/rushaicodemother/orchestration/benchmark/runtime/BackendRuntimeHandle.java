package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 已完成启动探测的后端运行时句柄，关闭时统一回收进程、沙箱、端口和副本。 */
public final class BackendRuntimeHandle implements AutoCloseable {

    private static final Runnable NO_OP = () -> { };

    private final int port;
    private final BackendRuntimeObservation observation;
    private final BooleanSupplier processAlive;
    private final Runnable cleanup;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BackendRuntimeHandle(
            int port,
            BackendRuntimeObservation observation,
            Runnable cleanup
    ) {
        this(port, observation, () -> true, cleanup);
    }

    /**
 * 创建后端运行时句柄实例并完成必要的依赖和初始状态设置。
 *
 * @param port 端口
 * @param observation 观测
 * @param processAlive 进程存活状态探测器
 * @param cleanup 资源清理回调
 */
    public BackendRuntimeHandle(
            int port,
            BackendRuntimeObservation observation,
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

    public static BackendRuntimeHandle failed(BackendRuntimeObservation observation) {
        return new BackendRuntimeHandle(0, observation, NO_OP);
    }

    public int port() {
        return port;
    }

    public BackendRuntimeObservation observation() {
        return observation;
    }

    public boolean healthy() {
        return port > 0 && observation.passedValidation();
    }

    /**
 * 处理{@code Alive}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean processAlive() {
        return healthy() && processAlive.getAsBoolean();
    }

    /** 关闭后端运行时句柄并释放资源。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanup.run();
        }
    }
}
