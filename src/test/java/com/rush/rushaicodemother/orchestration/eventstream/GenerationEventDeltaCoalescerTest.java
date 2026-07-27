package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.config.GenerationEventStreamProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationEventStreamMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationEventDeltaCoalescerTest {

    private static final String TASK_ID = "task-delta";

    @Test
    void firstDeltaMustBeWrittenImmediately() {
        RecordingWriter writer = new RecordingWriter();

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofSeconds(1), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("首段"));

            assertEquals(List.of("首段"), writer.eventTexts());
        }
    }

    @Test
    void adjacentDeltasMustBeMergedBeforeOrderingBarrier() {
        RecordingWriter writer = new RecordingWriter();

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofSeconds(1), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("A"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("B"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("C"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.toolCall("工具调用", Map.of("name", "writeFile")));

            assertEquals(List.of("A", "BC", "工具调用"), writer.eventTexts());
            assertEquals(List.of(
                    GenerationStreamEvent.AI_DELTA,
                    GenerationStreamEvent.AI_DELTA,
                    GenerationStreamEvent.TOOL_CALL
            ), writer.eventTypes());
        }
    }

    @Test
    void completionMustFlushPendingDeltaBeforeTerminalRecord() {
        RecordingWriter writer = new RecordingWriter();

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofSeconds(1), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("A"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("B"));
            coalescer.complete(TASK_ID);

            assertEquals(List.of("A", "B"), writer.eventTexts());
            assertEquals(1, writer.completionCount());
            assertTrue(writer.writes().getLast().complete());
        }
    }

    @Test
    void timeWindowMustFlushBufferedDeltaWithoutAnotherEvent() throws Exception {
        RecordingWriter writer = new RecordingWriter();

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofMillis(20), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("A"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("B"));

            await(() -> writer.eventTexts().size() == 2);

            assertEquals(List.of("A", "B"), writer.eventTexts());
        }
    }

    @Test
    void characterThresholdMustBoundTheBufferAndFlushSynchronously() {
        RecordingWriter writer = new RecordingWriter();
        String firstHalf = "甲".repeat(32);
        String secondHalf = "乙".repeat(32);

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofSeconds(1), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("首段"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta(firstHalf));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta(secondHalf));

            assertEquals(List.of("首段", firstHalf + secondHalf), writer.eventTexts());
        }
    }

    @Test
    void exhaustedAsyncRetriesMustKeepContentForTheNextBarrier() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        writer.failDeltaText("待重试", 4);

        try (GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofMillis(10), 64)) {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("首段"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("待重试"));

            await(() -> writer.attemptsFor("待重试") == 4);
            assertEquals(List.of("首段"), writer.eventTexts());

            coalescer.publish(TASK_ID, GenerationStreamEvent.generationStage("进入构建", Map.of()));

            assertEquals(5, writer.attemptsFor("待重试"));
            assertEquals(List.of("首段", "待重试", "进入构建"), writer.eventTexts());
        }
    }

    @Test
    void closeMustFlushPendingDeltaExactlyOnce() {
        RecordingWriter writer = new RecordingWriter();
        GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofSeconds(1), 64);
        coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("A"));
        coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("B"));

        coalescer.close();
        coalescer.close();

        assertEquals(List.of("A", "B"), writer.eventTexts());
    }

    @Test
    void timerFlushAndConcurrentCompletionMustRemainOrdered() throws Exception {
        CountDownLatch flushStarted = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        CountDownLatch completionEnteredWriter = new CountDownLatch(1);
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        GenerationEventDeltaCoalescer.EventWriter writer = new GenerationEventDeltaCoalescer.EventWriter() {
            @Override
            public void publish(String taskId, GenerationStreamEvent event) {
                if ("B".equals(event.getText())) {
                    flushStarted.countDown();
                    awaitLatch(releaseFlush);
                }
                order.add(event.getText());
            }

            @Override
            public void complete(String taskId) {
                completionEnteredWriter.countDown();
                order.add("完成");
            }
        };
        GenerationEventDeltaCoalescer coalescer = coalescer(writer, Duration.ofMillis(10), 64);

        try {
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("A"));
            coalescer.publish(TASK_ID, GenerationStreamEvent.aiDelta("B"));
            assertTrue(flushStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> coalescer.complete(TASK_ID));
            assertFalse(completionEnteredWriter.await(100, TimeUnit.MILLISECONDS));

            releaseFlush.countDown();
            completion.get(2, TimeUnit.SECONDS);
            assertEquals(List.of("A", "B", "完成"), order);
        } finally {
            releaseFlush.countDown();
            coalescer.close();
        }
    }

    @Test
    void releasedStateSlotMustBeReusableAfterTaskCompletion() {
        RecordingWriter writer = new RecordingWriter();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        properties.setMaxTrackedTasks(10);
        properties.setDeltaFlushInterval(Duration.ofSeconds(1));
        properties.setDeltaMaxChars(64);

        try (GenerationEventDeltaCoalescer coalescer = new GenerationEventDeltaCoalescer(
                properties,
                writer,
                new GenerationEventStreamMetricsCollector(registry))) {
            for (int index = 0; index < 10; index++) {
                coalescer.publish("task-" + index, GenerationStreamEvent.aiDelta("A"));
            }
            coalescer.publish("task-overflow", GenerationStreamEvent.aiDelta("B"));
            coalescer.complete("task-0");
            coalescer.publish("task-reused", GenerationStreamEvent.aiDelta("C"));

            assertEquals(1, registry.find("generation_event_stream_delta_inputs_total")
                    .tag("disposition", "capacity_bypass")
                    .counter()
                    .count(), 0.001);
            assertEquals(11, registry.find("generation_event_stream_delta_inputs_total")
                    .tag("disposition", "immediate")
                    .counter()
                    .count(), 0.001);
        }
    }

    private GenerationEventDeltaCoalescer coalescer(GenerationEventDeltaCoalescer.EventWriter writer,
                                                     Duration flushInterval,
                                                     int maxChars) {
        GenerationEventStreamProperties properties = new GenerationEventStreamProperties();
        properties.setDeltaFlushInterval(flushInterval);
        properties.setDeltaMaxChars(maxChars);
        return new GenerationEventDeltaCoalescer(
                properties,
                writer,
                new GenerationEventStreamMetricsCollector(new SimpleMeterRegistry())
        );
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待测试冲刷信号时被中断", interrupted);
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("等待异步 Delta 冲刷超时");
    }

    private record Write(GenerationStreamEvent event, boolean complete) {
    }

    private static final class RecordingWriter implements GenerationEventDeltaCoalescer.EventWriter {

        private final CopyOnWriteArrayList<Write> writes = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicInteger matchingAttempts = new AtomicInteger();
        private volatile String failingText;

        @Override
        public void publish(String taskId, GenerationStreamEvent event) {
            if (failingText != null && failingText.equals(event.getText())) {
                matchingAttempts.incrementAndGet();
                int remaining = failuresRemaining.getAndUpdate(current -> Math.max(0, current - 1));
                if (remaining > 0) {
                    throw new IllegalStateException("模拟 Redis 写入失败");
                }
            }
            writes.add(new Write(event, false));
        }

        @Override
        public void complete(String taskId) {
            writes.add(new Write(null, true));
        }

        private void failDeltaText(String text, int failureCount) {
            failingText = text;
            failuresRemaining.set(failureCount);
        }

        private int attemptsFor(String text) {
            return text.equals(failingText) ? matchingAttempts.get() : 0;
        }

        private List<String> eventTexts() {
            return writes.stream()
                    .filter(write -> !write.complete())
                    .map(Write::event)
                    .map(GenerationStreamEvent::getText)
                    .toList();
        }

        private List<String> eventTypes() {
            return writes.stream()
                    .filter(write -> !write.complete())
                    .map(Write::event)
                    .map(GenerationStreamEvent::getType)
                    .toList();
        }

        private long completionCount() {
            return writes.stream().filter(Write::complete).count();
        }

        private List<Write> writes() {
            return List.copyOf(writes);
        }
    }
}
