package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationSessionTest {

    @Test
    void emittedEventsAndCompletionMustReachSharedTaskTransport() {
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        when(executionContext.taskId()).thenReturn("task-shared-stream");
        GenerationEventStream eventStream = mock(GenerationEventStream.class);
        GenerationSession session = new GenerationSession(null, executionContext, eventStream);
        GenerationStreamEvent first = GenerationStreamEvent.aiDelta("hello");
        GenerationStreamEvent second = GenerationStreamEvent.aiDelta("world");

        session.emit(first);
        session.emit(second);
        session.complete();

        verify(eventStream).publish("task-shared-stream", first);
        verify(eventStream).publish("task-shared-stream", second);
        verify(eventStream).complete("task-shared-stream");
    }

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
