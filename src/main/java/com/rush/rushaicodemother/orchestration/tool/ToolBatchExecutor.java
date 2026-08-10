package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * 按分段执行一轮工具批次：只读分段并发，其余串行。
 *
 * <p>只承担「并发执行并把结果还原为原始顺序」这一职责，分段决策属于
 * {@link ToolBatchExecutionPlanner}，单次工具执行语义（埋点、审批、错误处理）仍由调用方注入。</p>
 *
 * <p>{@link MonitorContext} 是 ThreadLocal，不会自动传播到工作线程。若不显式安装，
 * 并发执行的工具将丢失 userId/appId/taskId 归因，token 计量与成本归属随之失真，
 * 因此每个工作线程执行前都会安装快照、执行后恢复原值。</p>
 */
@Slf4j
@Component
public class ToolBatchExecutor {

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("agent-tool-batch-", 0).factory());

    /**
     * 按分段顺序执行工具请求，返回与原始批次顺序一致的结果。
     *
     * <p>任一工具抛出异常时（例如审批挂起、栅栏失效），异常向调用方原样传播，
     * 与串行执行的失败语义保持一致；同一并发分段内其余请求的结果被丢弃，
     * 因为该轮工具循环已经无法继续推进。</p>
     *
     * @param segments 分段列表，需按顺序执行
     * @param totalRequests 本轮工具请求总数，用于按序回填结果
     * @param action 单个工具请求的执行动作
     * @return 按原始批次序号排列的执行结果
     */
    public List<ToolExecutionResult> execute(
            List<ToolBatchSegment> segments,
            int totalRequests,
            Function<IndexedToolRequest, ToolExecutionResult> action
    ) {
        Objects.requireNonNull(segments, "工具分段列表不能为空");
        Objects.requireNonNull(action, "工具执行动作不能为空");
        if (totalRequests < 0) {
            throw new IllegalArgumentException("工具请求总数不能为负");
        }
        List<ToolExecutionResult> ordered = new ArrayList<>(totalRequests);
        for (int slot = 0; slot < totalRequests; slot++) {
            ordered.add(null);
        }
        for (ToolBatchSegment segment : segments) {
            if (segment.concurrent()) {
                executeConcurrently(segment, ordered, action);
            } else {
                IndexedToolRequest only = segment.requests().getFirst();
                ordered.set(only.index(), action.apply(only));
            }
        }
        return ordered;
    }

    /** 并发执行只读分段；任一失败原样抛出，不吞异常。 */
    private void executeConcurrently(
            ToolBatchSegment segment,
            List<ToolExecutionResult> ordered,
            Function<IndexedToolRequest, ToolExecutionResult> action
    ) {
        MonitorContext capturedContext = copyContext(MonitorContextHolder.getContext());
        List<CompletableFuture<Void>> pending = new ArrayList<>(segment.requests().size());
        try {
            for (IndexedToolRequest indexed : segment.requests()) {
                pending.add(CompletableFuture.runAsync(
                        () -> ordered.set(indexed.index(), invokeWithContext(
                                indexed, capturedContext, action)),
                        executor));
            }
        } catch (RejectedExecutionException rejected) {
            // 执行器已关闭（应用停机）时退化为串行，保证当前请求仍能完成。
            log.warn("工具并发执行器不可用，本轮只读工具退化为串行执行");
            pending.forEach(future -> future.cancel(true));
            for (IndexedToolRequest indexed : segment.requests()) {
                if (ordered.get(indexed.index()) == null) {
                    ordered.set(indexed.index(), action.apply(indexed));
                }
            }
            return;
        }
        awaitAll(pending);
    }

    /** 等待全部并发任务；解包 CompletionException 以保持调用方原有异常契约。 */
    private void awaitAll(List<CompletableFuture<Void>> pending) {
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException completionFailure) {
            Throwable cause = completionFailure.getCause();
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            if (cause instanceof Error errorCause) {
                throw errorCause;
            }
            throw completionFailure;
        }
    }

    /** 在工作线程安装归因上下文后执行工具，结束后恢复线程原有上下文。 */
    private ToolExecutionResult invokeWithContext(
            IndexedToolRequest indexed,
            MonitorContext capturedContext,
            Function<IndexedToolRequest, ToolExecutionResult> action
    ) {
        MonitorContext previousContext = MonitorContextHolder.getContext();
        installContext(capturedContext);
        try {
            return action.apply(indexed);
        } finally {
            installContext(previousContext);
        }
    }

    private static MonitorContext copyContext(MonitorContext context) {
        if (context == null) {
            return null;
        }
        return MonitorContext.builder()
                .userId(context.getUserId())
                .appId(context.getAppId())
                .taskId(context.getTaskId())
                .build();
    }

    private static void installContext(MonitorContext context) {
        if (context == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(context);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
