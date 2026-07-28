package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 聚合一次模型调用链中的逻辑请求、容量租约和传输层取消句柄。 */
@Slf4j
public final class GenerationModelCancellationScope implements GenerationCancellationHandle {

    public static final String INVOCATION_PARAMETER =
            GenerationModelCancellationScope.class.getName();

    private static final int ACTIVE = 0;
    private static final int CANCELLED = 1;
    private static final int COMPLETED = 2;

    private final AtomicInteger state = new AtomicInteger(ACTIVE);
    private final Set<GenerationCancellationHandle> handles = ConcurrentHashMap.newKeySet();

    /**
 * 注册生成模型{@code Cancellation}作用域。
 *
 * @param handle 句柄
 */
    public void register(GenerationCancellationHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("模型取消句柄不能为空");
        }
        int current = state.get();
        if (current == CANCELLED) {
            cancelSafely(handle);
            return;
        }
        if (current == COMPLETED) {
            return;
        }
        handles.add(handle);
        current = state.get();
        if (current != ACTIVE && handles.remove(handle) && current == CANCELLED) {
            cancelSafely(handle);
        }
    }

    /** 取消生成模型{@code Cancellation}作用域。 */
    @Override
    public void cancel() {
        if (!state.compareAndSet(ACTIVE, CANCELLED)) {
            return;
        }
        handles.forEach(this::cancelSafely);
        handles.clear();
    }

    /** 完成生成模型{@code Cancellation}作用域并持久化终态。 */
    public void complete() {
        if (state.compareAndSet(ACTIVE, COMPLETED)) {
            handles.clear();
        }
    }

    public boolean isCancelled() {
        return state.get() == CANCELLED;
    }

    /** 取消安全处理。 */
    private void cancelSafely(GenerationCancellationHandle handle) {
        try {
            handle.cancel();
        } catch (RuntimeException failure) {
            log.warn("模型取消句柄执行失败: {}", LogExceptionSanitizer.sanitize(failure));
        }
    }
}
