package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskSpanMapper;
import com.rush.rushaicodemother.model.entity.GenerationTaskSpan;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationDurationSamples;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationDurationSampleRepositoryTest {

    @Test
    void mustLoadBoundedTaskAndSpanSamplesThroughExplicitMappers() {
        GenerationTaskRuntimeMapper taskMapper = mock(GenerationTaskRuntimeMapper.class);
        GenerationTaskSpanMapper spanMapper = mock(GenerationTaskSpanMapper.class);
        when(taskMapper.selectRecentSuccessfulDurationsByRoute("heavy", 100))
                .thenReturn(List.of(1_000L, 2_000L));
        when(spanMapper.selectRecentSuccessfulByRoute("heavy", 2_000))
                .thenReturn(List.of(GenerationTaskSpan.builder()
                        .stage("build").category("BUILD").durationMs(700L).build()));
        MyBatisGenerationDurationSampleRepository repository =
                new MyBatisGenerationDurationSampleRepository(taskMapper, spanMapper);

        GenerationDurationSamples samples = repository.loadRecentSuccessfulSamples("heavy", 100, 2_000);

        assertEquals(List.of(1_000L, 2_000L), samples.taskDurationsMs());
        assertEquals(1, samples.stageDurations().size());
        assertEquals("build", samples.stageDurations().getFirst().stage());
        assertEquals(700L, samples.stageDurations().getFirst().durationMs());
        verify(taskMapper).selectRecentSuccessfulDurationsByRoute("heavy", 100);
        verify(spanMapper).selectRecentSuccessfulByRoute("heavy", 2_000);
    }

    @Test
    void unsafeRouteAndUnboundedLimitsMustBeRejected() {
        MyBatisGenerationDurationSampleRepository repository =
                new MyBatisGenerationDurationSampleRepository(
                        mock(GenerationTaskRuntimeMapper.class), mock(GenerationTaskSpanMapper.class));

        assertThrows(IllegalArgumentException.class,
                () -> repository.loadRecentSuccessfulSamples("../heavy", 100, 2_000));
        assertThrows(IllegalArgumentException.class,
                () -> repository.loadRecentSuccessfulSamples("heavy", 2_001, 2_000));
        assertThrows(IllegalArgumentException.class,
                () -> repository.loadRecentSuccessfulSamples("heavy", 100, 20_001));
    }
}
