package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskMaintenanceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundJobsRoleIsolationTest {

    @Test
    void standaloneRoleMustDisableBothSchedulingEntryPoints() {
        new ApplicationContextRunner()
                .withPropertyValues("app.background-jobs.enabled=false")
                .withUserConfiguration(
                        GenerationSessionCleanupConfiguration.class,
                        GenerationTaskMaintenanceConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            GenerationSessionCleanupConfiguration.class);
                    assertThat(context).doesNotHaveBean(
                            GenerationTaskMaintenanceConfiguration.class);
                });
    }
}
