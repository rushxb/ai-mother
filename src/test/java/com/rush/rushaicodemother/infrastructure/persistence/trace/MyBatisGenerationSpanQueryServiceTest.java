package com.rush.rushaicodemother.infrastructure.persistence.trace;

import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationSpanQueryServiceTest {

    @Test
    void shouldClampLimitAndMapDurableRows() {
        GenerationTaskSpanMapper mapper = mock(GenerationTaskSpanMapper.class);
        GenerationTaskSpan entity = GenerationTaskSpan.builder()
                .spanId("span-1")
                .taskId("task-1")
                .stage("build")
                .category("build")
                .status("success")
                .startedAt(LocalDateTime.of(2026, 7, 16, 4, 0))
                .endedAt(LocalDateTime.of(2026, 7, 16, 4, 0, 1))
                .durationMs(1_000L)
                .detail("ok")
                .build();
        when(mapper.selectByTaskId("task-1", GenerationSpanQueryService.MAX_LIMIT))
                .thenReturn(List.of(entity));
        MyBatisGenerationSpanQueryService service =
                new MyBatisGenerationSpanQueryService(mapper, ZoneOffset.UTC);

        var result = service.findByTaskId("task-1", 50_000);

        assertEquals(1, result.size());
        assertEquals("build", result.getFirst().category());
        assertEquals(1_000L, result.getFirst().durationMs());
        verify(mapper).selectByTaskId("task-1", GenerationSpanQueryService.MAX_LIMIT);
    }

    @Test
    void shouldRejectUnsafeTaskIdBeforeQueryingDatabase() {
        MyBatisGenerationSpanQueryService service = new MyBatisGenerationSpanQueryService(
                mock(GenerationTaskSpanMapper.class), ZoneOffset.UTC);

        assertThrows(IllegalArgumentException.class,
                () -> service.findByTaskId("../other-task", 10));
    }
}
