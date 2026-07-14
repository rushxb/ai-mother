package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EditLocatorBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WorkspaceSemanticIndexService.class, () -> mock(WorkspaceSemanticIndexService.class))
            .withBean(EditStatePersistenceService.class, () -> mock(EditStatePersistenceService.class))
            .withUserConfiguration(
                    EditLocatorProperties.class,
                    EditWorkspaceFileService.class,
                    SelectedElementFileLocator.class,
                    DiagnosticFileLocator.class,
                    EditContextPackageBuilder.class,
                    EditFileLocatorService.class
            );

    @Test
    void shouldCreateLocatorModulesWithoutCircularDependencies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EditFileLocatorService.class);
            assertThat(context).hasSingleBean(EditContextPackageBuilder.class);
            assertThat(context).hasSingleBean(EditWorkspaceFileService.class);
        });
    }
}
