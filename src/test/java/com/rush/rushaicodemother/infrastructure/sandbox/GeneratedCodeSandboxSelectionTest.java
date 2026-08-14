package com.rush.rushaicodemother.infrastructure.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedCodeSandboxSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HostLocalGeneratedCodeProcessSandbox.class);

    @Test
    void hostLocalSandboxMustRequireExplicitConfiguration() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(HostLocalGeneratedCodeProcessSandbox.class));

        contextRunner
                .withPropertyValues("app.generated-code-sandbox.mode=host-local")
                .run(context ->
                        assertThat(context).hasSingleBean(HostLocalGeneratedCodeProcessSandbox.class));
    }
}
