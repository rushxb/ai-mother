package com.rush.rushaicodemother.orchestration.event;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldKeepReplayWindowBounded() {
        GenerationEventPublisher publisher = new GenerationEventPublisher();
        GenerationTaskRequest request = request(1L, 2L);

        for (int i = 0; i < 105; i++) {
            publisher.publish(request, GenerationEventType.INDEX_UPDATE, "event-" + i, Map.of("index", i));
        }

        assertEquals(100, publisher.recent(1L).size());
        assertEquals(5, publisher.recent(1L).getFirst().data().get("index"));
    }

    private GenerationTaskRequest request(Long appId, Long userId) {
        App app = new App();
        app.setId(appId);
        User user = new User();
        user.setId(userId);
        return new GenerationTaskRequest(app, "生成一个应用", user);
    }
}
