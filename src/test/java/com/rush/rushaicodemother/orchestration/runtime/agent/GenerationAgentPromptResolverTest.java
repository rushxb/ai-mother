package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.ClasspathPromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptRolloutSubject;
import com.rush.rushaicodemother.ai.prompt.PromptSelection;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationAgentPromptResolverTest {

    @Test
    void everyEngineeringAgentMustResolveThroughTheProductionPromptCatalogBinding() {
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        ClasspathPromptCatalog catalog = new ClasspathPromptCatalog(
                new AiPromptCatalogProperties(), resourceLoader, new ObjectMapper());
        GenerationAgentPromptResolver resolver = new GenerationAgentPromptResolver(
                new PromptSystemMessageTransformer(catalog), resourceLoader);

        for (GenerationAgentPromptBinding binding : GenerationAgentPromptBinding.all()) {
            InvocationContext context = mock(InvocationContext.class);
            when(context.chatMemoryId()).thenReturn(42L);
            PromptRolloutSubject subject = PromptRolloutSubject.from(context);
            PromptSelection selected = catalog.selectByKey(
                    binding.promptKey(), subject.cohortKey())
                    .orElseThrow();

            assertEquals(selected.content(), resolver.resolve(binding.codeGenType(), context));
        }
    }

    @Test
    void unsupportedGenerationTypeMustFailClosed() {
        GenerationAgentPromptResolver resolver = new GenerationAgentPromptResolver(
                new PromptSystemMessageTransformer(com.rush.rushaicodemother.ai.prompt.PromptCatalog.unmanaged()),
                new DefaultResourceLoader());

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(CodeGenTypeEnum.HTML, mock(InvocationContext.class)));
    }
}
