package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSessionTest {

    @Test
    void cancelInvokesRegisteredHandleOnlyOnce() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger cancellations = new AtomicInteger();

        session.setCancellationHandle(cancellations::incrementAndGet);
        session.cancel();
        session.cancel();

        assertEquals(1, cancellations.get());
    }

    @Test
    void registeringHandleAfterCancellationCancelsItImmediately() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger cancellations = new AtomicInteger();

        session.cancel();
        session.setCancellationHandle(cancellations::incrementAndGet);

        assertEquals(1, cancellations.get());
    }

    @Test
    void cancellationTargetsMostRecentlyRegisteredHandle() {
        GenerationSession session = new GenerationSession(null);
        AtomicInteger previousCancellations = new AtomicInteger();
        AtomicInteger currentCancellations = new AtomicInteger();

        session.setCancellationHandle(previousCancellations::incrementAndGet);
        session.setCancellationHandle(currentCancellations::incrementAndGet);
        session.cancel();

        assertEquals(0, previousCancellations.get());
        assertEquals(1, currentCancellations.get());
    }
    @Test
    void terminalCompletionCanBeClaimedOnlyOnce() {
        GenerationSession session = new GenerationSession(null);

        assertTrue(session.tryBeginCompletion());
        assertFalse(session.tryBeginCompletion());
        assertFalse(session.isActive());
    }

    @Test
    void taskRequestCanBeRecoveredByStopDrivenTerminalization() {
        GenerationSession session = new GenerationSession(null);
        App app = new App();
        app.setId(11L);
        User user = new User();
        user.setId(22L);
        GenerationTaskRequest request = new GenerationTaskRequest(app, "prompt", user);

        session.bindTaskRequest(request);

        assertSame(request, session.taskRequest());
    }

}
