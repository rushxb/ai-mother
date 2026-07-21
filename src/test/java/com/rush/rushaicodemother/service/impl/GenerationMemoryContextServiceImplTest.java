package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.context.AiContextPack;
import com.rush.rushaicodemother.orchestration.context.AiContextPackAssembler;
import com.rush.rushaicodemother.orchestration.context.AiContextPackSection;
import com.rush.rushaicodemother.orchestration.context.AiContextPackSectionType;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
                app, "continue", CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(pack.render(), result);
    }
}
