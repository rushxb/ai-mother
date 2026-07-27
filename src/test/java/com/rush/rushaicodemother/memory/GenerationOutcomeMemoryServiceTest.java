package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GenerationOutcomeMemoryServiceTest {

    @Test
    void durableOutboxMustBeTheOnlyWriterWhenMilvusAndOutboxAreEnabled() {
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        MilvusMemoryProperties longTermProperties = new MilvusMemoryProperties();
        longTermProperties.setEnabled(true);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        outboxProperties.setEnabled(true);
        GenerationOutcomeMemoryService service = new GenerationOutcomeMemoryService(
                semanticMemoryService, longTermProperties, outboxProperties);

        service.remember(request());

        verifyNoInteractions(semanticMemoryService);
    }

    @Test
    void disabledOutboxMustRetainTheDirectWriteFallback() {
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        MilvusMemoryProperties longTermProperties = new MilvusMemoryProperties();
        longTermProperties.setEnabled(true);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        outboxProperties.setEnabled(false);
        GenerationOutcomeMemoryService service = new GenerationOutcomeMemoryService(
                semanticMemoryService, longTermProperties, outboxProperties);

        service.remember(request());

        verifyDirectWrite(semanticMemoryService);
    }

    @Test
    void localMemoryModeMustRetainTheDirectWriteFallback() {
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        MilvusMemoryProperties longTermProperties = new MilvusMemoryProperties();
        longTermProperties.setEnabled(false);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        outboxProperties.setEnabled(true);
        GenerationOutcomeMemoryService service = new GenerationOutcomeMemoryService(
                semanticMemoryService, longTermProperties, outboxProperties);

        service.remember(request());

        verifyDirectWrite(semanticMemoryService);
    }

    @Test
    void failedTaskMustBecomeAFailureLesson() {
        GenerationOutcomeMemoryRequest failed = new GenerationOutcomeMemoryRequest(
                "task-2", 9L, 1L, 2L, GenerationTaskStatus.FAILED,
                "修复支付页面", "构建失败", "graph", "vue_project");

        GenerationOutcomeMemoryDocument document =
                GenerationOutcomeMemoryDocument.from(failed, "generation_task_outbox");

        assertEquals(MemoryType.FAILURE_LESSON, document.type());
        assertEquals("failed", document.metadata().get("taskStatus"));
    }

    @Test
    void documentBudgetMustPreserveBothRequestAndOutcomeWithoutBreakingUnicode() {
        String longPrompt = "需求😀".repeat(2_000);
        String longSummary = "结果😀".repeat(2_000);
        GenerationOutcomeMemoryRequest oversized = new GenerationOutcomeMemoryRequest(
                "task-3", 9L, 1L, 2L, GenerationTaskStatus.SUCCESS,
                longPrompt, longSummary, "graph", "vue_project");

        GenerationOutcomeMemoryDocument document =
                GenerationOutcomeMemoryDocument.from(oversized, "generation_task_outbox");

        assertTrue(document.content().startsWith("用户需求："));
        assertTrue(document.content().contains("\n执行结果："));
        assertTrue(document.content().endsWith("..."));
        assertTrue(document.content().codePointCount(0, document.content().length()) <= 7_520);
        assertFalse(Character.isHighSurrogate(
                document.content().charAt(document.content().length() - 4)));
    }

    private void verifyDirectWrite(GenerationSemanticMemoryService semanticMemoryService) {
        verify(semanticMemoryService).rememberAsync(
                9L,
                1L,
                2L,
                "task-1",
                MemoryType.TASK_OUTCOME,
                "用户需求：创建订单管理页面\n执行结果：构建通过",
                Map.of(
                        "source", "generation_completion_fallback",
                        "taskStatus", "success",
                        "orchestrationMode", "graph",
                        "targetType", "vue_project"
                )
        );
    }

    private GenerationOutcomeMemoryRequest request() {
        return new GenerationOutcomeMemoryRequest(
                "task-1",
                9L,
                1L,
                2L,
                GenerationTaskStatus.SUCCESS,
                "创建订单管理页面",
                "构建通过",
                "graph",
                "vue_project"
        );
    }
}
