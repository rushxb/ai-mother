package com.rush.rushaicodemother.ai.generation;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightweightCodeGenerationExecutorTest {

    @Test
    void shouldRouteBlockingAndStreamingCallsToRegisteredTypeAdapters() {
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        HtmlCodeResult htmlResult = new HtmlCodeResult();
        MultiFileCodeResult multiFileResult = new MultiFileCodeResult();
        TokenStream htmlStream = mock(TokenStream.class);
        TokenStream multiFileStream = mock(TokenStream.class);
        InvocationParameters parameters = mock(InvocationParameters.class);
        when(service.generateHtmlCode("html-prompt")).thenReturn(htmlResult);
        when(service.generateMultiFileCode("multi-prompt")).thenReturn(multiFileResult);
        when(service.generateHtmlCodeStream("html-stream", parameters)).thenReturn(htmlStream);
        when(service.generateMultiFileCodeStream("multi-stream", parameters)).thenReturn(multiFileStream);
        LightweightCodeGenerationExecutor executor = new LightweightCodeGenerationExecutor(List.of(
                new HtmlLightweightCodeGenerationAdapter(),
                new MultiFileLightweightCodeGenerationAdapter()
        ));

        assertTrue(executor.supports(CodeGenTypeEnum.HTML));
        assertTrue(executor.supports(CodeGenTypeEnum.MULTI_FILE));
        assertFalse(executor.supports(CodeGenTypeEnum.VUE_PROJECT));
        assertSame(htmlResult, executor.generate(service, CodeGenTypeEnum.HTML, "html-prompt"));
        assertSame(multiFileResult,
                executor.generate(service, CodeGenTypeEnum.MULTI_FILE, "multi-prompt"));
        assertSame(htmlStream, executor.generateStream(
                service, CodeGenTypeEnum.HTML, "html-stream", parameters));
        assertSame(multiFileStream, executor.generateStream(
                service, CodeGenTypeEnum.MULTI_FILE, "multi-stream", parameters));

        verify(service).generateHtmlCode("html-prompt");
        verify(service).generateMultiFileCode("multi-prompt");
    }

    @Test
    void shouldRejectDuplicateAndMissingTypeAdapters() {
        assertThrows(IllegalStateException.class, () -> new LightweightCodeGenerationExecutor(List.of(
                new HtmlLightweightCodeGenerationAdapter(),
                new HtmlLightweightCodeGenerationAdapter()
        )));

        LightweightCodeGenerationExecutor executor = new LightweightCodeGenerationExecutor(List.of(
                new HtmlLightweightCodeGenerationAdapter(),
                new MultiFileLightweightCodeGenerationAdapter()
        ));
        assertThrows(BusinessException.class, () -> executor.generate(
                mock(AiCodeGeneratorService.class),
                CodeGenTypeEnum.VUE_PROJECT,
                "unsupported"
        ));
    }
}
