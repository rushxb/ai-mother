package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具批次执行的顺序、并发与归因上下文语义。 */
class ToolBatchExecutorTest {

    private final ToolBatchExecutor executor = new ToolBatchExecutor();

    @AfterEach
    void tearDown() {
        MonitorContextHolder.clearContext();
    }

    @Test
    void resultsMustBeReturnedInOriginalRequestOrderDespiteCompletionOrder() {
        List<IndexedToolRequest> requests = List.of(
                indexed(0, "slow"), indexed(1, "fast"), indexed(2, "medium"));

        // 故意让先请求的先睡最久，完成顺序与请求顺序相反。
        List<ToolExecutionResult> results = executor.execute(
                List.of(ToolBatchSegment.concurrent(requests)),
                3,
                request -> {
                    long sleepMillis = switch (request.index()) {
                        case 0 -> 120L;
                        case 2 -> 60L;
                        default -> 0L;
                    };
                    sleep(sleepMillis);
                    return result(request.request().name());
                });

        assertEquals(List.of("slow", "fast", "medium"),
                results.stream().map(ToolExecutionResult::resultText).toList());
    }

    @Test
    void concurrentSegmentMustExecuteInParallelRatherThanSerially() {
        int toolCount = 4;
        CountDownLatch allStarted = new CountDownLatch(toolCount);
        List<IndexedToolRequest> requests = List.of(
                indexed(0, "a"), indexed(1, "b"), indexed(2, "c"), indexed(3, "d"));

        // 每个工具都必须等到全部工具都已开始才返回；串行执行会在此死锁并超时。
        assertTimeout(Duration.ofSeconds(10), () -> executor.execute(
                List.of(ToolBatchSegment.concurrent(requests)),
                toolCount,
                request -> {
                    allStarted.countDown();
                    try {
                        assertTrue(allStarted.await(5, TimeUnit.SECONDS),
                                "只读工具未并发执行");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return result(request.request().name());
                }));
    }

    @Test
    void monitorContextMustPropagateToConcurrentWorkerThreads() {
        MonitorContextHolder.setContext(MonitorContext.builder()
                .userId("user-1").appId("app-2").taskId("task-3").build());
        Set<String> observedTaskIds = ConcurrentHashMap.newKeySet();

        executor.execute(
                List.of(ToolBatchSegment.concurrent(List.of(indexed(0, "a"), indexed(1, "b")))),
                2,
                request -> {
                    MonitorContext context = MonitorContextHolder.getContext();
                    observedTaskIds.add(context == null ? "missing" : context.getTaskId());
                    return result(request.request().name());
                });

        assertEquals(Set.of("task-3"), observedTaskIds);
    }

    @Test
    void sequentialSegmentsMustRunStrictlyInOrder() {
        AtomicInteger sequence = new AtomicInteger();
        List<Integer> executionOrder = new java.util.ArrayList<>();

        executor.execute(
                List.of(
                        ToolBatchSegment.sequential(indexed(0, "first")),
                        ToolBatchSegment.sequential(indexed(1, "second"))),
                2,
                request -> {
                    executionOrder.add(request.index());
                    sequence.incrementAndGet();
                    return result(request.request().name());
                });

        assertEquals(List.of(0, 1), executionOrder);
        assertEquals(2, sequence.get());
    }

    @Test
    void toolFailureInConcurrentSegmentMustPropagateToCaller() {
        assertThrows(IllegalStateException.class, () -> executor.execute(
                List.of(ToolBatchSegment.concurrent(List.of(indexed(0, "a"), indexed(1, "b")))),
                2,
                request -> {
                    if (request.index() == 1) {
                        throw new IllegalStateException("工具执行失败");
                    }
                    return result(request.request().name());
                }));
    }

    private IndexedToolRequest indexed(int index, String name) {
        return new IndexedToolRequest(index, ToolExecutionRequest.builder()
                .id(String.valueOf(index)).name(name).arguments("{}").build());
    }

    private ToolExecutionResult result(String text) {
        return ToolExecutionResult.builder().resultText(text).build();
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
