package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateSpecServiceTest {

    @Test
    void fallbackReasonMustNotExposeModelExceptionDetails() {
        AiCreateSpecServiceFactory serviceFactory = mock(AiCreateSpecServiceFactory.class);
        when(serviceFactory.createService())
                .thenThrow(new IllegalStateException("provider-api-key=secret-value"));
        CreateSpecService service = new CreateSpecService(serviceFactory, new CreateSpecNormalizer());
        SlotGroup group = new SlotGroup(
                "landing-slots",
                "vue-web-landing",
                "landing",
                List.of("landing_core_data"),
                0
        );
        CreateGenerationPlan plan = new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(),
                List.of(group),
                0.9,
                "test",
                "test",
                ""
        );

        CreateSpecService.SpecResult result = service.generate("创建一个官网", plan, group);

        assertEquals("local_spec_fallback:create_spec_exception", result.reason());
        assertFalse(result.reason().contains("secret-value"));
        assertFalse(result.spec().toString().contains("secret-value"));
    }
}
