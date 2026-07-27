package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.bootstrap.StandaloneProcessExitCodeGenerator;
import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkBrowserProperties;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidateIdentityResolver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeService;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceManagementService;
import com.rush.rushaicodemother.service.aimodel.AiModelConfigurationPolicy;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import com.rush.rushaicodemother.service.aimodel.AiModelPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationBenchmarkWorkerSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerConfiguration.class)
            .withBean(GenerationBenchmarkWorkerProperties.class, this::workerProperties)
            .withBean(GenerationBenchmarkBrowserProperties.class, this::browserProperties)
            .withBean(GenerationBenchmarkBackendProperties.class, this::backendProperties)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AiModelPersistenceService.class,
                    () -> mock(AiModelPersistenceService.class))
            .withBean(AiModelConfigurationPolicy.class,
                    () -> mock(AiModelConfigurationPolicy.class))
            .withBean(PromptReleaseRepository.class,
                    () -> mock(PromptReleaseRepository.class))
            .withBean(PromptReleaseRuntime.class,
                    () -> mock(PromptReleaseRuntime.class))
            .withBean(PromptCatalog.class, () -> mock(PromptCatalog.class))
            .withBean(GenerationBenchmarkEvidenceCandidateIdentityResolver.class,
                    () -> mock(GenerationBenchmarkEvidenceCandidateIdentityResolver.class))
            .withBean(GenerationBenchmarkService.class,
                    () -> mock(GenerationBenchmarkService.class))
            .withBean(GenerationBenchmarkEvidenceEnvelopeService.class,
                    () -> mock(GenerationBenchmarkEvidenceEnvelopeService.class))
            .withBean(GenerationBenchmarkEvidenceManagementService.class,
                    () -> mock(GenerationBenchmarkEvidenceManagementService.class));

    @Test
    void enabledWorkerMustWireOneFrozenRuntimeAndOneExitCodeGenerator() {
        contextRunner
                .withPropertyValues("app.generation-benchmark.worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            GenerationBenchmarkAiModelConfigurationSnapshot.class);
                    assertThat(context).hasSingleBean(
                            AiModelEnabledConfigurationSnapshot.class);
                    assertThat(context).hasSingleBean(
                            GenerationBenchmarkCandidateRuntime.class);
                    assertThat(context).hasSingleBean(
                            GenerationBenchmarkCandidateInvocationTracker.class);
                    assertThat(context).hasSingleBean(
                            GenerationBenchmarkWorkerExecutionService.class);
                    assertThat(context).hasSingleBean(
                            StandaloneProcessExitCodeGenerator.class);
                });
    }

    @Test
    void disabledWorkerMustNotCreateStandaloneRuntimeBeans() {
        contextRunner
                .withPropertyValues("app.generation-benchmark.worker.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            GenerationBenchmarkAiModelConfigurationSnapshot.class);
                    assertThat(context).doesNotHaveBean(
                            GenerationBenchmarkWorkerExecutionService.class);
                    assertThat(context).doesNotHaveBean(
                            StandaloneProcessExitCodeGenerator.class);
                });
    }

    private GenerationBenchmarkWorkerProperties workerProperties() {
        GenerationBenchmarkWorkerProperties properties =
                new GenerationBenchmarkWorkerProperties();
        properties.setEnabled(true);
        properties.setOutputFile("target/benchmark-worker-result.json");
        properties.getCandidate().setSubjectType("AI_MODEL_ENABLE");
        properties.getCandidate().setModelId(7L);
        return properties;
    }

    private GenerationBenchmarkBrowserProperties browserProperties() {
        GenerationBenchmarkBrowserProperties properties =
                new GenerationBenchmarkBrowserProperties();
        properties.setEnabled(true);
        return properties;
    }

    private GenerationBenchmarkBackendProperties backendProperties() {
        GenerationBenchmarkBackendProperties properties =
                new GenerationBenchmarkBackendProperties();
        properties.setEnabled(true);
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            GenerationBenchmarkWorkerCandidateProvider.class,
            GenerationBenchmarkAiModelConfigurationSnapshot.class,
            GenerationBenchmarkCandidateRuntime.class,
            GenerationBenchmarkCandidateInvocationTracker.class,
            GenerationBenchmarkWorkerResultWriter.class,
            GenerationBenchmarkWorkerExecutionService.class,
            GenerationBenchmarkWorkerApplicationRunner.class
    })
    static class WorkerConfiguration {
    }
}
