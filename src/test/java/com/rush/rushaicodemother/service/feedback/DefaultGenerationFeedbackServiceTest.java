package com.rush.rushaicodemother.service.feedback;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.GenerationFeedback;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.vo.GenerationFeedbackVO;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackCommand;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignal;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignalPublisher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultGenerationFeedbackServiceTest {

    @Test
    void shouldUpsertFeedbackForOwnedTerminalTaskAndPublishSignal() {
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationFeedbackRepository feedbackRepository = mock(GenerationFeedbackRepository.class);
        GenerationFeedbackSignalPublisher signalPublisher = mock(GenerationFeedbackSignalPublisher.class);
        DefaultGenerationFeedbackService service =
                new DefaultGenerationFeedbackService(taskRepository, feedbackRepository, signalPublisher);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task("task-1", 10L, 7L,
                GenerationTaskStatus.SUCCESS)));
        when(feedbackRepository.upsert(any())).thenAnswer(invocation -> {
            GenerationFeedback feedback = invocation.getArgument(0);
            feedback.setId(99L);
            feedback.setUpdateTime(LocalDateTime.now());
            return feedback;
        });

        GenerationFeedbackVO result = service.submit(
                new GenerationFeedbackCommand("task-1", 5, "useful", " very useful "),
                User.builder().id(7L).build()
        );

        assertEquals(99L, result.id());
        assertEquals(10L, result.appId());
        assertEquals(5, result.rating());
        assertEquals("useful", result.outcome());
        assertEquals("very useful", result.comment());
        verify(feedbackRepository).upsert(any(GenerationFeedback.class));
        ArgumentCaptor<GenerationFeedbackSignal> signalCaptor =
                ArgumentCaptor.forClass(GenerationFeedbackSignal.class);
        verify(signalPublisher).publish(signalCaptor.capture());
        assertEquals("task-1", signalCaptor.getValue().taskId());
        assertEquals(10L, signalCaptor.getValue().appId());
        assertEquals(7L, signalCaptor.getValue().userId());
        assertEquals(GenerationTaskStatus.SUCCESS, signalCaptor.getValue().taskStatus());
        assertEquals(5, signalCaptor.getValue().rating());
        assertEquals("useful", signalCaptor.getValue().outcome());
    }

    @Test
    void shouldKeepSavedFeedbackWhenSignalPublisherFails() {
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationFeedbackRepository feedbackRepository = mock(GenerationFeedbackRepository.class);
        GenerationFeedbackSignalPublisher signalPublisher = mock(GenerationFeedbackSignalPublisher.class);
        DefaultGenerationFeedbackService service =
                new DefaultGenerationFeedbackService(taskRepository, feedbackRepository, signalPublisher);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task("task-1", 10L, 7L,
                GenerationTaskStatus.SUCCESS)));
        when(feedbackRepository.upsert(any())).thenAnswer(invocation -> {
            GenerationFeedback feedback = invocation.getArgument(0);
            feedback.setId(100L);
            return feedback;
        });
        doThrow(new IllegalStateException("memory unavailable"))
                .when(signalPublisher).publish(any(GenerationFeedbackSignal.class));

        GenerationFeedbackVO result = service.submit(
                new GenerationFeedbackCommand("task-1", 2, "broken", "build result is not usable"),
                User.builder().id(7L).build()
        );

        assertEquals(100L, result.id());
        verify(signalPublisher).publish(any(GenerationFeedbackSignal.class));
    }

    @Test
    void shouldRejectFeedbackForAnotherUserTask() {
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationFeedbackSignalPublisher signalPublisher = mock(GenerationFeedbackSignalPublisher.class);
        DefaultGenerationFeedbackService service = new DefaultGenerationFeedbackService(
                taskRepository, mock(GenerationFeedbackRepository.class), signalPublisher);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task("task-1", 10L, 8L,
                GenerationTaskStatus.SUCCESS)));

        assertThrows(BusinessException.class, () -> service.submit(
                new GenerationFeedbackCommand("task-1", 4, "useful", ""),
                User.builder().id(7L).build()
        ));
        verifyNoInteractions(signalPublisher);
    }

    @Test
    void shouldRejectFeedbackForNonTerminalTask() {
        DurableGenerationTaskRepository taskRepository = mock(DurableGenerationTaskRepository.class);
        GenerationFeedbackSignalPublisher signalPublisher = mock(GenerationFeedbackSignalPublisher.class);
        DefaultGenerationFeedbackService service = new DefaultGenerationFeedbackService(
                taskRepository, mock(GenerationFeedbackRepository.class), signalPublisher);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task("task-1", 10L, 7L,
                GenerationTaskStatus.RUNNING)));

        assertThrows(BusinessException.class, () -> service.submit(
                new GenerationFeedbackCommand("task-1", 4, "useful", ""),
                User.builder().id(7L).build()
        ));
        verifyNoInteractions(signalPublisher);
    }

    @Test
    void shouldRejectInvalidOutcomeLabel() {
        GenerationFeedbackSignalPublisher signalPublisher = mock(GenerationFeedbackSignalPublisher.class);
        DefaultGenerationFeedbackService service = new DefaultGenerationFeedbackService(
                mock(DurableGenerationTaskRepository.class),
                mock(GenerationFeedbackRepository.class),
                signalPublisher
        );

        assertThrows(BusinessException.class, () -> service.submit(
                new GenerationFeedbackCommand("task-1", 4, "../escape", ""),
                User.builder().id(7L).build()
        ));
        verifyNoInteractions(signalPublisher);
    }

    private DurableGenerationTaskRecord task(String taskId,
                                             Long appId,
                                             Long userId,
                                             GenerationTaskStatus status) {
        return new DurableGenerationTaskRecord(
                taskId,
                appId,
                userId,
                100L,
                "heavy",
                status,
                "completed",
                null,
                Instant.now(),
                Instant.now().plusSeconds(60),
                false,
                null,
                null,
                null,
                null,
                1,
                1,
                Instant.now(),
                null
        );
    }
}
