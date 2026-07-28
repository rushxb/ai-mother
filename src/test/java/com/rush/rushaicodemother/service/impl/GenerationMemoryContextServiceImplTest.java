package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.AiContextPack;
import com.rush.rushaicodemother.orchestration.context.AiContextPackAssembler;
import com.rush.rushaicodemother.orchestration.context.AiContextPackSection;
import com.rush.rushaicodemother.orchestration.context.AiContextPackSectionType;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextReadExecutor;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationMemoryContextServiceImplTest {

    @Test
    void structuredPackMustNotBeTruncatedAgainAfterBudgeting() {
        GenerationTraceService traceService = mock(GenerationTraceService.class);
        GenerationSemanticMemoryService memoryService = mock(GenerationSemanticMemoryService.class);
        AiContextPackAssembler assembler = mock(AiContextPackAssembler.class);
        GenerationMemoryContextServiceImpl service = new GenerationMemoryContextServiceImpl(
                traceService, memoryService, assembler);
        String protectedContent = "BEGIN_UNTRUSTED_HISTORICAL_MEMORY\n"
                + "x".repeat(3_500)
                + "\nEND_UNTRUSTED_HISTORICAL_MEMORY";
        AiContextPack pack = new AiContextPack(1L, "app", "vue_project", List.of(
                new AiContextPackSection(
                        AiContextPackSectionType.SEMANTIC_MEMORY,
                        "memory",
                        protectedContent,
                        20,
                        Map.of())
        ));
        when(assembler.buildGenerationPack(any(), any(), any(), any(), any(), any())).thenReturn(pack);
        App app = App.builder().id(1L).userId(2L).build();

        String result = service.buildGenerationMemoryContext(
                "task-structured-pack", app, "continue", CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(pack.render(), result);
    }

    @Test
    void enabledParallelReadsMustOverlapIndependentMemorySources() throws Exception {
        GenerationTraceService traceService = mock(GenerationTraceService.class);
        GenerationSemanticMemoryService memoryService = mock(GenerationSemanticMemoryService.class);
        AiContextPackAssembler assembler = mock(AiContextPackAssembler.class);
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setParallelReadsEnabled(true);
        properties.setMaxConcurrentReads(3);
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        CountDownLatch allReadsStarted = new CountDownLatch(3);
        when(traceService.listRecentTasksByAppId(anyLong(), anyInt()))
                .thenAnswer(ignored -> awaitAllReads(allReadsStarted, List.of()));
        when(traceService.listRecentBuildLogsByAppId(anyLong(), anyInt()))
                .thenAnswer(ignored -> awaitAllReads(allReadsStarted, List.of()));
        when(memoryService.recall(anyLong(), anyLong(), anyString(), anySet()))
                .thenAnswer(ignored -> awaitAllReads(allReadsStarted, List.of()));
        when(assembler.buildGenerationPack(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiContextPack(1L, "app", "vue_project", List.of()));

        try (GenerationMemoryContextReadExecutor executor = new GenerationMemoryContextReadExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                executionContextService())) {
            GenerationMemoryContextServiceImpl service = new GenerationMemoryContextServiceImpl(
                    traceService, memoryService, assembler, executor);
            App app = App.builder().id(1L).tenantId(2L).userId(3L).build();

            assertEquals("", service.buildGenerationMemoryContext(
                    "task-parallel-memory", app, "继续生成", CodeGenTypeEnum.VUE_PROJECT));
            assertEquals(0, allReadsStarted.getCount());
        }
    }

    @Test
    void disabledByDefaultMustPreserveSequentialReadOrder() {
        GenerationTraceService traceService = mock(GenerationTraceService.class);
        GenerationSemanticMemoryService memoryService = mock(GenerationSemanticMemoryService.class);
        AiContextPackAssembler assembler = mock(AiContextPackAssembler.class);
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        List<String> readOrder = new ArrayList<>();
        when(traceService.listRecentTasksByAppId(anyLong(), anyInt())).thenAnswer(ignored -> {
            readOrder.add("recent_tasks");
            return List.of();
        });
        when(traceService.listRecentBuildLogsByAppId(anyLong(), anyInt())).thenAnswer(ignored -> {
            readOrder.add("recent_build_logs");
            return List.of();
        });
        when(memoryService.recall(anyLong(), anyLong(), anyString(), anySet())).thenAnswer(ignored -> {
            readOrder.add("semantic_memory");
            return List.of();
        });

        assertFalse(properties.isParallelReadsEnabled());
        try (GenerationMemoryContextReadExecutor executor = new GenerationMemoryContextReadExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                executionContextService())) {
            GenerationMemoryContextServiceImpl service = new GenerationMemoryContextServiceImpl(
                    traceService, memoryService, assembler, executor);
            App app = App.builder().id(1L).tenantId(2L).userId(3L).build();

            assertEquals("", service.buildGenerationMemoryContext(
                    "task-sequential-memory", app, "继续生成", CodeGenTypeEnum.VUE_PROJECT));
        }
        assertEquals(List.of("recent_tasks", "recent_build_logs", "semantic_memory"), readOrder);
    }

    private <T> T awaitAllReads(CountDownLatch allReadsStarted, T result) throws Exception {
        allReadsStarted.countDown();
        assertTrue(allReadsStarted.await(2, TimeUnit.SECONDS));
        return result;
    }

    private GenerationExecutionContextService executionContextService() {
        GenerationExecutionContextService service = mock(GenerationExecutionContextService.class);
        when(service.clampTimeout(nullable(String.class), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return service;
    }
}
