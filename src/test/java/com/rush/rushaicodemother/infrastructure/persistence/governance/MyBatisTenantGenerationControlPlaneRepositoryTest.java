package com.rush.rushaicodemother.infrastructure.persistence.governance;

import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.TenantGenerationControlPlaneMapper;
import com.rush.rushaicodemother.mapper.TenantGenerationQueueRow;
import com.rush.rushaicodemother.mapper.TenantGenerationScenarioCostRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyBatisTenantGenerationControlPlaneRepositoryTest {

    @Test
    void repositoryMustLoadStructuredBudgetQueueAndUnitSuccessCostFacts() {
        GenerationTaskRuntimeMapper runtimeMapper = mock(GenerationTaskRuntimeMapper.class);
        TenantGenerationControlPlaneMapper controlPlaneMapper = mock(TenantGenerationControlPlaneMapper.class);
        LocalDateTime periodStart = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime observedBefore = LocalDateTime.of(2026, 8, 28, 12, 0);
        when(runtimeMapper.sumTenantGenerationCreditUsage(100L, periodStart, observedBefore))
                .thenReturn(900L);
        when(controlPlaneMapper.selectQueueSummary(100L))
                .thenReturn(new TenantGenerationQueueRow(2, 3, 1, 6, 2));
        when(controlPlaneMapper.selectScenarioCosts(100L, periodStart, observedBefore))
                .thenReturn(List.of(new TenantGenerationScenarioCostRow(
                        "heavy_generation", "multi_file", 3L, 2L, 7L)));
        MyBatisTenantGenerationControlPlaneRepository repository =
                new MyBatisTenantGenerationControlPlaneRepository(runtimeMapper, controlPlaneMapper);

        var facts = repository.load(100L, periodStart, observedBefore);

        assertEquals(900L, facts.consumedCredit());
        assertEquals(6, facts.queue().totalNonTerminalTasks());
        assertEquals(2, facts.queue().heavyNonTerminalTasks());
        assertEquals("multi_file", facts.scenarioCosts().getFirst().targetCodeGenType());
        assertEquals(3L, facts.scenarioCosts().getFirst().settledTasks());
        assertEquals(2L, facts.scenarioCosts().getFirst().successfulDeliveries());
        assertEquals(7L, facts.scenarioCosts().getFirst().totalCreditCost());
        var order = inOrder(runtimeMapper, controlPlaneMapper);
        order.verify(runtimeMapper).sumTenantGenerationCreditUsage(100L, periodStart, observedBefore);
        order.verify(controlPlaneMapper).selectQueueSummary(100L);
        order.verify(controlPlaneMapper).selectScenarioCosts(100L, periodStart, observedBefore);
    }

    @Test
    void invalidScopeMustFailBeforeMapperAccess() {
        GenerationTaskRuntimeMapper runtimeMapper = mock(GenerationTaskRuntimeMapper.class);
        TenantGenerationControlPlaneMapper controlPlaneMapper = mock(TenantGenerationControlPlaneMapper.class);
        MyBatisTenantGenerationControlPlaneRepository repository =
                new MyBatisTenantGenerationControlPlaneRepository(runtimeMapper, controlPlaneMapper);
        LocalDateTime now = LocalDateTime.of(2026, 8, 28, 12, 0);

        assertThrows(IllegalArgumentException.class,
                () -> repository.load(0L, now.minusDays(1), now));
        assertThrows(IllegalArgumentException.class,
                () -> repository.load(100L, now, now));

        verifyNoInteractions(runtimeMapper, controlPlaneMapper);
    }
}
