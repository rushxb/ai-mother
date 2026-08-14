package com.rush.rushaicodemother.orchestration.event;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationEventPublisherTest {

    @Test
    void shouldReplayRecentEventsAndStreamLiveEvents() throws InterruptedException {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);

        publisher.publish(request, GenerationEventType.TASK_ROUTE, "route selected", Map.of("route", "slot_fill"));

        assertEquals(1, publisher.recent(1L).size());
        List<GenerationEvent> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        publisher.stream(1L)
                .take(2)
                .subscribe(event -> {
                    received.add(event);
                    latch.countDown();
                });
        publisher.publish(request, GenerationEventType.TASK_DONE, "done", Map.of("status", "success"));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(GenerationEventType.TASK_ROUTE, received.get(0).type());
        assertEquals("slot_fill", received.get(0).data().get("route"));
        assertEquals(GenerationEventType.TASK_DONE, received.get(1).type());
        assertEquals("success", received.get(1).data().get("status"));
    }

    @Test
    void logsMustRedactSensitiveEventFieldsWithoutMutatingPublishedEvent() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);
        Logger logger = (Logger) LoggerFactory.getLogger(GenerationEventPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean originalAdditive = logger.isAdditive();
        appender.start();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            publisher.publish(
                    request,
                    GenerationEventType.TASK_FAILED,
                    "api-key=message-secret",
                    Map.of("reason", "registry-token=data-secret")
            );
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }

        String loggedEvent = appender.list.getFirst().getFormattedMessage();
        assertFalse(loggedEvent.contains("message-secret"));
        assertFalse(loggedEvent.contains("data-secret"));
        assertTrue(loggedEvent.contains("api-key=[REDACTED]"));
        assertTrue(loggedEvent.contains("registry-token=[REDACTED]"));
        assertEquals("api-key=message-secret", publisher.recent(1L).getFirst().message());
        assertEquals("registry-token=data-secret",
                publisher.recent(1L).getFirst().data().get("reason"));
    }

    @Test
    void shouldKeepReplayWindowBounded() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);

        for (int i = 0; i < 105; i++) {
            publisher.publish(request, GenerationEventType.INDEX_UPDATE, "event-" + i, Map.of("index", i));
        }

        assertEquals(100, publisher.recent(1L).size());
        assertEquals(5, publisher.recent(1L).getFirst().data().get("index"));
        List<GenerationEvent> streamedReplay = publisher.stream(1L)
                .take(100)
                .collectList()
                .block(Duration.ofSeconds(1));
        assertEquals(100, streamedReplay.size());
        assertEquals(5, streamedReplay.getFirst().data().get("index"));
    }

    @Test
    void safePublishMustNotInterruptWorkflowWhenEventDataFails() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> brokenData = mock(Map.class);
        when(brokenData.isEmpty()).thenThrow(new IllegalStateException("event_data_unavailable"));

        assertDoesNotThrow(() -> publisher.publishSafely(
                request, GenerationEventType.TASK_FAILED, "failed", brokenData));
        assertTrue(publisher.recent(1L).isEmpty());
    }

    @Test
    void terminalEventMustCloseLateStreamsAndReleaseTheLiveSink() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);
        publisher.stream(1L).subscribe();
        assertEquals(1, publisher.trackedSinkCount());

        publisher.publish(request, GenerationEventType.TASK_DONE, "done", Map.of("status", "success"));

        assertEquals(0, publisher.trackedSinkCount());
        List<GenerationEvent> replay = publisher.stream(1L).collectList().block();
        assertEquals(1, replay.size());
        assertEquals(GenerationEventType.TASK_DONE, replay.getFirst().type());
        assertEquals(0, publisher.trackedSinkCount());
    }

    @Test
    void idleReplayWindowMustExpireAndCloseItsLiveStream() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T08:00:00Z"));
        GenerationEventPublisher publisher = new GenerationEventPublisher(
                clock, Duration.ofMinutes(5), 10, 100);
        GenerationTaskRequest request = request(1L, 2L);
        AtomicBoolean streamCompleted = new AtomicBoolean(false);
        publisher.stream(1L).subscribe(
                ignored -> { },
                ignored -> { },
                () -> streamCompleted.set(true));
        publisher.publish(
                request, GenerationEventType.TASK_ROUTE,
                "route selected", Map.of("route", "slot_fill"));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertTrue(publisher.recent(1L).isEmpty());
        assertTrue(streamCompleted.get());
    }

    @Test
    void leastRecentlyUsedAppMustBeEvictedWhenGlobalCapacityIsReached() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T08:00:00Z"));
        GenerationEventPublisher publisher = new GenerationEventPublisher(
                clock, Duration.ofHours(1), 2, 100);
        AtomicBoolean evictedStreamCompleted = new AtomicBoolean(false);

        publisher.publish(
                request(1L, 1L), GenerationEventType.TASK_ROUTE,
                "app-1", Map.of("app", 1));
        publisher.stream(2L).subscribe(
                ignored -> { },
                ignored -> { },
                () -> evictedStreamCompleted.set(true));
        publisher.publish(
                request(2L, 2L), GenerationEventType.TASK_ROUTE,
                "app-2", Map.of("app", 2));
        assertEquals(1, publisher.recent(1L).size());

        publisher.publish(
                request(3L, 3L), GenerationEventType.TASK_ROUTE,
                "app-3", Map.of("app", 3));

        assertEquals(1, publisher.recent(1L).size());
        assertTrue(publisher.recent(2L).isEmpty());
        assertEquals(1, publisher.recent(3L).size());
        assertTrue(evictedStreamCompleted.get());
        assertEquals(2, publisher.trackedSinkCount());
    }

    @Test
    void stableEventIdMustMakeOutboxPublicationIdempotent() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationEvent event = new GenerationEvent(
                1L,
                2L,
                GenerationEventType.TASK_ROUTE,
                "route selected",
                Map.of("eventId", "terminal-effect:42", "route", "agent"),
                Instant.parse("2026-08-14T08:00:00Z"));

        publisher.publishIdempotently(event);
        publisher.publishIdempotently(event);

        assertEquals(1, publisher.recent(1L).size());
        assertEquals("terminal-effect:42", publisher.recent(1L).getFirst().data().get("eventId"));
    }

    private GenerationTaskRequest request(Long appId, Long userId) {
        App app = new App();
        app.setId(appId);
        User user = new User();
        user.setId(userId);
        return new GenerationTaskRequest(app, "生成一个应用", user);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
