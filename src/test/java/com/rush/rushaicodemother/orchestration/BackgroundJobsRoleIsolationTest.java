package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.config.BackgroundJobSchedulingConfiguration;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskMaintenanceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundJobsRoleIsolationTest {

    @Test
    void standaloneRoleMustDisableSchedulingInfrastructureAndJobs() {
        new ApplicationContextRunner()
                .withPropertyValues("app.background-jobs.enabled=false")
                .withUserConfiguration(
                        BackgroundJobSchedulingConfiguration.class,
                        GenerationSessionCleanupConfiguration.class,
                        GenerationTaskMaintenanceConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            BackgroundJobSchedulingConfiguration.class);
                    assertThat(context).doesNotHaveBean(
                            GenerationSessionCleanupConfiguration.class);
                    assertThat(context).doesNotHaveBean(
                            GenerationTaskMaintenanceConfiguration.class);
                });
    }
}
