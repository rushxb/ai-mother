package com.rush.rushaicodemother.orchestration.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectTemplateBootstrapperTest {

    @Test
    void shouldRejectBlankTargetBeforeAbsolutePathNormalization() {
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(
                Path.of("target", "test-workspaces", "template-bootstrap", "blank-target")
        );

        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> fixture.templateBootstrapper.bootstrap(
                        ProjectTemplateCatalog.VUE_BASIC,
                        Path.of("")
                )
        );

        assertEquals(TemplateMaterializationException.Reason.UNSAFE_TARGET, exception.reason());
    }
}
