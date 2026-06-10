package com.rush.rushaicodemother.orchestration.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateManifestServiceTest {

    private final TemplateManifestService service = new TemplateManifestService();

    @Test
    void shouldValidateCreateTemplateManifests() {
        for (String templateId : List.of(
                "vue-web-basic",
                "vue-web-admin",
                "vue-web-mobile",
                "vue-web-landing",
                "go-sqlite-backend-basic"
        )) {
            TemplateManifestService.ManifestValidationResult result = service.validateManifest(templateId);

            assertTrue(result.valid(), templateId + " manifest errors: " + result.errors());
        }
    }
}
