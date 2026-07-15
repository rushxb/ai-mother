package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplatePreWarmProperties;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TemplatePreWarmConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(
                    TemplatePreWarmProperties.class,
                    TemplatePreWarmConfiguration.class,
                    TemplateNodeModulesPreWarmRunner.class
            )
            .withBean(TemplatePreWarmService.class, () -> mock(TemplatePreWarmService.class))
            .withBean(ProjectDependencyInstaller.class, () -> mock(ProjectDependencyInstaller.class))
            .withBean(ProjectTemplateMaterializer.class, () -> mock(ProjectTemplateMaterializer.class))
            .withBean(WorkspaceFileSystemService.class, () -> mock(WorkspaceFileSystemService.class));

    @Test
    void shouldNotCreatePreWarmRuntimeResourcesWhenDisabled() {
        contextRunner
                .withPropertyValues("app.template-pre-warm.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TemplateNodeModulesPreWarmRunner.class);
                    assertThat(context).doesNotHaveBean(
                            TemplatePreWarmConfiguration.TEMPLATE_PRE_WARM_TASK_EXECUTOR
                    );
                });
    }

    @Test
    void shouldCreateDedicatedBoundedExecutorWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.template-pre-warm.enabled=true",
                        "app.template-pre-warm.max-concurrency=3",
                        "app.template-pre-warm.template-ids[0]=vue-web-basic",
                        "app.template-pre-warm.template-ids[1]=vue-web-admin"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TemplateNodeModulesPreWarmRunner.class);
                    ThreadPoolTaskExecutor taskExecutor = context.getBean(
                            TemplatePreWarmConfiguration.TEMPLATE_PRE_WARM_TASK_EXECUTOR,
                            ThreadPoolTaskExecutor.class
                    );
                    assertEquals(3, taskExecutor.getCorePoolSize());
                    assertEquals(3, taskExecutor.getMaxPoolSize());
                    assertEquals(2, taskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity());
                });
    }
}
