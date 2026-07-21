package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SemanticGenerationFeedbackSignalPublisherTest {

    @Test
    void shouldWriteUserFeedbackSignalToSemanticMemory() {
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        SemanticGenerationFeedbackSignalPublisher publisher =
                new SemanticGenerationFeedbackSignalPublisher(semanticMemoryService);

        publisher.publish(new GenerationFeedbackSignal(
                "task-1",
                10L,
                7L,
                GenerationTaskStatus.SUCCESS,
                5,
                "useful",
                "The generated dashboard matched my request."
        ));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(semanticMemoryService).rememberAsync(
                eq(10L),
                eq(7L),
                eq("task-1"),
                eq(MemoryType.USER_FEEDBACK),
                contentCaptor.capture(),
                metadataCaptor.capture()
        );
        assertTrue(contentCaptor.getValue().contains("Rating: 5/5"));
        assertTrue(contentCaptor.getValue().contains("generated dashboard"));
        assertEquals("generation_feedback", metadataCaptor.getValue().get("signalSource"));
        assertEquals("positive", metadataCaptor.getValue().get("ratingBucket"));
        assertEquals(false, metadataCaptor.getValue().get("improvementCandidate"));
    }

    @Test
    void lowRatingShouldBeMarkedAsImprovementCandidate() {
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        SemanticGenerationFeedbackSignalPublisher publisher =
                new SemanticGenerationFeedbackSignalPublisher(semanticMemoryService);

        publisher.publish(new GenerationFeedbackSignal(
                "task-2",
                10L,
                7L,
                GenerationTaskStatus.SUCCESS,
                2,
                "broken",
                "Build failed after generation."
        ));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(semanticMemoryService).rememberAsync(
                eq(10L),
                eq(7L),
                eq("task-2"),
                eq(MemoryType.USER_FEEDBACK),
                contentCaptor.capture(),
                metadataCaptor.capture()
        );
        assertTrue(contentCaptor.getValue().contains("Improvement candidate: true"));
        assertEquals("negative", metadataCaptor.getValue().get("ratingBucket"));
        assertEquals(true, metadataCaptor.getValue().get("improvementCandidate"));
    }
}
