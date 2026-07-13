package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerOutputPumpTest {

    @Test
    void shouldDecodeUtf8AcrossBuffersAndSplitCrLfAndLf() throws Exception {
        String longPrefix = "x".repeat(1023);
        String output = longPrefix + "\u4E2D\r\n\u4E2D\u6587\n\u7ED3\u675F";
        FakeProcess process = new FakeProcess(output.getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();

        CompletableFuture<Void> completion = new DevServerOutputPump(2000)
                .start(process, "test", lines::add);
        completion.get(1, TimeUnit.SECONDS);

        assertEquals(List.of(longPrefix + "\u4E2D", "\u4E2D\u6587", "\u7ED3\u675F"), lines);
        assertTrue(lines.stream().noneMatch(line -> line.contains("\uFFFD")));
    }

    @Test
    void shouldBoundLongLineAndDeliverSameBoundedValue() throws Exception {
        FakeProcess process = new FakeProcess("abcdefghijklmnopqrstuvwxyz\n".getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();

        new DevServerOutputPump(12)
                .start(process, "test", lines::add)
                .get(1, TimeUnit.SECONDS);

        assertEquals(1, lines.size());
        assertEquals(12, lines.getFirst().length());
    }

    @Test
    void consumerFailureMustNotBreakOutputDrain() throws Exception {
        FakeProcess process = new FakeProcess("first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        List<String> attempted = new ArrayList<>();

        CompletableFuture<Void> completion = new DevServerOutputPump(100)
                .start(process, "test", line -> {
                    attempted.add(line);
                    throw new IllegalStateException("collector failed");
                });

        completion.get(1, TimeUnit.SECONDS);
        assertEquals(List.of("first", "second"), attempted);
    }

    @Test
    void awaitCompletionMustPreserveExistingInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            DevServerOutputPump.awaitCompletionPreservingInterrupt(
                    new CompletableFuture<>(),
                    Duration.ofMillis(10)
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldRejectInvalidConstructionAndNullStartArguments() {
        assertThrows(IllegalArgumentException.class, () -> new DevServerOutputPump(0));
        DevServerOutputPump pump = new DevServerOutputPump(10);
        assertThrows(NullPointerException.class, () -> pump.start(null, "test", line -> { }));
        assertThrows(NullPointerException.class,
                () -> pump.start(new FakeProcess(new byte[0]), "test", null));
    }

    private static final class FakeProcess extends Process {

        private final InputStream inputStream;

        private FakeProcess(byte[] output) {
            this.inputStream = new ByteArrayInputStream(output);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public long pid() {
            return 4242L;
        }
    }
}
