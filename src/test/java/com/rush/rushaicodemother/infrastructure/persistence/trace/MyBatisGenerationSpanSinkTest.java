package com.rush.rushaicodemother.infrastructure.persistence.trace;

import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MyBatisGenerationSpanSinkTest {

    @Test
    void shouldMapObservationUsingConfiguredDatabaseZone() {
        GenerationTaskSpanMapper mapper = mock(GenerationTaskSpanMapper.class);
        MyBatisGenerationSpanSink sink = new MyBatisGenerationSpanSink(mapper, ZoneOffset.UTC);
        Instant startedAt = Instant.parse("2026-07-16T04:00:00Z");
        Instant endedAt = startedAt.plusMillis(125);
        GenerationSpanObservation observation = new GenerationSpanObservation(
                "e106ce55-03ab-4fe2-a917-bcdd60bd2348",
                "task-1",
                "llm_generation",
                GenerationSpanCategory.MODEL,
                "success",
                startedAt,
                endedAt,
                125,
                "model=router"
        );

        sink.record(observation);

        ArgumentCaptor<GenerationTaskSpan> captor = ArgumentCaptor.forClass(GenerationTaskSpan.class);
        verify(mapper).insertSpan(captor.capture());
        GenerationTaskSpan entity = captor.getValue();
        assertEquals(observation.spanId(), entity.getSpanId());
        assertEquals("model", entity.getCategory());
        assertEquals(startedAt, entity.getStartedAt().toInstant(ZoneOffset.UTC));
        assertEquals(endedAt, entity.getEndedAt().toInstant(ZoneOffset.UTC));
        assertEquals(125L, entity.getDurationMs());
        assertEquals(0, entity.getIsDelete());
    }
}
